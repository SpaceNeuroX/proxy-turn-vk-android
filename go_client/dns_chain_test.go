package main

import (
	"context"
	"encoding/binary"
	"net"
	"reflect"
	"sync/atomic"
	"testing"
	"time"
)

// fakeDNS — UDP DNS-сервер для тестов. mode: "answer" (A-запись ip),
// "silent" (молчит), "refused" (REFUSED).
type fakeDNS struct {
	pc    net.PacketConn
	ip    net.IP
	mode  string
	count atomic.Int32
}

func startFakeDNS(t *testing.T, mode string, ip string) *fakeDNS {
	t.Helper()
	pc, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	f := &fakeDNS{pc: pc, ip: net.ParseIP(ip).To4(), mode: mode}
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			f.count.Add(1)
			if f.mode == "silent" || n < 12 {
				continue
			}
			_, _ = pc.WriteTo(f.reply(buf[:n]), from)
		}
	}()
	return f
}

func (f *fakeDNS) addr() string { return f.pc.LocalAddr().String() }

func (f *fakeDNS) reply(q []byte) []byte {
	// Конец вопроса: заголовок 12 байт + QNAME + QTYPE/QCLASS.
	i := 12
	for i < len(q) && q[i] != 0 {
		i += int(q[i]) + 1
	}
	qEnd := i + 5
	if qEnd > len(q) {
		qEnd = len(q)
	}
	r := append([]byte(nil), q[:qEnd]...)
	r[2] = 0x81 // QR + RD
	if f.mode == "refused" {
		r[3] = 0x05
		binary.BigEndian.PutUint16(r[6:8], 0)
		return r
	}
	r[3] = 0x80 // RA, rcode 0
	qtype := binary.BigEndian.Uint16(q[qEnd-4 : qEnd-2])
	if qtype != 1 { // только A
		binary.BigEndian.PutUint16(r[6:8], 0)
		return r
	}
	binary.BigEndian.PutUint16(r[6:8], 1) // ANCOUNT
	r = append(r, 0xC0, 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4)
	return append(r, f.ip...)
}

func dnsQueryA(id uint16, name string) []byte {
	q := make([]byte, 12)
	binary.BigEndian.PutUint16(q[0:2], id)
	q[2] = 0x01
	q[5] = 1
	for _, label := range splitLabels(name) {
		q = append(q, byte(len(label)))
		q = append(q, label...)
	}
	return append(q, 0, 0, 1, 0, 1)
}

func splitLabels(name string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(name); i++ {
		if i == len(name) || name[i] == '.' {
			if i > start {
				out = append(out, name[start:i])
			}
			start = i + 1
		}
	}
	return out
}

func udpChain(primary, fallback []string) *dnsChain {
	var ups []dnsUpstream
	for _, a := range primary {
		ups = append(ups, dnsUpstream{kind: "udp", addr: a})
	}
	for _, a := range fallback {
		ups = append(ups, dnsUpstream{kind: "udp", addr: a, network: true, backup: true})
	}
	return newDNSChain("test", ups)
}

func answerIP(t *testing.T, resp []byte) net.IP {
	t.Helper()
	if len(resp) < 4 {
		t.Fatalf("short response")
	}
	return net.IP(resp[len(resp)-4:])
}

func TestDNSChainPrimaryWorks(t *testing.T) {
	primary := startFakeDNS(t, "answer", "1.1.1.1")
	network := startFakeDNS(t, "answer", "2.2.2.2")
	c := udpChain([]string{primary.addr()}, []string{network.addr()})
	resp, err := c.resolve(context.Background(), dnsQueryA(1, "login.vk.ru"))
	if err != nil {
		t.Fatal(err)
	}
	if got := answerIP(t, resp); !got.Equal(net.ParseIP("1.1.1.1")) {
		t.Fatalf("answer from %v, want primary", got)
	}
	if network.count.Load() != 0 {
		t.Fatal("network DNS must not be asked while the primary works")
	}
}

func TestDNSChainFallsBackToNetworkDNS(t *testing.T) {
	primary := startFakeDNS(t, "silent", "")
	network := startFakeDNS(t, "answer", "2.2.2.2")
	c := udpChain([]string{primary.addr()}, []string{network.addr()})

	start := time.Now()
	resp, err := c.resolve(context.Background(), dnsQueryA(2, "login.vk.ru"))
	if err != nil {
		t.Fatal(err)
	}
	if got := answerIP(t, resp); !got.Equal(net.ParseIP("2.2.2.2")) {
		t.Fatalf("answer from %v, want network DNS", got)
	}
	if took := time.Since(start); took > dnsUDPTimeout+time.Second {
		t.Fatalf("fallback took %v", took)
	}

	// Пока основной «остывает», следующий запрос идёт сразу на DNS сети.
	start = time.Now()
	if _, err := c.resolve(context.Background(), dnsQueryA(3, "api.vk.me")); err != nil {
		t.Fatal(err)
	}
	if took := time.Since(start); took > 300*time.Millisecond {
		t.Fatalf("second query waited %v for the dead primary again", took)
	}
	if primary.count.Load() != 1 {
		t.Fatalf("dead primary asked %d times during cooldown, want 1", primary.count.Load())
	}
}

func TestDNSChainRefusedGoesToNext(t *testing.T) {
	primary := startFakeDNS(t, "refused", "")
	network := startFakeDNS(t, "answer", "2.2.2.2")
	c := udpChain([]string{primary.addr()}, []string{network.addr()})
	resp, err := c.resolve(context.Background(), dnsQueryA(4, "login.vk.ru"))
	if err != nil {
		t.Fatal(err)
	}
	if got := answerIP(t, resp); !got.Equal(net.ParseIP("2.2.2.2")) {
		t.Fatalf("answer from %v after REFUSED, want network DNS", got)
	}
}

func TestDNSChainAllDead(t *testing.T) {
	a := startFakeDNS(t, "silent", "")
	b := startFakeDNS(t, "silent", "")
	c := udpChain([]string{a.addr()}, []string{b.addr()})
	if _, err := c.resolve(context.Background(), dnsQueryA(5, "login.vk.ru")); err == nil {
		t.Fatal("expected error when every server is silent")
	}
}

func TestDNSChainThroughGoResolver(t *testing.T) {
	// Полный путь, как в клиенте: net.DefaultResolver → chainConn → цепочка.
	primary := startFakeDNS(t, "silent", "")
	network := startFakeDNS(t, "answer", "3.3.3.3")
	old := net.DefaultResolver
	t.Cleanup(func() { net.DefaultResolver = old })

	setupGlobalResolver("custom:"+primary.addr(), []string{network.addr()})
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	ips, err := net.DefaultResolver.LookupIPAddr(ctx, "login.vk.ru")
	if err != nil {
		t.Fatal(err)
	}
	found := false
	for _, ip := range ips {
		if ip.IP.Equal(net.ParseIP("3.3.3.3")) {
			found = true
		}
	}
	if !found {
		t.Fatalf("resolved %v, want 3.3.3.3 from network DNS", ips)
	}
}

func TestParseDNSFallback(t *testing.T) {
	got := parseDNSFallback(" 10.0.0.1, 2a00:1fa0::1,127.0.0.1, fe80::1, 10.0.0.1, [2a00::2]:53, 8.8.8.8:5353, junk")
	want := []string{"10.0.0.1:53", "[2a00:1fa0::1]:53", "[2a00::2]:53", "8.8.8.8:5353"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestBuildDNSUpstreams(t *testing.T) {
	net := []string{"10.0.0.1:53"}

	ups := buildDNSUpstreams("operator", net)
	if len(ups) < 2 || ups[0].addr != "10.0.0.1:53" || !ups[0].network || ups[0].backup || !ups[1].backup {
		t.Fatalf("operator: network DNS main, Yandex backup; got %+v", ups)
	}
	if ups := buildDNSUpstreams("operator", nil); len(ups) == 0 || ups[0].backup {
		t.Fatalf("operator without network DNS: Yandex becomes main: %+v", ups)
	}

	ups = buildDNSUpstreams("cloudflare", net)
	last := ups[len(ups)-1]
	if ups[0].addr != "1.1.1.1:53" || ups[0].backup || !last.backup || !last.network || last.addr != "10.0.0.1:53" {
		t.Fatalf("cloudflare: preset main, network backup; got %+v", ups)
	}

	ups = buildDNSUpstreams("doh-yandex", net)
	if ups[0].kind != "doh" || ups[len(ups)-1].kind != "udp" || !ups[len(ups)-1].backup {
		t.Fatalf("doh: DoH first, network UDP last; got %+v", ups)
	}

	if ups := buildDNSUpstreams("yandex", nil); len(ups) != 2 {
		t.Fatalf("no network DNS: only the preset; got %+v", ups)
	}
}

func TestValidateDNSReply(t *testing.T) {
	q := dnsQueryA(0xABCD, "vk.ru")
	ok := (&fakeDNS{ip: net.ParseIP("1.2.3.4").To4(), mode: "answer"}).reply(q)
	if err := validateDNSReply(q, ok); err != nil {
		t.Fatal(err)
	}
	wrongID := append([]byte(nil), ok...)
	wrongID[1] ^= 0xFF
	if validateDNSReply(q, wrongID) == nil {
		t.Fatal("reply to another query must be rejected")
	}
	nx := append([]byte(nil), ok...)
	nx[3] = 0x83 // NXDOMAIN — нормальный ответ
	if err := validateDNSReply(q, nx); err != nil {
		t.Fatalf("NXDOMAIN must be accepted: %v", err)
	}
}
