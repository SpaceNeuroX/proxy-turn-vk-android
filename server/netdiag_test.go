package main

import (
	"encoding/hex"
	"encoding/json"
	"errors"
	"net"
	neturl "net/url"
	"strings"
	"testing"
	"time"
)

// Тот же вектор, что в go_client/netdiag_test.go.
const (
	diagVectorToken = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8"
	diagVectorKey   = "0f2aecd3cd0a9fca825b30c36f38e91e2e81e5a8e4abafd8e758c76fb351702e"
)

func vectorSecret() []byte {
	s := make([]byte, 32)
	for i := range s {
		s[i] = byte(i)
	}
	return s
}

func TestDiagKeyVector(t *testing.T) {
	if got := diagTokenFromSecret(vectorSecret()); got != diagVectorToken {
		t.Fatalf("token %s", got)
	}
	key, err := deriveDiagKey(vectorSecret())
	if err != nil {
		t.Fatal(err)
	}
	if got := hex.EncodeToString(key); got != diagVectorKey {
		t.Fatalf("key %s", got)
	}
}

func TestLoadOrCreateDiagSecretPersists(t *testing.T) {
	dir := t.TempDir()
	a, err := loadOrCreateDiagSecret(dir)
	if err != nil {
		t.Fatal(err)
	}
	b, err := loadOrCreateDiagSecret(dir)
	if err != nil {
		t.Fatal(err)
	}
	if hex.EncodeToString(a) != hex.EncodeToString(b) || len(a) != 32 {
		t.Fatal("diag secret must survive restarts")
	}
}

func TestWouldBindDeviceMatchesCanConnectAndBind(t *testing.T) {
	cases := []PasswordEntry{
		{MaxDevices: 1},
		{MaxDevices: 1, DeviceIDs: []string{"a"}},
		{MaxDevices: 2, DeviceIDs: []string{"a"}},
		{MaxDevices: 2, DeviceIDs: []string{"a", "b"}},
		{MaxDevices: 1, DeviceID: "a"},
		{MaxDevices: 2, DeviceID: "a"},
		{MaxDevices: 0, DeviceID: "x"},
	}
	for i, c := range cases {
		for _, dev := range []string{"a", "b", "c"} {
			ro := c
			ro.DeviceIDs = append([]string(nil), c.DeviceIDs...)
			rw := c
			rw.DeviceIDs = append([]string(nil), c.DeviceIDs...)
			if got, want := ro.wouldBindDevice(dev), rw.canConnectAndBind(dev); got != want {
				t.Fatalf("case %d dev %s: wouldBind=%v canConnectAndBind=%v", i, dev, got, want)
			}
			if len(ro.DeviceIDs) != len(c.DeviceIDs) || ro.DeviceID != c.DeviceID {
				t.Fatalf("case %d: wouldBindDevice must not modify the entry", i)
			}
		}
	}
}

// ─── Стенд: настоящий wrapPacketListener ───

type diagTestServer struct {
	addr    *net.UDPAddr
	results chan error
}

func setupDiagServer(t *testing.T) *diagTestServer {
	t.Helper()
	db = &Database{
		MainPassword: "main-pass",
		Passwords: map[string]*PasswordEntry{
			"good":    {MaxDevices: 1, DeviceIDs: []string{"dev-1"}},
			"old":     {MaxDevices: 1, ExpiresAt: time.Now().Add(-time.Hour).Unix()},
			"off":     {MaxDevices: 1, IsDeactivated: true},
			"full":    {MaxDevices: 1, DeviceIDs: []string{"someone-else"}},
			"roomy":   {MaxDevices: 3, DeviceIDs: []string{"x"}},
			"expired": {ExpiresAt: 1},
		},
		Devices: map[string]*ClientDevice{},
	}
	keys := newWrapKeyStore()
	if err := keys.SetPasswords("main-pass", []string{"good", "full", "roomy"}); err != nil {
		t.Fatal(err)
	}
	serverDiag = &diagState{
		ports:     map[string]int{},
		seen:      map[string]time.Time{},
		authFails: map[string]*diagAuthFail{},
		probes:    map[string]int{},
		tokens:    diagRateBurst,
	}
	if err := serverDiag.configure(vectorSecret()); err != nil {
		t.Fatal(err)
	}
	serverDiag.setPort("dtls", "0.0.0.0:56000")
	serverDiag.setPort("raw", "0.0.0.0:56003")

	ln, err := listenWrapped("dtls", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")}, keys)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	s := &diagTestServer{addr: ln.Addr().(*net.UDPAddr), results: make(chan error, 64)}
	go func() {
		for {
			pc, _, err := ln.Accept()
			if err != nil {
				return
			}
			go func(pc net.PacketConn) {
				defer pc.Close()
				buf := make([]byte, 1600)
				_ = pc.SetReadDeadline(time.Now().Add(3 * time.Second))
				_, _, err := pc.ReadFrom(buf)
				s.results <- err
			}(pc)
		}
	}()
	return s
}

func clientProbe(t *testing.T, srv *diagTestServer, key []byte, req diagRequest) (*diagReply, int) {
	t.Helper()
	c, err := net.ListenUDP("udp4", &net.UDPAddr{IP: net.ParseIP("127.0.0.1")})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	body, _ := json.Marshal(req)
	payload := append([]byte(diagRequestMagic), body...)
	cfg, _ := NewObfsConfig("audio")
	st, _ := NewObfsState()
	wire, err := obfsWrapPacket(key, payload, cfg, st, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.WriteToUDP(wire, srv.addr); err != nil {
		t.Fatal(err)
	}
	_ = c.SetReadDeadline(time.Now().Add(1500 * time.Millisecond))
	buf := make([]byte, 2048)
	n, err := c.Read(buf)
	if err != nil {
		return nil, 0
	}
	plain := make([]byte, 2048)
	m, err := obfsUnwrapPacket(key, buf[:n], plain)
	if err != nil {
		t.Fatalf("reply does not decrypt with the probe key: %v", err)
	}
	if !strings.HasPrefix(string(plain[:m]), diagReplyMagic) {
		t.Fatalf("not a diag reply: %q", plain[:m])
	}
	var r diagReply
	if err := json.Unmarshal(plain[len(diagReplyMagic):m], &r); err != nil {
		t.Fatal(err)
	}
	if r.Nonce != req.Nonce {
		t.Fatalf("nonce mismatch")
	}
	return &r, m
}

func nonce(i int) string { return strings.Repeat("0", 30) + hex.EncodeToString([]byte{byte(i)}) }

func TestDiagProbeWithPasswordKey(t *testing.T) {
	srv := setupDiagServer(t)
	key, _ := deriveWrapKey("good")
	r, _ := clientProbe(t, srv, key, diagRequest{Nonce: nonce(1), Time: time.Now().Unix(), Device: "dev-1"})
	if r == nil || r.Status != diagStatusOK || r.Listener != "dtls" || r.Ports["raw"] != 56003 {
		t.Fatalf("reply %+v", r)
	}
	if err := <-srv.results; !errors.Is(err, errDiagnosticProbe) {
		t.Fatalf("connection must end with errDiagnosticProbe, got %v", err)
	}
}

func TestDiagProbeWithServerKey(t *testing.T) {
	srv := setupDiagServer(t)
	dkey, _ := deriveDiagKey(vectorSecret())
	cases := []struct {
		pass, dev, want string
	}{
		{"good", "dev-1", diagStatusOK},
		{"main-pass", "anything", diagStatusOK},
		{"typo", "dev-1", diagStatusUnknown},
		{"old", "dev-1", diagStatusExpired},
		{"off", "dev-1", diagStatusDeactivated},
		{"full", "dev-1", diagStatusDeviceLimit},
		{"roomy", "new-device", diagStatusOK},
	}
	for i, c := range cases {
		r, _ := clientProbe(t, srv, dkey, diagRequest{Nonce: nonce(10 + i), Time: time.Now().Unix(), PassID: wrapKeyID(c.pass), Device: c.dev})
		if r == nil || r.Status != c.want {
			t.Fatalf("%s: reply %+v, want %s", c.pass, r, c.want)
		}
	}
	if got := db.Passwords["roomy"].DeviceIDs; len(got) != 1 {
		t.Fatalf("diagnostics must not bind devices, got %v", got)
	}
}

func TestDiagProbeReplySizeAndLimits(t *testing.T) {
	srv := setupDiagServer(t)
	key, _ := deriveWrapKey("good")
	r, m := clientProbe(t, srv, key, diagRequest{Nonce: nonce(30), Time: time.Now().Unix(), ReplySize: 1100})
	if r == nil || m != 1100 {
		t.Fatalf("large reply %d bytes, want 1100", m)
	}
	_, m = clientProbe(t, srv, key, diagRequest{Nonce: nonce(31), Time: time.Now().Unix(), ReplySize: 60000})
	if m != diagMaxReply {
		t.Fatalf("reply must be capped at %d, got %d", diagMaxReply, m)
	}
}

func TestDiagProbeRejectsReplayAndStale(t *testing.T) {
	srv := setupDiagServer(t)
	key, _ := deriveWrapKey("good")
	req := diagRequest{Nonce: nonce(40), Time: time.Now().Unix()}
	if r, _ := clientProbe(t, srv, key, req); r == nil {
		t.Fatal("first probe must be answered")
	}
	if r, _ := clientProbe(t, srv, key, req); r != nil {
		t.Fatal("replayed nonce must be ignored")
	}
	stale := diagRequest{Nonce: nonce(41), Time: time.Now().Add(-time.Hour).Unix()}
	if r, _ := clientProbe(t, srv, key, stale); r != nil {
		t.Fatal("stale probe must be ignored")
	}
}

func TestDiagUnknownKeyStaysSilent(t *testing.T) {
	srv := setupDiagServer(t)
	wrong, _ := deriveWrapKey("not-on-server")
	if r, _ := clientProbe(t, srv, wrong, diagRequest{Nonce: nonce(50), Time: time.Now().Unix()}); r != nil {
		t.Fatal("server must not answer a key it does not know")
	}
	report := serverDiag.Report()
	if !strings.Contains(report, "Не расшифровано") || !strings.Contains(report, "127.0.0.1") {
		t.Fatalf("auth failure must be in the journal:\n%s", report)
	}
}

func TestQwdttLinkCarriesPortAndServerKey(t *testing.T) {
	setupDiagServer(t)
	serverDiag.setPort("dtls", "0.0.0.0:56010")
	link := buildQwdttLink("Имя & co", "203.0.113.5", "hash1,hash2", "p@ss word")
	u, err := neturl.Parse(link)
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	if u.Scheme != "qwdtt" || u.Host != "config" {
		t.Fatalf("link %s", link)
	}
	if q.Get("peer") != "203.0.113.5:56010" || q.Get("dk") != diagVectorToken ||
		q.Get("pass") != "p@ss word" || q.Get("name") != "Имя & co" || q.Get("raw") != "56003" {
		t.Fatalf("link %s → %v", link, q)
	}
	var conf qwdttConfFile
	if err := json.Unmarshal(buildQwdttConf(`My "srv"`, "203.0.113.5", "h", "p"), &conf); err != nil {
		t.Fatal(err)
	}
	if conf.Peer != "203.0.113.5:56010" || conf.DiagKey != diagVectorToken || conf.Name != `My "srv"` {
		t.Fatalf("conf %+v", conf)
	}
}
