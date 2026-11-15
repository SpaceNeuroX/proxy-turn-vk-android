package main

// Диагностика подключения через VK (серверная сторона — server/netdiag.go).
//
// Когда туннель не поднимается, раньше пользователь видел только «DTLS
// timeout» через 50 секунд — за этим прятались неверный пароль, закрытый
// порт, мёртвый relay VK, проблемы с крупными пакетами. Здесь клиент сам, за
// несколько секунд и только через VK (напрямую до сервера в белых списках не
// достучаться), выясняет, что именно сломалось:
//
//  1. TURN: получаем relay VK (при неудаче пробуем другой транспорт UDP/TCP).
//  2. Проба на порт режима. Проба — обычный RTP-obfs кадр, зашифрованный
//     ключом пароля и (если в ссылке есть dk=) ключом диагностики сервера.
//     Сервер отвечает статусом пароля: ok / не найден / истёк / отключён /
//     лимит устройств.
//  3. Крупные пакеты (~1100 байт) вверх и вниз — DTLS-рукопожатие идёт ими.
//  4. Если ответа нет: пробы на другие порты сервера, STUN-запрос через тот
//     же relay к публичным STUN-серверам (выпускает ли VK трафик вообще) и
//     проба через другой TURN-сервер VK.
//
// Результат — строки "DIAG|{json}" в stdout (их разбирает приложение) и
// понятный текст в лог.

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/transport/v4/stdnet"
	"github.com/pion/turn/v5"
	"golang.org/x/crypto/hkdf"
)

const (
	diagRequestMagic = "QWDTT-DIAG/1\n"
	diagReplyMagic   = "QWDTT-DIAG-OK/1\n"

	// diagLargeSize — размер «крупной» пробы: столько же, сколько DTLS
	// пишет в одну запись (MTU 1100 у клиента и у сервера).
	diagLargeSize = 1100
	// diagProbeWindow — сколько ждём ответа на пробу (с повторами).
	diagProbeWindow = 3500 * time.Millisecond
)

var diagRetransmits = []time.Duration{0, 700 * time.Millisecond, 1700 * time.Millisecond}

// ─── Протокол (должен совпадать с server/netdiag.go) ───

type diagRequest struct {
	Nonce     string `json:"n"`
	Time      int64  `json:"t"`
	PassID    string `json:"p,omitempty"`
	Device    string `json:"d,omitempty"`
	ReplySize int    `json:"rs,omitempty"`
	Pad       string `json:"pad,omitempty"`
}

type diagReply struct {
	Nonce    string         `json:"n"`
	Status   string         `json:"st"`
	Listener string         `json:"l"`
	Proto    int            `json:"v"`
	Ports    map[string]int `json:"ports,omitempty"`
	Pad      string         `json:"pad,omitempty"`
}

const (
	diagStatusOK          = "ok"
	diagStatusUnknown     = "unknown"
	diagStatusExpired     = "expired"
	diagStatusDeactivated = "deactivated"
	diagStatusDeviceLimit = "device_limit"
)

// wrapKeyID — как на сервере: по нему сервер находит пароль, не получая его.
func wrapKeyID(password string) string {
	sum := sha256.Sum256([]byte("WDTT-WRAP-ID-v1\x00" + password))
	return hex.EncodeToString(sum[:8])
}

// parseDiagToken разбирает dk= из ссылки (base64url, 32 байта).
func parseDiagToken(token string) ([]byte, error) {
	token = strings.TrimRight(strings.TrimSpace(token), "=")
	if token == "" {
		return nil, nil
	}
	secret, err := base64.RawURLEncoding.DecodeString(token)
	if err != nil {
		return nil, fmt.Errorf("ключ диагностики: %w", err)
	}
	if len(secret) != 32 {
		return nil, fmt.Errorf("ключ диагностики: длина %d вместо 32", len(secret))
	}
	return secret, nil
}

// deriveDiagKey — AEAD-ключ проб из секрета сервера. Совпадает с сервером.
func deriveDiagKey(secret []byte) ([]byte, error) {
	if len(secret) != 32 {
		return nil, errors.New("diag secret must be 32 bytes")
	}
	key := make([]byte, wrapKeyLen)
	reader := hkdf.New(sha256.New, secret, []byte("QWDTT-DIAG-v1"), []byte("rtp-obfs/chacha20poly1305"))
	if _, err := io.ReadFull(reader, key); err != nil {
		return nil, err
	}
	return key, nil
}

func newDiagNonce() string {
	var b [16]byte
	_, _ = rand.Read(b[:])
	return hex.EncodeToString(b[:])
}

func buildDiagRequest(nonce, passID, device string, size, replySize int) []byte {
	req := diagRequest{Nonce: nonce, Time: time.Now().Unix(), PassID: passID, Device: device, ReplySize: replySize}
	base, _ := json.Marshal(req)
	out := append([]byte(diagRequestMagic), base...)
	if need := size - len(out) - 9; need > 0 { // ,"pad":""
		req.Pad = strings.Repeat("0", need)
		base, _ = json.Marshal(req)
		out = append([]byte(diagRequestMagic), base...)
	}
	return out
}

func parseDiagReply(plain []byte) (*diagReply, bool) {
	if len(plain) <= len(diagReplyMagic) || string(plain[:len(diagReplyMagic)]) != diagReplyMagic {
		return nil, false
	}
	var r diagReply
	if err := json.Unmarshal(plain[len(diagReplyMagic):], &r); err != nil || r.Nonce == "" {
		return nil, false
	}
	return &r, true
}

// ─── STUN Binding (контроль: выпускает ли relay VK трафик наружу) ───

const stunMagicCookie = 0x2112A442

func buildSTUNBinding() ([]byte, [12]byte) {
	var txID [12]byte
	_, _ = rand.Read(txID[:])
	msg := make([]byte, 20)
	binary.BigEndian.PutUint16(msg[0:2], 0x0001) // Binding Request
	binary.BigEndian.PutUint16(msg[2:4], 0)
	binary.BigEndian.PutUint32(msg[4:8], stunMagicCookie)
	copy(msg[8:20], txID[:])
	return msg, txID
}

// stunResponseTxID — transaction ID, если это ответ STUN Binding.
func stunResponseTxID(b []byte) ([12]byte, bool) {
	var id [12]byte
	if len(b) < 20 || b[0]&0xC0 != 0 {
		return id, false
	}
	typ := binary.BigEndian.Uint16(b[0:2])
	if typ != 0x0101 && typ != 0x0111 { // success / error response
		return id, false
	}
	if binary.BigEndian.Uint32(b[4:8]) != stunMagicCookie {
		return id, false
	}
	copy(id[:], b[8:20])
	return id, true
}

// Публичные STUN-серверы для контроля. Хватит ответа любого.
var diagSTUNHosts = []string{
	"stun.l.google.com:19302",
	"stun.cloudflare.com:3478",
	"stun1.l.google.com:19302",
}

// ─── Вывод для приложения ───

type diagStepState string

const (
	diagRun  diagStepState = "run"
	diagOK   diagStepState = "ok"
	diagFail diagStepState = "fail"
	diagWarn diagStepState = "warn"
	diagSkip diagStepState = "skip"
)

type diagEvent struct {
	Ev      string            `json:"ev"`
	Run     int64             `json:"run"`
	Trigger string            `json:"trigger,omitempty"`
	Mode    string            `json:"mode,omitempty"`
	Step    string            `json:"step,omitempty"`
	Title   string            `json:"title,omitempty"`
	State   diagStepState     `json:"state,omitempty"`
	Text    string            `json:"text,omitempty"`
	Code    string            `json:"code,omitempty"`
	Sev     string            `json:"sev,omitempty"`
	Actions []string          `json:"actions,omitempty"`
	Facts   map[string]string `json:"facts,omitempty"`
}

var diagOutMu sync.Mutex

func emitDiag(e diagEvent) {
	b, err := json.Marshal(e)
	if err != nil {
		return
	}
	diagOutMu.Lock()
	fmt.Printf("DIAG|%s\n", b)
	diagOutMu.Unlock()
}

// Названия шагов (одни и те же в приложении и в логе).
var diagStepTitles = map[string]string{
	"turn":     "TURN-сервер VK",
	"server":   "Ответ сервера через VK",
	"password": "Пароль и устройство",
	"mtu":      "Крупные пакеты",
	"ports":    "Другие порты сервера",
	"relay":    "Выход VK в интернет",
	"altrelay": "Другой TURN-сервер VK",
	"legacy":   "Обычное подключение",
}

type diagReporter struct {
	run int64
}

func (r *diagReporter) step(id string, st diagStepState, text string) {
	title := diagStepTitles[id]
	emitDiag(diagEvent{Ev: "step", Run: r.run, Step: id, Title: title, State: st, Text: text})
	if st == diagRun {
		return
	}
	mark := map[diagStepState]string{diagOK: "✓", diagFail: "✗", diagWarn: "!", diagSkip: "–"}[st]
	log.Printf("[ДИАГ] %s %s: %s", mark, title, text)
}

// ─── Конфигурация и результат ───

type diagListener struct {
	Name string // dtls / direct / raw
	Port int
}

func diagListenerTitle(name string) string {
	switch name {
	case "dtls":
		return "WG (DTLS)"
	case "direct":
		return "Direct"
	case "raw":
		return "Raw"
	}
	return name
}

// parseDiagPorts разбирает -diag-ports "dtls=56000,direct=56002,raw=56003".
func parseDiagPorts(s string) map[string]int {
	out := map[string]int{}
	for _, part := range strings.Split(s, ",") {
		k, v, ok := strings.Cut(strings.TrimSpace(part), "=")
		if !ok {
			continue
		}
		k = strings.ToLower(strings.TrimSpace(k))
		p, err := strconv.Atoi(strings.TrimSpace(v))
		if err != nil || p <= 0 || p > 65535 {
			continue
		}
		switch k {
		case "dtls", "direct", "raw":
			out[k] = p
		}
	}
	return out
}

type diagConfig struct {
	TP       *TurnParams
	Peer     *net.UDPAddr
	Listener string // dtls / direct / raw — режим клиента
	Ports    map[string]int
	Password string
	DiagKey  []byte // nil — в ссылке нет ключа сервера
	DeviceID string
	// Creds — откуда брать TURN-креды; fresh — отдельный поток кредов,
	// если на основных TURN-квота уже выбрана воркерами.
	Creds func(ctx context.Context, fresh bool) (*Credentials, error)
	// TunnelReceiving — приходили ли по туннелю данные от сервера за
	// последние секунды (nil — неизвестно, например в режиме -diag).
	TunnelReceiving func() bool
}

func diagTargetListener(tp *TurnParams) string {
	switch {
	case tp.RawMode:
		return "raw"
	case tp.NoDTLS:
		return "direct"
	default:
		return "dtls"
	}
}

type diagKeyRef struct {
	name string // pass / diag
	key  []byte
}

type diagProbeResult struct {
	ok    bool
	rtt   time.Duration
	reply *diagReply
	key   string
}

// ─── Relay для проб ───

type diagRelay struct {
	target   turnTarget
	closers  []io.Closer
	relay    net.PacketConn
	keys     []diagKeyRef
	obfsMode string

	mu      sync.Mutex
	waiters map[string]chan diagProbeResult // nonce → ответ
	stun    map[[12]byte]chan struct{}
	sentAt  map[string]time.Time
	keyOf   map[string]string
}

func (r *diagRelay) Close() {
	for i := len(r.closers) - 1; i >= 0; i-- {
		_ = r.closers[i].Close()
	}
}

func (cfg *diagConfig) openRelay(ctx context.Context, creds *Credentials, idx int, transport string, keys []diagKeyRef) (*diagRelay, error) {
	tp := *cfg.TP
	tp.Transport = transport
	target, err := selectTurnTarget(&tp, creds.TurnURLs, idx)
	if err != nil {
		return nil, err
	}
	dctx, cancel := context.WithTimeout(ctx, 8*time.Second)
	defer cancel()
	conn, closer, err := dialTURNConn(dctx, target)
	if err != nil {
		return nil, err
	}
	r := &diagRelay{
		target:   target,
		closers:  []io.Closer{closer},
		keys:     keys,
		obfsMode: cfg.TP.ObfsMode,
		waiters:  map[string]chan diagProbeResult{},
		stun:     map[[12]byte]chan struct{}{},
		sentAt:   map[string]time.Time{},
		keyOf:    map[string]string{},
	}
	family := turn.RequestedAddressFamilyIPv4
	if cfg.Peer.IP.To4() == nil {
		family = turn.RequestedAddressFamilyIPv6
	}
	tc, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr:         target.Addr,
		TURNServerAddr:         target.Addr,
		Conn:                   conn,
		Net:                    new(stdnet.Net),
		Username:               creds.User,
		Password:               creds.Pass,
		RequestedAddressFamily: family,
		LoggerFactory:          &NullLoggerFactory{},
	})
	if err != nil {
		r.Close()
		return nil, err
	}
	r.closers = append(r.closers, closerFunc(func() error { tc.Close(); return nil }))
	if err := tc.Listen(); err != nil {
		r.Close()
		return nil, err
	}

	type allocResult struct {
		pc  net.PacketConn
		err error
	}
	ch := make(chan allocResult, 1)
	go func() {
		pc, err := tc.Allocate()
		ch <- allocResult{pc, err}
	}()
	select {
	case res := <-ch:
		if res.err != nil {
			r.Close()
			return nil, res.err
		}
		r.relay = res.pc
	case <-time.After(10 * time.Second):
		r.Close()
		return nil, errors.New("TURN Allocate: таймаут")
	case <-ctx.Done():
		r.Close()
		return nil, ctx.Err()
	}
	r.closers = append(r.closers, r.relay)
	go r.readLoop()
	return r, nil
}

type closerFunc func() error

func (f closerFunc) Close() error { return f() }

func (r *diagRelay) readLoop() {
	buf := make([]byte, 2048)
	plain := make([]byte, 2048)
	for {
		n, _, err := r.relay.ReadFrom(buf)
		if err != nil {
			return
		}
		pkt := buf[:n]
		if id, ok := stunResponseTxID(pkt); ok {
			r.mu.Lock()
			if ch := r.stun[id]; ch != nil {
				delete(r.stun, id)
				close(ch)
			}
			r.mu.Unlock()
			continue
		}
		if !obfsIsRTPPacket(pkt) {
			continue
		}
		for _, k := range r.keys {
			m, err := obfsUnwrapPacket(k.key, pkt, plain)
			if err != nil {
				continue
			}
			reply, ok := parseDiagReply(plain[:m])
			if !ok {
				break
			}
			r.mu.Lock()
			ch := r.waiters[reply.Nonce]
			sent := r.sentAt[reply.Nonce]
			keyName := r.keyOf[reply.Nonce]
			delete(r.waiters, reply.Nonce)
			r.mu.Unlock()
			if ch != nil {
				ch <- diagProbeResult{ok: true, rtt: time.Since(sent), reply: reply, key: keyName}
			}
			break
		}
	}
}

// probe шлёт одну пробу (с повторами) и ждёт ответ.
func (r *diagRelay) probe(ctx context.Context, dst *net.UDPAddr, k diagKeyRef, passID, device string, size, replySize int) diagProbeResult {
	nonce := newDiagNonce()
	payload := buildDiagRequest(nonce, passID, device, size, replySize)
	ch := make(chan diagProbeResult, 1)
	r.mu.Lock()
	r.waiters[nonce] = ch
	r.sentAt[nonce] = time.Now()
	r.keyOf[nonce] = k.name
	r.mu.Unlock()
	defer func() {
		r.mu.Lock()
		delete(r.waiters, nonce)
		delete(r.sentAt, nonce)
		delete(r.keyOf, nonce)
		r.mu.Unlock()
	}()

	cfg, err := NewObfsConfig(r.obfsMode)
	if err != nil {
		return diagProbeResult{}
	}
	state, err := NewObfsState()
	if err != nil {
		return diagProbeResult{}
	}
	deadline := time.NewTimer(diagProbeWindow)
	defer deadline.Stop()
	start := time.Now()
	for i := 0; ; i++ {
		if i < len(diagRetransmits) {
			wait := time.Until(start.Add(diagRetransmits[i]))
			if wait > 0 {
				select {
				case res := <-ch:
					return res
				case <-time.After(wait):
				case <-deadline.C:
					return diagProbeResult{}
				case <-ctx.Done():
					return diagProbeResult{}
				}
			}
			// Каждый повтор — новый кадр (свой seq/ts), иначе сервер
			// отбросит его как повтор.
			if wrapped, err := obfsWrapPacket(k.key, payload, cfg, state); err == nil {
				_, _ = r.relay.WriteTo(wrapped, dst)
			}
			continue
		}
		select {
		case res := <-ch:
			return res
		case <-deadline.C:
			return diagProbeResult{}
		case <-ctx.Done():
			return diagProbeResult{}
		}
	}
}

// diagSameDstGap — пауза между пробами на один и тот же адрес. Сервер
// закрывает соединение relay→порт сразу после ответа на пробу; пакет,
// пришедший в этот момент, теряется и спасается только повтором (~0.7 с).
const diagSameDstGap = 220 * time.Millisecond

// diagOldServerTitle — итог для сервера, который не умеет отвечать на пробы.
const diagOldServerTitle = "Старая версия сервера — диагностика недоступна"

// legacyCheckAddr — куда пробовать обычное подключение: порт WG сервера
// (в режиме WG это и есть адрес профиля, в Raw/Direct — из -diag-ports).
func (cfg *diagConfig) legacyCheckAddr() *net.UDPAddr {
	if cfg.Listener == "dtls" {
		return cfg.Peer
	}
	if port := cfg.Ports["dtls"]; port > 0 {
		return &net.UDPAddr{IP: cfg.Peer.IP, Port: port, Zone: cfg.Peer.Zone}
	}
	return nil
}

// diagLegacyTimeout — сколько ждать обычного подключения к серверу, который
// не отвечает на пробы (см. diagnose).
const diagLegacyTimeout = 20 * time.Second

// probeAnyKey шлёт пробу всеми ключами (с небольшим разносом) и возвращает
// первый ответ; остальные пробы после ответа не отправляются.
func (r *diagRelay) probeAnyKey(ctx context.Context, dst *net.UDPAddr, passID, device string, size, replySize int) diagProbeResult {
	results := make(chan diagProbeResult, len(r.keys))
	pctx, cancel := context.WithCancel(ctx)
	defer cancel()
	for i, k := range r.keys {
		go func(i int, k diagKeyRef) {
			if i > 0 {
				select {
				case <-time.After(time.Duration(i) * diagSameDstGap):
				case <-pctx.Done():
					results <- diagProbeResult{}
					return
				}
			}
			results <- r.probe(pctx, dst, k, passID, device, size, replySize)
		}(i, k)
	}
	var best diagProbeResult
	for range r.keys {
		res := <-results
		if res.ok {
			// Ответ ключом пароля доказывает больше (пароль верный) —
			// но статус в обоих ответах одинаковый, берём первый.
			cancel()
			return res
		}
		best = res
	}
	return best
}

// stunCheck: отвечает ли хоть один публичный STUN-сервер через relay.
func (r *diagRelay) stunCheck(ctx context.Context, family4 bool) (bool, string) {
	var addrs []*net.UDPAddr
	rctx, cancel := context.WithTimeout(ctx, 3*time.Second)
	defer cancel()
	for _, h := range diagSTUNHosts {
		host, port, err := net.SplitHostPort(h)
		if err != nil {
			continue
		}
		ips, err := net.DefaultResolver.LookupIPAddr(rctx, host)
		if err != nil {
			continue
		}
		for _, ip := range ips {
			if (ip.IP.To4() != nil) != family4 {
				continue
			}
			p, _ := strconv.Atoi(port)
			addrs = append(addrs, &net.UDPAddr{IP: ip.IP, Port: p})
			break
		}
	}
	if len(addrs) == 0 {
		return false, "не удалось найти адреса публичных STUN-серверов (DNS)"
	}

	done := make(chan string, len(addrs))
	for _, a := range addrs {
		msg, id := buildSTUNBinding()
		ch := make(chan struct{})
		r.mu.Lock()
		r.stun[id] = ch
		r.mu.Unlock()
		go func(a *net.UDPAddr, msg []byte, ch chan struct{}) {
			for i := 0; i < 3; i++ {
				_, _ = r.relay.WriteTo(msg, a)
				select {
				case <-ch:
					done <- a.String()
					return
				case <-time.After(800 * time.Millisecond):
				case <-ctx.Done():
					return
				}
			}
		}(a, msg, ch)
	}
	select {
	case who := <-done:
		return true, who
	case <-time.After(3 * time.Second):
		return false, ""
	case <-ctx.Done():
		return false, ""
	}
}

// ─── Сам прогон ───

type diagVerdict struct {
	Code    string
	Sev     string // ok / warn / error
	Title   string
	Text    string
	Actions []string
	Facts   map[string]string
}

var diagRunSeq atomic.Int64

// runDiagnostics — один прогон. Безопасно вызывать параллельно с воркерами:
// у проб свои TURN-аллокации.
func runDiagnostics(ctx context.Context, cfg *diagConfig, trigger string) diagVerdict {
	run := time.Now().UnixMilli()*10 + diagRunSeq.Add(1)%10
	rep := &diagReporter{run: run}
	emitDiag(diagEvent{Ev: "start", Run: run, Trigger: trigger, Mode: cfg.Listener})
	log.Printf("[ДИАГ] ═══ Проверка соединения (%s) ═══", map[string]string{"auto": "автоматически", "manual": "по запросу"}[trigger])

	v := cfg.diagnose(ctx, rep)
	if trigger == "auto" && v.Code == "path_ok" {
		// Проверка запустилась потому, что туннель не поднялся, а путь при
		// этом цел — значит, сбой был временным или внутри рукопожатия.
		v.Sev = "warn"
		v.Title = "Сервер доступен, туннель не поднялся"
		v.Text = "VK пропускает трафик, сервер отвечает, пароль действует. Скорее всего, сбой временный — переподключитесь. Если повторяется, попробуйте режим Raw."
	}
	if v.Facts == nil {
		v.Facts = map[string]string{}
	}
	emitDiag(diagEvent{Ev: "verdict", Run: run, Code: v.Code, Sev: v.Sev, Title: v.Title, Text: v.Text, Actions: v.Actions, Facts: v.Facts})
	log.Printf("[ДИАГ] Итог: %s. %s", v.Title, v.Text)
	emitDiag(diagEvent{Ev: "end", Run: run})
	return v
}

func (cfg *diagConfig) keys() ([]diagKeyRef, error) {
	passKey, err := deriveWrapKey(cfg.Password)
	if err != nil {
		return nil, err
	}
	keys := []diagKeyRef{{name: "pass", key: passKey}}
	if len(cfg.DiagKey) == wrapKeyLen {
		keys = append(keys, diagKeyRef{name: "diag", key: cfg.DiagKey})
	}
	return keys, nil
}

func otherTransport(t string) string {
	if t == turnTransportTCP {
		return turnTransportUDP
	}
	return turnTransportTCP
}

func isQuotaErr(err error) bool {
	if err == nil {
		return false
	}
	s := err.Error()
	return strings.Contains(s, "Quota") || strings.Contains(s, "486")
}

func shortErr(err error) string {
	if err == nil {
		return ""
	}
	s := err.Error()
	if len(s) > 140 {
		s = s[:140] + "…"
	}
	return s
}

func (cfg *diagConfig) diagnose(ctx context.Context, rep *diagReporter) diagVerdict {
	facts := map[string]string{
		"mode":   cfg.Listener,
		"server": cfg.Peer.String(),
		"dk":     strconv.FormatBool(len(cfg.DiagKey) == wrapKeyLen),
	}
	keys, err := cfg.keys()
	if err != nil {
		return diagVerdict{Code: "config", Sev: "error", Title: "Не задан пароль подключения",
			Text: "Без пароля проверить сервер нельзя. Укажите пароль в «Секретах».", Facts: facts}
	}

	// 1. TURN ──────────────────────────────────────────────────────────
	rep.step("turn", diagRun, "Получаем relay…")
	creds, err := cfg.Creds(ctx, false)
	if err != nil {
		if callErr, ok := asCallUnavailableError(err); ok {
			rep.step("turn", diagFail, fmt.Sprintf("VK: ссылка на звонок недействительна (код %d)", callErr.Code))
			return diagVerdict{Code: "vk_call_invalid", Sev: "error", Title: "Ссылка на звонок VK не работает",
				Text:  "VK отвечает, что звонка нет или ссылка недействительна: звонок завершён или хеш устарел. Обновите VK-хеши или возьмите новую ссылку у администратора.",
				Facts: withFact(facts, "error", shortErr(err))}
		}
		rep.step("turn", diagFail, "VK не выдал доступ к TURN: "+shortErr(err))
		return diagVerdict{Code: "vk_creds", Sev: "error", Title: "VK не выдал доступ к TURN",
			Text:  "Не удалось получить TURN-креды по ссылке на звонок. Проверьте VK-хеши (вкладка «Хеши») или решите капчу и подключитесь снова.",
			Facts: withFact(facts, "error", shortErr(err))}
	}

	transport := normalizeTurnTransport(cfg.TP.Transport)
	t0 := time.Now()
	relay, err := cfg.openRelay(ctx, creds, 0, transport, keys)
	for attempt := 0; err != nil && isQuotaErr(err) && attempt < 2; attempt++ {
		// Квоту на этих кредах могли выбрать воркеры — берём свежие.
		select {
		case <-ctx.Done():
			return diagVerdict{Code: "cancelled", Sev: "warn", Title: "Проверка прервана", Facts: facts}
		case <-time.After(1500 * time.Millisecond):
		}
		if fresh, ferr := cfg.Creds(ctx, true); ferr == nil {
			creds = fresh
		}
		relay, err = cfg.openRelay(ctx, creds, 0, transport, keys)
	}
	if err != nil {
		firstErr := err
		alt := otherTransport(transport)
		rep.step("turn", diagRun, fmt.Sprintf("%s не прошёл, пробуем %s…", strings.ToUpper(transport), strings.ToUpper(alt)))
		altRelay, altErr := cfg.openRelay(ctx, creds, 0, alt, keys)
		if altErr == nil {
			altRelay.Close()
			rep.step("turn", diagWarn, fmt.Sprintf("%s до VK не проходит, %s работает", strings.ToUpper(transport), strings.ToUpper(alt)))
			return diagVerdict{Code: "turn_transport", Sev: "warn",
				Title:   fmt.Sprintf("%s до VK не проходит", strings.ToUpper(transport)),
				Text:    fmt.Sprintf("Сеть не пропускает %s до TURN-серверов VK, а %s проходит. Переключите транспорт TURN на %s.", strings.ToUpper(transport), strings.ToUpper(alt), strings.ToUpper(alt)),
				Actions: []string{"switch_" + alt},
				Facts:   withFact(facts, "error", shortErr(firstErr))}
		}
		rep.step("turn", diagFail, "relay не выдан: "+shortErr(firstErr))
		if isQuotaErr(firstErr) {
			return diagVerdict{Code: "turn_quota", Sev: "warn", Title: "Квота TURN VK исчерпана",
				Text:  "VK временно не выдаёт новые relay на эти креды. Уменьшите число потоков или подождите пару минут.",
				Facts: withFact(facts, "error", shortErr(firstErr))}
		}
		return diagVerdict{Code: "turn_unreachable", Sev: "error", Title: "TURN-серверы VK недоступны",
			Text:  "Ни по UDP, ни по TCP не удалось получить relay VK. Проверьте интернет или смените сеть (Wi-Fi ↔ мобильная).",
			Facts: withFact(facts, "error", shortErr(firstErr))}
	}
	defer relay.Close()
	facts["turn"] = relay.target.Addr
	facts["transport"] = relay.target.Transport
	rep.step("turn", diagOK, fmt.Sprintf("relay %s (%s), %d мс", relay.target.Addr, strings.ToUpper(relay.target.Transport), time.Since(t0).Milliseconds()))

	// 2. Проба на порт режима + параллельно другие порты и STUN ─────────
	passID := wrapKeyID(cfg.Password)
	targetName := diagListenerTitle(cfg.Listener)
	rep.step("server", diagRun, fmt.Sprintf("Проба на %s, порт %d…", targetName, cfg.Peer.Port))

	type portResult struct {
		l   diagListener
		res diagProbeResult
	}
	var others []diagListener
	for name, port := range cfg.Ports {
		if port != cfg.Peer.Port {
			others = append(others, diagListener{Name: name, Port: port})
		}
	}
	sort.Slice(others, func(i, j int) bool { return others[i].Port < others[j].Port })

	mainCh := make(chan diagProbeResult, 1)
	go func() {
		mainCh <- relay.probeAnyKey(ctx, cfg.Peer, passID, cfg.DeviceID, 0, 0)
	}()
	otherCh := make(chan portResult, len(others))
	for _, l := range others {
		go func(l diagListener) {
			dst := &net.UDPAddr{IP: cfg.Peer.IP, Port: l.Port, Zone: cfg.Peer.Zone}
			otherCh <- portResult{l: l, res: relay.probeAnyKey(ctx, dst, passID, cfg.DeviceID, 0, 0)}
		}(l)
	}
	main := <-mainCh

	if main.ok {
		rep.step("server", diagOK, fmt.Sprintf("%s, порт %d ответил за %d мс", targetName, cfg.Peer.Port, main.rtt.Milliseconds()))
		facts["rtt_ms"] = strconv.FormatInt(main.rtt.Milliseconds(), 10)
		facts["reply_key"] = main.key

		// 3. Пароль
		if v, bad := passwordVerdict(main.reply.Status, facts); bad {
			rep.step("password", diagFail, v.Title)
			return v
		}
		passText := "пароль действует"
		if main.key == "pass" {
			passText = "пароль верный, сервер его принял"
		}
		rep.step("password", diagOK, passText)

		// 4. Крупные пакеты
		rep.step("mtu", diagRun, "Проверяем пакеты по 1100 байт…")
		var up, down diagProbeResult
		var wg sync.WaitGroup
		wg.Add(2)
		go func() {
			defer wg.Done()
			up = relay.probeAnyKey(ctx, cfg.Peer, passID, cfg.DeviceID, diagLargeSize, 0)
		}()
		go func() {
			defer wg.Done()
			// Разносим с пробой вверх: тот же адрес (см. diagSameDstGap).
			select {
			case <-time.After(time.Duration(len(keys)) * diagSameDstGap):
			case <-ctx.Done():
				return
			}
			down = relay.probeAnyKey(ctx, cfg.Peer, passID, cfg.DeviceID, 0, diagLargeSize)
		}()
		wg.Wait()
		switch {
		case up.ok && down.ok:
			rep.step("mtu", diagOK, "1100 байт проходят в обе стороны")
		default:
			dir := "в обе стороны"
			if up.ok {
				dir = "от сервера к вам"
			} else if down.ok {
				dir = "от вас к серверу"
			}
			rep.step("mtu", diagFail, "крупные пакеты не проходят "+dir)
			actions := []string{}
			if cfg.Listener == "dtls" && cfg.rawMatches(main.reply) {
				actions = append(actions, "switch_raw")
			}
			return diagVerdict{Code: "mtu", Sev: "error", Title: "Крупные пакеты не проходят",
				Text:    "Мелкие пакеты до сервера доходят, а пакеты около 1100 байт теряются " + dir + ". Из-за этого не завершается рукопожатие. Попробуйте режим Raw или другую сеть.",
				Actions: actions, Facts: withFact(facts, "mtu_dir", dir)}
		}

		// Всё прошло.
		actions := []string{"reconnect"}
		if cfg.Listener == "dtls" && cfg.rawMatches(main.reply) {
			actions = append(actions, "switch_raw")
		}
		return diagVerdict{Code: "path_ok", Sev: "ok", Title: "Путь до сервера в порядке",
			Text:    "VK пропускает трафик, сервер отвечает, пароль действует, крупные пакеты проходят.",
			Actions: actions, Facts: facts}
	}

	rep.step("server", diagFail, fmt.Sprintf("%s, порт %d не ответил", targetName, cfg.Peer.Port))

	// Ответы с других портов.
	var alive []portResult
	for range others {
		pr := <-otherCh
		if pr.res.ok {
			alive = append(alive, pr)
		}
	}
	if len(others) > 0 {
		if len(alive) > 0 {
			var names []string
			for _, a := range alive {
				names = append(names, fmt.Sprintf("%s %d", diagListenerTitle(a.l.Name), a.l.Port))
			}
			rep.step("ports", diagOK, "отвечает: "+strings.Join(names, ", "))
		} else {
			var names []string
			for _, l := range others {
				names = append(names, fmt.Sprintf("%s %d", diagListenerTitle(l.Name), l.Port))
			}
			rep.step("ports", diagFail, "молчат: "+strings.Join(names, ", "))
		}
	}

	// Никто не ответил на пробы, а данные от сервера по туннелю при этом
	// идут — значит, сервер доступен, просто старый и проб не понимает.
	// Именно данные, а не число воркеров: воркер на мёртвом relay числится
	// живым, пока не упадёт.
	if len(alive) == 0 && cfg.TunnelReceiving != nil && cfg.TunnelReceiving() {
		rep.step("server", diagSkip, "сервер не отвечает на пробы, но данные по туннелю идут")
		return diagVerdict{Code: "server_old", Sev: "warn", Title: diagOldServerTitle,
			Text:  "Туннель работает, но на сервере старая версия wdtt-server: она не отвечает на диагностические пробы. Обновите сервер, чтобы проверка могла называть причины сбоев.",
			Facts: facts}
	}

	// Сервер версии до 1.4.5 на пробы молчит, но подключиться к нему можно.
	// Пробуем обычное DTLS-рукопожатие на порт WG (он есть у любого сервера),
	// как «пинг» профиля, в любом режиме: удалось — сервер рабочий, просто
	// старый; пароль при этом верный (иначе сервер не принял бы пакеты).
	if legacyAddr := cfg.legacyCheckAddr(); len(alive) == 0 && legacyAddr != nil {
		rep.step("legacy", diagRun, fmt.Sprintf("Пробуем обычное подключение (WG, порт %d)…", legacyAddr.Port))
		lctx, lcancel := context.WithTimeout(ctx, diagLegacyTimeout)
		ltp := *cfg.TP
		ltp.NoDTLS, ltp.RawMode = false, false
		rtt, lerr := RunPing(lctx, &ltp, legacyAddr, creds)
		lcancel()
		if lerr == nil {
			rep.step("server", diagSkip, "на пробы не отвечает — старая версия сервера")
			if len(others) > 0 {
				rep.step("ports", diagSkip, "старый сервер — проверить нельзя")
			}
			rep.step("legacy", diagOK, fmt.Sprintf("подключение по WG работает, %d мс", rtt))
			text := fmt.Sprintf("Сервер работает: подключение по WG проходит за %d мс, пароль верный. "+
				"Но на нём старая версия wdtt-server — она не отвечает на диагностические пробы, поэтому подробная проверка недоступна. "+
				"Обновите сервер, чтобы проверка могла называть причины сбоев.", rtt)
			if cfg.Listener != "dtls" {
				text += fmt.Sprintf(" Если режим %s не подключается, переключитесь на WG.", diagListenerTitle(cfg.Listener))
			}
			return diagVerdict{Code: "server_old", Sev: "warn", Title: diagOldServerTitle,
				Text:  text,
				Facts: withFact(withFact(facts, "rtt_ms", strconv.FormatInt(rtt, 10)), "legacy", "true")}
		}
		if ctx.Err() != nil {
			return diagVerdict{Code: "cancelled", Sev: "warn", Title: "Проверка прервана", Facts: facts}
		}
		rep.step("legacy", diagFail, "не подключилось: "+shortErr(lerr))
	}

	if len(alive) > 0 {
		a := alive[0]
		if v, bad := passwordVerdict(a.res.reply.Status, facts); bad {
			rep.step("password", diagFail, v.Title)
			return v
		}
		rep.step("password", diagOK, "пароль действует")
		actions := []string{}
		switch {
		case a.l.Name == "raw" && cfg.Listener != "raw":
			actions = append(actions, "switch_raw")
		case a.l.Name == "dtls" && cfg.Listener != "dtls":
			actions = append(actions, "switch_wg")
		}
		return diagVerdict{Code: "port_closed", Sev: "error",
			Title:   fmt.Sprintf("Порт %d не отвечает", cfg.Peer.Port),
			Text:    fmt.Sprintf("Сервер работает и отвечает на %s (порт %d), но порт %s %d молчит: он закрыт файрволом, не включён на сервере или в настройках указан неверный порт.", diagListenerTitle(a.l.Name), a.l.Port, targetName, cfg.Peer.Port),
			Actions: actions, Facts: withFact(facts, "alive_port", fmt.Sprintf("%s=%d", a.l.Name, a.l.Port))}
	}

	// Никто не ответил. Выпускает ли VK трафик вообще?
	rep.step("relay", diagRun, "STUN-запрос через relay к публичным серверам…")
	stunOK, stunWho := relay.stunCheck(ctx, cfg.Peer.IP.To4() != nil)
	if stunOK {
		rep.step("relay", diagOK, "relay VK выпускает трафик (ответил "+stunWho+")")
	} else if stunWho != "" {
		rep.step("relay", diagSkip, stunWho)
	} else {
		rep.step("relay", diagFail, "публичные STUN-серверы через relay не отвечают")
	}

	// Другой TURN-сервер VK.
	altIdx := -1
	hosts := creds.TurnURLs
	if len(cfg.TP.Hosts) > 0 {
		hosts = cfg.TP.Hosts
	}
	for i := 1; i < len(hosts); i++ {
		if t, err := selectTurnTarget(cfg.TP, creds.TurnURLs, i); err == nil {
			if h1, _, _ := net.SplitHostPort(t.Addr); h1 != "" {
				if h0, _, _ := net.SplitHostPort(relay.target.Addr); h0 != h1 {
					altIdx = i
					break
				}
			}
		}
	}
	if altIdx > 0 {
		rep.step("altrelay", diagRun, "Пробуем через другой TURN-сервер…")
		alt, err := cfg.openRelay(ctx, creds, altIdx, relay.target.Transport, keys)
		if err == nil {
			res := alt.probeAnyKey(ctx, cfg.Peer, passID, cfg.DeviceID, 0, 0)
			altAddr := alt.target.Addr
			alt.Close()
			if res.ok {
				rep.step("altrelay", diagOK, fmt.Sprintf("через %s сервер ответил", altAddr))
				if v, bad := passwordVerdict(res.reply.Status, facts); bad {
					return v
				}
				altHost, _, _ := net.SplitHostPort(altAddr)
				badHost, _, _ := net.SplitHostPort(relay.target.Addr)
				return diagVerdict{Code: "bad_relay", Sev: "warn", Title: "Сбоит отдельный TURN-сервер VK",
					Text:    fmt.Sprintf("Через %s сервер не отвечает, а через %s отвечает. Обычно это временно: переподключитесь. Если повторяется — закрепите рабочий TURN-адрес.", badHost, altHost),
					Actions: []string{"reconnect", "use_turn:" + altHost},
					Facts:   withFact(facts, "good_turn", altHost)}
			}
			rep.step("altrelay", diagFail, fmt.Sprintf("через %s тоже нет ответа", altAddr))
		} else {
			rep.step("altrelay", diagSkip, "второй relay не выдан: "+shortErr(err))
		}
	}

	if !stunOK && stunWho == "" {
		return diagVerdict{Code: "relay_blocked", Sev: "error", Title: "VK не выпускает трафик",
			Text:  "Relay VK выдан, но не пропускает трафик даже к публичным серверам. Это ограничение VK или сети, а не вашего сервера. Попробуйте позже, другую сеть или TCP.",
			Facts: facts, Actions: []string{"switch_" + otherTransport(relay.target.Transport)}}
	}

	if len(cfg.DiagKey) == wrapKeyLen {
		return diagVerdict{Code: "server_unreachable", Sev: "error", Title: "Сервер не отвечает",
			Text:  fmt.Sprintf("VK пропускает трафик, но сервер %s молчит на всех портах. Сервер выключен, порт закрыт файрволом или VK не пропускает трафик к этому IP. Пароль тут ни при чём — с ключом сервера он ответил бы и на неверный.", cfg.Peer.IP),
			Facts: facts}
	}
	return diagVerdict{Code: "no_reply", Sev: "error", Title: "Сервер не отвечает",
		Text:  fmt.Sprintf("VK пропускает трафик, но сервер %s не ответил. Возможные причины: неверный пароль, сервер выключен или устарел, порт закрыт, VK блокирует этот IP. Попросите у администратора новую ссылку — с ней причину можно назвать точно.", cfg.Peer.IP),
		Facts: facts}
}

// rawMatches: сервер слушает Raw на том же порту, что настроен у клиента —
// только тогда «Перейти на Raw» приведёт куда надо.
func (cfg *diagConfig) rawMatches(r *diagReply) bool {
	if r == nil {
		return false
	}
	p := r.Ports["raw"]
	return p > 0 && p == cfg.Ports["raw"]
}

func withFact(f map[string]string, k, v string) map[string]string {
	f[k] = v
	return f
}

// passwordVerdict — вердикт по статусу пароля из ответа сервера.
func passwordVerdict(status string, facts map[string]string) (diagVerdict, bool) {
	switch status {
	case diagStatusOK, "", "no_password":
		return diagVerdict{}, false
	case diagStatusUnknown:
		return diagVerdict{Code: "password_unknown", Sev: "error", Title: "Пароль не найден на сервере",
			Text: "Сервер доступен, но такого пароля на нём нет: опечатка, пароль удалён или ссылка от другого сервера. Попросите у администратора новую ссылку.", Facts: facts}, true
	case diagStatusExpired:
		return diagVerdict{Code: "password_expired", Sev: "error", Title: "Срок действия пароля истёк",
			Text: "Сервер доступен, но срок действия пароля закончился. Продлите доступ у администратора.", Facts: facts}, true
	case diagStatusDeactivated:
		return diagVerdict{Code: "password_deactivated", Sev: "error", Title: "Пароль отключён",
			Text: "Сервер доступен, но администратор отключил этот пароль.", Facts: facts}, true
	case diagStatusDeviceLimit:
		return diagVerdict{Code: "device_limit", Sev: "error", Title: "Достигнут лимит устройств",
			Text: "Пароль действует, но к нему уже привязано максимальное число устройств. Отвяжите старое устройство или попросите увеличить лимит.", Facts: facts}, true
	}
	return diagVerdict{}, false
}

// ─── Автозапуск ───

// diagCredsReadyAt — когда первая группа получила креды (unix ms). Таймер
// автопроверки отсчитывается от него: пока решается капча, проверять нечего.
var diagCredsReadyAt atomic.Int64

func markDiagCredsReady() {
	diagCredsReadyAt.CompareAndSwap(0, time.Now().UnixMilli())
}

// diagCredsFailedAt — когда группа впервые не получила креды (unix ms).
// Если к этому моменту креды не получил никто, туннель не поднимется вовсе —
// проверку запускаем сразу, не дожидаясь таймаута шага в приложении.
var diagCredsFailedAt atomic.Int64

func markDiagCredsFailed() {
	diagCredsFailedAt.CompareAndSwap(0, time.Now().UnixMilli())
}

// diagLastDownlink — когда по туннелю последний раз пришли данные от
// сервера (unix ms). Обновляет diagWatchdog.
var diagLastDownlink atomic.Int64

const diagDownlinkFresh = 20 * time.Second

func tunnelReceivingRecently() bool {
	last := diagLastDownlink.Load()
	return last > 0 && time.Since(time.UnixMilli(last)) < diagDownlinkFresh
}

const (
	diagAutoAfterCreds = 15 * time.Second // без единого живого воркера после кредов
	// Пауза после неудачи кредов: дать соседним группам шанс их получить.
	diagAutoAfterCredsFail = 3 * time.Second
	diagAutoAfterDrop      = 25 * time.Second // все воркеры упали после работы
	diagAutoCooldown       = 3 * time.Minute
)

// diagWatchdog запускает проверку сам, когда туннель не поднимается, и по
// запросу приложения (файл-триггер).
// autoDiagAtStartup — пора ли запускать проверку, пока туннель ни разу не
// поднимался: креды есть, а потоков нет 15 с; или креды не получил никто.
func autoDiagAtStartup(now time.Time, readyMs, failedMs int64) bool {
	if readyMs > 0 {
		return now.Sub(time.UnixMilli(readyMs)) >= diagAutoAfterCreds
	}
	return failedMs > 0 && now.Sub(time.UnixMilli(failedMs)) >= diagAutoAfterCredsFail
}

func diagWatchdog(ctx context.Context, cfg *diagConfig, stats *Stats, pauseFlag *int32, auto bool, triggerFile string) {
	t := time.NewTicker(time.Second)
	defer t.Stop()

	var (
		everUp      bool
		downSince   time.Time
		lastAuto    time.Time
		startupDone bool
		running     atomic.Bool
		lastDown    int64
	)
	launch := func(trigger string) {
		if !running.CompareAndSwap(false, true) {
			return
		}
		go func() {
			defer running.Store(false)
			runDiagnostics(ctx, cfg, trigger)
		}()
	}

	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}

		if down := stats.TotalBytesDown.Load(); down != lastDown {
			lastDown = down
			diagLastDownlink.Store(time.Now().UnixMilli())
		}

		if triggerFile != "" {
			if _, err := os.Stat(triggerFile); err == nil {
				_ = os.Remove(triggerFile)
				launch("manual")
				continue
			}
		}
		if !auto || atomic.LoadInt32(pauseFlag) != 0 {
			downSince = time.Time{}
			continue
		}

		active := stats.ActiveConnections.Load()
		now := time.Now()
		if active > 0 {
			everUp = true
			startupDone = true
			downSince = time.Time{}
			continue
		}
		if !everUp {
			if !startupDone && autoDiagAtStartup(now, diagCredsReadyAt.Load(), diagCredsFailedAt.Load()) {
				startupDone = true
				lastAuto = now
				launch("auto")
			}
			continue
		}
		if downSince.IsZero() {
			downSince = now
			continue
		}
		if now.Sub(downSince) >= diagAutoAfterDrop && now.Sub(lastAuto) >= diagAutoCooldown {
			lastAuto = now
			launch("auto")
		}
	}
}
