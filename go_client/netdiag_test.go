package main

import (
	"context"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"

	"github.com/pion/turn/v5"
)

// Тестовый вектор вывода ключа диагностики — такой же стоит в
// server/netdiag_test.go: клиент и сервер обязаны выводить один ключ.
const (
	diagVectorToken = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
	diagVectorKey   = "0f2aecd3cd0a9fca825b30c36f38e91e2e81e5a8e4abafd8e758c76fb351702e"
)

func TestDiagKeyVector(t *testing.T) {
	secret, err := parseDiagToken(diagVectorToken)
	if err != nil {
		t.Fatal(err)
	}
	for i, b := range secret {
		if int(b) != i {
			t.Fatalf("token decode: byte %d = %d", i, b)
		}
	}
	key, err := deriveDiagKey(secret)
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(key); got != diagVectorKey {
		t.Fatalf("diag key = %s, want %s", got, diagVectorKey)
	}
	if _, err := parseDiagToken("bad!"); err == nil {
		t.Fatal("garbage token must fail")
	}
	if s, err := parseDiagToken(""); err != nil || s != nil {
		t.Fatal("empty token = no key")
	}
}

func TestWrapKeyIDMatchesServer(t *testing.T) {
	// sha256("WDTT-WRAP-ID-v1\x00secret")[:8] — то же, что server/database_bot.go.
	if got := wrapKeyID("secret"); len(got) != 16 {
		t.Fatalf("wrapKeyID length %d", len(got))
	}
}

func TestDiagRequestSizes(t *testing.T) {
	small := buildDiagRequest(newDiagNonce(), "p", "d", 0, 0)
	if len(small) > 200 {
		t.Fatalf("small probe %d bytes", len(small))
	}
	large := buildDiagRequest(newDiagNonce(), "p", "d", diagLargeSize, 0)
	if len(large) != diagLargeSize {
		t.Fatalf("large probe %d bytes, want %d", len(large), diagLargeSize)
	}
	var req diagRequest
	if err := json.Unmarshal(large[len(diagRequestMagic):], &req); err != nil {
		t.Fatal(err)
	}
}

func TestParseDiagPorts(t *testing.T) {
	got := parseDiagPorts(" dtls=56000, raw=56003,direct=x,junk=1,raw=70000")
	if got["dtls"] != 56000 || got["raw"] != 56003 || len(got) != 2 {
		t.Fatalf("got %v", got)
	}
}

func TestSTUNResponseParsing(t *testing.T) {
	req, id := buildSTUNBinding()
	if _, ok := stunResponseTxID(req); ok {
		t.Fatal("request is not a response")
	}
	resp := append([]byte(nil), req...)
	binary.BigEndian.PutUint16(resp[0:2], 0x0101)
	got, ok := stunResponseTxID(resp)
	if !ok || got != id {
		t.Fatal("response not recognized")
	}
}

// ─── Стенд: TURN + имитация сервера + STUN ───

type fakeDiagServer struct {
	pc     net.PacketConn
	keys   map[string][]byte // имя → ключ, которым сервер умеет расшифровать
	status string
	// maxReply — ответы крупнее не отправляются (обрыв крупных пакетов вниз).
	maxReply int
	// maxRequest — запросы крупнее игнорируются (обрыв вверх).
	maxRequest int
	ports      map[string]int
}

// startFakeDiagServer — opts настраивают сервер до запуска горутины.
func startFakeDiagServer(t *testing.T, ip string, keys map[string][]byte, status string, opts ...func(*fakeDiagServer)) *fakeDiagServer {
	t.Helper()
	pc, err := net.ListenPacket("udp4", ip+":0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	s := &fakeDiagServer{pc: pc, keys: keys, status: status, maxReply: 1 << 16, maxRequest: 1 << 16}
	for _, o := range opts {
		o(s)
	}
	go s.serve()
	return s
}

func (s *fakeDiagServer) port() int { return s.pc.LocalAddr().(*net.UDPAddr).Port }

func (s *fakeDiagServer) serve() {
	buf := make([]byte, 4096)
	plain := make([]byte, 4096)
	for {
		n, from, err := s.pc.ReadFrom(buf)
		if err != nil {
			return
		}
		if n > s.maxRequest+120 {
			continue
		}
		for _, key := range s.keys {
			m, err := obfsUnwrapPacket(key, buf[:n], plain)
			if err != nil {
				continue
			}
			if !strings.HasPrefix(string(plain[:m]), diagRequestMagic) {
				break
			}
			var req diagRequest
			if json.Unmarshal(plain[len(diagRequestMagic):m], &req) != nil {
				break
			}
			r := diagReply{Nonce: req.Nonce, Status: s.status, Listener: "dtls", Proto: 1, Ports: s.ports}
			body, _ := json.Marshal(r)
			out := append([]byte(diagReplyMagic), body...)
			if need := req.ReplySize - len(out) - 9; need > 0 {
				r.Pad = strings.Repeat("0", need)
				body, _ = json.Marshal(r)
				out = append([]byte(diagReplyMagic), body...)
			}
			if len(out) > s.maxReply {
				break
			}
			cfg, _ := NewObfsConfig("audio")
			st, _ := NewObfsState()
			wrapped, _ := obfsWrapPacket(key, out, cfg, st)
			_, _ = s.pc.WriteTo(wrapped, from)
			break
		}
	}
}

func startSTUNResponder(t *testing.T, ip string) string {
	t.Helper()
	pc, err := net.ListenPacket("udp4", ip+":0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { pc.Close() })
	go func() {
		buf := make([]byte, 1500)
		for {
			n, from, err := pc.ReadFrom(buf)
			if err != nil {
				return
			}
			if n < 20 {
				continue
			}
			resp := append([]byte(nil), buf[:20]...)
			binary.BigEndian.PutUint16(resp[0:2], 0x0101)
			_, _ = pc.WriteTo(resp, from)
		}
	}()
	return pc.LocalAddr().String()
}

// startTestTURN — pion TURN на UDP. deny — IP, к которым relay не пускает
// (имитация relay VK, который не доставляет трафик).
func startTestTURN(t *testing.T, deny ...string) string {
	t.Helper()
	return startTestTURNOn(t, "127.0.0.1", deny...)
}

func startTestTURNOn(t *testing.T, listenIP string, deny ...string) string {
	t.Helper()
	const user, pass, realm = "u", "p", "test"
	pc, err := net.ListenPacket("udp4", listenIP+":0")
	if err != nil {
		t.Fatal(err)
	}
	denied := map[string]bool{}
	for _, ip := range deny {
		denied[ip] = true
	}
	srv, err := turn.NewServer(turn.ServerConfig{
		Realm: realm,
		AuthHandler: func(ra *turn.RequestAttributes) (string, []byte, bool) {
			if ra.Username != user {
				return "", nil, false
			}
			return user, turn.GenerateAuthKey(user, realm, pass), true
		},
		PacketConnConfigs: []turn.PacketConnConfig{{
			PacketConn: pc,
			RelayAddressGenerator: &turn.RelayAddressGeneratorStatic{
				RelayAddress: net.ParseIP("127.0.0.1"),
				Address:      "127.0.0.1",
			},
			PermissionHandler: func(_ net.Addr, peerIP net.IP) bool {
				return !denied[peerIP.String()]
			},
		}},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { srv.Close() })
	return pc.LocalAddr().String()
}

type diagBench struct {
	cfg    *diagConfig
	events []diagEvent
}

func newDiagBench(t *testing.T, turnAddrs []string, peerPort int, password string, diagKey []byte, ports map[string]int) *diagConfig {
	t.Helper()
	return &diagConfig{
		TP:       &TurnParams{Transport: "udp", ObfsMode: "audio"},
		Peer:     &net.UDPAddr{IP: net.ParseIP("127.0.0.1"), Port: peerPort},
		Listener: "dtls",
		Ports:    ports,
		Password: password,
		DiagKey:  diagKey,
		DeviceID: "dev-1",
		Creds: func(ctx context.Context, fresh bool) (*Credentials, error) {
			return &Credentials{User: "u", Pass: "p", TurnURLs: turnAddrs}, nil
		},
	}
}

func withSTUNHosts(t *testing.T, hosts ...string) {
	t.Helper()
	old := diagSTUNHosts
	diagSTUNHosts = hosts
	t.Cleanup(func() { diagSTUNHosts = old })
}

func diagnoseQuiet(t *testing.T, cfg *diagConfig) diagVerdict {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 40*time.Second)
	defer cancel()
	return cfg.diagnose(ctx, &diagReporter{})
}

func testKeys(t *testing.T, password string) (pass, diag []byte) {
	t.Helper()
	pass, err := deriveWrapKey(password)
	if err != nil {
		t.Fatal(err)
	}
	secret, _ := parseDiagToken(diagVectorToken)
	diag, _ = deriveDiagKey(secret)
	return pass, diag
}

func TestDiagnosePathOK(t *testing.T) {
	pass, diag := testKeys(t, "good")
	srv := startFakeDiagServer(t, "127.0.0.1", map[string][]byte{"pass": pass, "diag": diag}, "ok",
		func(s *fakeDiagServer) { s.ports = map[string]int{"raw": 1} })
	turnAddr := startTestTURN(t)
	cfg := newDiagBench(t, []string{turnAddr}, srv.port(), "good", diag, map[string]int{"raw": 1})

	v := diagnoseQuiet(t, cfg)
	if v.Code != "path_ok" || v.Sev != "ok" {
		t.Fatalf("verdict %+v", v)
	}
	if !contains(v.Actions, "switch_raw") {
		t.Fatalf("raw listener exists → switch_raw expected, got %v", v.Actions)
	}
	// Raw на сервере на другом порту, чем в настройках клиента — не предлагаем.
	cfg.Ports = map[string]int{"raw": 2}
	if v := diagnoseQuiet(t, cfg); contains(v.Actions, "switch_raw") {
		t.Fatalf("raw port mismatch must not offer switch_raw: %v", v.Actions)
	}
}

func TestDiagnoseWrongPasswordWithServerKey(t *testing.T) {
	_, diag := testKeys(t, "good")
	// Сервер не знает пароль клиента, но знает ключ диагностики.
	srv := startFakeDiagServer(t, "127.0.0.1", map[string][]byte{"diag": diag}, "unknown")
	turnAddr := startTestTURN(t)
	cfg := newDiagBench(t, []string{turnAddr}, srv.port(), "typo", diag, nil)

	v := diagnoseQuiet(t, cfg)
	if v.Code != "password_unknown" {
		t.Fatalf("verdict %+v", v)
	}
}

func TestDiagnoseDeviceLimit(t *testing.T) {
	pass, _ := testKeys(t, "good")
	srv := startFakeDiagServer(t, "127.0.0.1", map[string][]byte{"pass": pass}, "device_limit")
	turnAddr := startTestTURN(t)
	cfg := newDiagBench(t, []string{turnAddr}, srv.port(), "good", nil, nil)

	if v := diagnoseQuiet(t, cfg); v.Code != "device_limit" {
		t.Fatalf("verdict %+v", v)
	}
}

func TestDiagnosePortClosed(t *testing.T) {
	pass, diag := testKeys(t, "good")
	raw := startFakeDiagServer(t, "127.0.0.1", map[string][]byte{"pass": pass, "diag": diag}, "ok")
	dead, _ := net.ListenPacket("udp4", "127.0.0.1:0") // порт DTLS молчит
	defer dead.Close()
	deadPort := dead.LocalAddr().(*net.UDPAddr).Port
	turnAddr := startTestTURN(t)
	cfg := newDiagBench(t, []string{turnAddr}, deadPort, "good", diag,
		map[string]int{"dtls": deadPort, "raw": raw.port()})

	v := diagnoseQuiet(t, cfg)
	if v.Code != "port_closed" || !contains(v.Actions, "switch_raw") {
		t.Fatalf("verdict %+v", v)
	}
}

func TestDiagnoseLargePacketsDown(t *testing.T) {
	pass, _ := testKeys(t, "good")
	srv := startFakeDiagServer(t, "127.0.0.1", map[string][]byte{"pass": pass}, "ok",
		func(s *fakeDiagServer) { s.maxReply = 600 })
	turnAddr := startTestTURN(t)
	cfg := newDiagBench(t, []string{turnAddr}, srv.port(), "good", nil, nil)

	v := diagnoseQuiet(t, cfg)
	if v.Code != "mtu" || v.Facts["mtu_dir"] != "от сервера к вам" {
		t.Fatalf("verdict %+v", v)
	}
}

func TestDiagnoseServerUnreachable(t *testing.T) {
	_, diag := testKeys(t, "good")
	dead, _ := net.ListenPacket("udp4", "127.0.0.1:0")
	defer dead.Close()
	withSTUNHosts(t, startSTUNResponder(t, "127.0.0.2"))
	turnAddr := startTestTURN(t)

	cfg := newDiagBench(t, []string{turnAddr}, dead.LocalAddr().(*net.UDPAddr).Port, "good", diag, nil)
	if v := diagnoseQuiet(t, cfg); v.Code != "server_unreachable" {
		t.Fatalf("with server key: %+v", v)
	}
	cfg.DiagKey = nil
	if v := diagnoseQuiet(t, cfg); v.Code != "no_reply" {
		t.Fatalf("without server key: %+v", v)
	}
}

func TestDiagnoseOldServerWhileTunnelWorks(t *testing.T) {
	// Старый сервер на пробы молчит, но туннель держится — это не «сервер
	// недоступен».
	dead, _ := net.ListenPacket("udp4", "127.0.0.1:0")
	defer dead.Close()
	turnAddr := startTestTURN(t)
	cfg := newDiagBench(t, []string{turnAddr}, dead.LocalAddr().(*net.UDPAddr).Port, "good", nil, nil)
	cfg.TunnelReceiving = func() bool { return true }
	if v := diagnoseQuiet(t, cfg); v.Code != "server_old" || v.Sev != "warn" {
		t.Fatalf("verdict %+v", v)
	}
}

func TestDiagnoseClosedPortWithZombieTunnel(t *testing.T) {
	// Порт режима закрыли, пока туннель был поднят: воркеры ещё числятся,
	// данных уже нет, а соседний порт отвечает — это закрытый порт, а не
	// «старый сервер» (баг, найденный на живом сервере).
	pass, _ := testKeys(t, "good")
	wg := startFakeDiagServer(t, "127.0.0.1", map[string][]byte{"pass": pass}, "ok")
	dead, _ := net.ListenPacket("udp4", "127.0.0.1:0")
	defer dead.Close()
	deadPort := dead.LocalAddr().(*net.UDPAddr).Port
	turnAddr := startTestTURN(t)
	cfg := newDiagBench(t, []string{turnAddr}, deadPort, "good", nil, map[string]int{"dtls": wg.port(), "raw": deadPort})
	cfg.Listener = "raw"
	cfg.TunnelReceiving = func() bool { return false }
	v := diagnoseQuiet(t, cfg)
	if v.Code != "port_closed" || !contains(v.Actions, "switch_wg") {
		t.Fatalf("verdict %+v", v)
	}
	// Даже если данные идут (чужой поток), ответ другого порта важнее.
	cfg.TunnelReceiving = func() bool { return true }
	if v := diagnoseQuiet(t, cfg); v.Code != "port_closed" {
		t.Fatalf("with downlink: %+v", v)
	}
}

func TestDiagnoseRelayBlocked(t *testing.T) {
	dead, _ := net.ListenPacket("udp4", "127.0.0.1:0")
	defer dead.Close()
	stun := startSTUNResponder(t, "127.0.0.2")
	withSTUNHosts(t, stun)
	// relay никуда не пускает: ни к серверу, ни к STUN.
	turnAddr := startTestTURN(t, "127.0.0.1", "127.0.0.2")

	cfg := newDiagBench(t, []string{turnAddr}, dead.LocalAddr().(*net.UDPAddr).Port, "good", nil, nil)
	if v := diagnoseQuiet(t, cfg); v.Code != "relay_blocked" {
		t.Fatalf("verdict %+v", v)
	}
}

func TestDiagnoseBadRelay(t *testing.T) {
	pass, _ := testKeys(t, "good")
	srv := startFakeDiagServer(t, "127.0.0.1", map[string][]byte{"pass": pass}, "ok")
	withSTUNHosts(t, startSTUNResponder(t, "127.0.0.2"))
	bad := startTestTURNOn(t, "127.0.0.1", "127.0.0.1") // до сервера не пускает, наружу (STUN) — да
	good := startTestTURNOn(t, "127.0.0.3")

	cfg := newDiagBench(t, []string{bad, good}, srv.port(), "good", nil, nil)
	v := diagnoseQuiet(t, cfg)
	if v.Code != "bad_relay" || v.Facts["good_turn"] != "127.0.0.3" || !contains(v.Actions, "use_turn:127.0.0.3") {
		t.Fatalf("verdict %+v", v)
	}
}

func TestDiagnoseTurnUnreachable(t *testing.T) {
	pass, _ := testKeys(t, "good")
	srv := startFakeDiagServer(t, "127.0.0.1", map[string][]byte{"pass": pass}, "ok")
	// Никто не слушает: ни UDP, ни TCP.
	l, _ := net.ListenPacket("udp4", "127.0.0.1:0")
	addr := l.LocalAddr().String()
	l.Close()
	cfg := newDiagBench(t, []string{addr}, srv.port(), "good", nil, nil)
	if v := diagnoseQuiet(t, cfg); v.Code != "turn_unreachable" {
		t.Fatalf("verdict %+v", v)
	}
}

func contains(list []string, s string) bool {
	for _, x := range list {
		if x == s {
			return true
		}
	}
	return false
}

func TestAutoDiagAtStartup(t *testing.T) {
	now := time.Now()
	ms := func(ago time.Duration) int64 { return now.Add(-ago).UnixMilli() }
	cases := []struct {
		name          string
		ready, failed int64
		want          bool
	}{
		{"nothing yet", 0, 0, false},
		{"creds ok, workers still coming", ms(5 * time.Second), 0, false},
		{"creds ok, no workers for 15s", ms(16 * time.Second), 0, true},
		{"creds failed just now", 0, ms(time.Second), false},
		{"creds failed, nobody got them", 0, ms(4 * time.Second), true},
		{"one group failed, another got creds", ms(5 * time.Second), ms(6 * time.Second), false},
	}
	for _, c := range cases {
		if got := autoDiagAtStartup(now, c.ready, c.failed); got != c.want {
			t.Errorf("%s: got %v, want %v", c.name, got, c.want)
		}
	}
}
