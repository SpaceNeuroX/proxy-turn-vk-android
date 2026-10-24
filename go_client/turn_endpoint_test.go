package main

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"math/big"
	"net"
	"reflect"
	"testing"
	"time"

	"github.com/pion/transport/v4/stdnet"
	"github.com/pion/turn/v5"
)

func TestTurnURLsToAddressesKeepsTLSScheme(t *testing.T) {
	got := turnURLsToAddresses([]string{
		"turn:1.2.3.4:3478?transport=udp",
		"turn:1.2.3.4:3478?transport=tcp",
		"turns:turn.example.com:443?transport=tcp",
		"  ",
		"5.6.7.8:3478",
	})
	want := []string{
		"1.2.3.4:3478",
		"1.2.3.4:3478",
		"turns:turn.example.com:443",
		"5.6.7.8:3478",
	}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestParseTurnHosts(t *testing.T) {
	got := parseTurnHosts(" 1.2.3.4, 5.6.7.8:3478;[2001:db8::1]:443\n9.9.9.9 ")
	want := []string{"1.2.3.4", "5.6.7.8:3478", "[2001:db8::1]:443", "9.9.9.9"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	if hosts := parseTurnHosts(""); hosts != nil {
		t.Fatalf("empty input gave %v", hosts)
	}
}

func TestNormalizeTurnTransport(t *testing.T) {
	cases := map[string]string{
		"": "udp", "udp": "udp", "TCP": "tcp", " tls ": "tls", "turns": "tls", "junk": "udp",
	}
	for in, want := range cases {
		if got := normalizeTurnTransport(in); got != want {
			t.Errorf("normalizeTurnTransport(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestSelectTurnTarget(t *testing.T) {
	vk := []string{"1.1.1.1:3478", "2.2.2.2:3478", "turns:turn.vk.example:443"}

	tests := []struct {
		name    string
		tp      TurnParams
		entries []string
		idx     int
		want    turnTarget
	}{
		{
			name: "udp rotates over plain VK addresses only",
			tp:   TurnParams{Transport: "udp"}, entries: vk, idx: 1,
			want: turnTarget{Addr: "2.2.2.2:3478", Transport: "udp"},
		},
		{
			name: "tcp uses plain VK addresses",
			tp:   TurnParams{Transport: "tcp"}, entries: vk, idx: 2,
			want: turnTarget{Addr: "1.1.1.1:3478", Transport: "tcp"},
		},
		{
			name: "tls picks turns address with VK domain as SNI",
			tp:   TurnParams{Transport: "tls"}, entries: vk, idx: 5,
			want: turnTarget{Addr: "turn.vk.example:443", SNI: "turn.vk.example", Transport: "tls"},
		},
		{
			name: "tls without turns addresses falls back to tcp",
			tp:   TurnParams{Transport: "tls"}, entries: vk[:2], idx: 0,
			want: turnTarget{Addr: "1.1.1.1:3478", Transport: "tcp"},
		},
		{
			name: "own hosts replace VK host, keep VK port",
			tp:   TurnParams{Transport: "udp", Hosts: []string{"9.9.9.9", "8.8.8.8"}}, entries: vk, idx: 1,
			want: turnTarget{Addr: "8.8.8.8:3478", Transport: "udp"},
		},
		{
			name: "own host port wins over -port",
			tp:   TurnParams{Transport: "udp", Hosts: []string{"9.9.9.9:19302"}, Port: "1000"}, entries: vk, idx: 0,
			want: turnTarget{Addr: "9.9.9.9:19302", Transport: "udp"},
		},
		{
			name: "-port overrides VK port",
			tp:   TurnParams{Transport: "udp", Port: "1000"}, entries: vk, idx: 0,
			want: turnTarget{Addr: "1.1.1.1:1000", Transport: "udp"},
		},
		{
			name: "tls with own IP keeps VK domain as SNI",
			tp:   TurnParams{Transport: "tls", Hosts: []string{"7.7.7.7"}}, entries: vk, idx: 0,
			want: turnTarget{Addr: "7.7.7.7:443", SNI: "turn.vk.example", Transport: "tls"},
		},
		{
			name: "own IPv6 host",
			tp:   TurnParams{Transport: "udp", Hosts: []string{"[2001:db8::1]:443"}}, entries: vk, idx: 0,
			want: turnTarget{Addr: "[2001:db8::1]:443", Transport: "udp"},
		},
		{
			name: "only turns addresses in udp mode are still usable",
			tp:   TurnParams{Transport: "udp"}, entries: []string{"turns:turn.vk.example:443"}, idx: 0,
			want: turnTarget{Addr: "turn.vk.example:443", SNI: "turn.vk.example", Transport: "udp"},
		},
		{
			name: "address without port gets default",
			tp:   TurnParams{Transport: "udp"}, entries: []string{"1.1.1.1"}, idx: 0,
			want: turnTarget{Addr: "1.1.1.1:3478", Transport: "udp"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, err := selectTurnTarget(&tt.tp, tt.entries, tt.idx)
			if err != nil {
				t.Fatal(err)
			}
			if got != tt.want {
				t.Fatalf("got %+v, want %+v", got, tt.want)
			}
		})
	}

	if _, err := selectTurnTarget(&TurnParams{}, nil, 0); err == nil {
		t.Fatal("expected error for empty TURN list")
	}
}

func selfSignedCert(t *testing.T) tls.Certificate {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(1),
		Subject:      pkix.Name{CommonName: "turn.test"},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(time.Hour),
		IPAddresses:  []net.IP{net.ParseIP("127.0.0.1")},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}
}

// TestTURNOverTLSRelaysData поднимает настоящий pion TURN-сервер на TLS и
// проверяет весь путь клиента: uTLS-хендшейк, Allocate и обмен данными через
// relay с UDP echo-сервером.
func TestTURNOverTLSRelaysData(t *testing.T) {
	const user, pass, realm = "u", "p", "test"

	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{selfSignedCert(t)}})
	if err != nil {
		t.Fatal(err)
	}
	srv, err := turn.NewServer(turn.ServerConfig{
		Realm: realm,
		AuthHandler: func(ra *turn.RequestAttributes) (string, []byte, bool) {
			if ra.Username != user {
				return "", nil, false
			}
			return user, turn.GenerateAuthKey(user, realm, pass), true
		},
		ListenerConfigs: []turn.ListenerConfig{{
			Listener: ln,
			RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{
				RelayAddress: net.ParseIP("127.0.0.1"),
				Address:      "127.0.0.1",
			},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer srv.Close()

	echo, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer echo.Close()
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := echo.ReadFrom(buf)
			if err != nil {
				return
			}
			_, _ = echo.WriteTo(buf[:n], from)
		}
	}()

	target, err := selectTurnTarget(&TurnParams{Transport: "tls"}, []string{"turns:" + ln.Addr().String()}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if target.Transport != turnTransportTLS {
		t.Fatalf("transport = %q, want tls", target.Transport)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	conn, closer, err := dialTURNConn(ctx, target)
	if err != nil {
		t.Fatal(err)
	}
	defer closer.Close()

	tc, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr: target.Addr,
		TURNServerAddr: target.Addr,
		Conn:           conn,
		Net:            new(stdnet.Net),
		Username:       user,
		Password:       pass,
		LoggerFactory:  &NullLoggerFactory{},
	})
	if err != nil {
		t.Fatal(err)
	}
	defer tc.Close()
	if err := tc.Listen(); err != nil {
		t.Fatal(err)
	}
	relay, err := tc.Allocate()
	if err != nil {
		t.Fatalf("Allocate over TLS: %v", err)
	}
	defer relay.Close()

	msg := []byte("qwdtt over turns")
	if _, err := relay.WriteTo(msg, echo.LocalAddr()); err != nil {
		t.Fatal(err)
	}
	_ = relay.SetReadDeadline(time.Now().Add(5 * time.Second))
	buf := make([]byte, 1500)
	n, _, err := relay.ReadFrom(buf)
	if err != nil {
		t.Fatalf("read echo through relay: %v", err)
	}
	if string(buf[:n]) != string(msg) {
		t.Fatalf("echo = %q, want %q", buf[:n], msg)
	}
}

func TestDialTURNTLSRejectsPlainTCP(t *testing.T) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		c, err := ln.Accept()
		if err == nil {
			_, _ = c.Write([]byte("not tls at all\r\n"))
			c.Close()
		}
	}()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, _, err := dialTURNConn(ctx, turnTarget{Addr: ln.Addr().String(), Transport: turnTransportTLS}); err == nil {
		t.Fatal("expected TLS handshake error")
	}
}
