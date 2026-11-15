package main

// DNS для VK (login.vk.ru, api.vk.me и т.п.) с запасным вариантом.
//
// Раньше «второй сервер» пресета фактически не использовался: UDP-«connect»
// всегда успешен, и недоступный DNS выяснялся только по таймауту запроса,
// после чего переключения не было. DoH перебирал свои адреса, но дальше не
// шёл. Если выбранный DNS был заблокирован, подключиться было нельзя.
//
// Теперь все запросы идут через цепочку: сначала выбранные серверы (UDP или
// DoH), затем DNS текущей сети — мобильного оператора или роутера Wi-Fi
// (приложение передаёт их флагом -dns-fallback). Не ответивший сервер
// пропускается 30 с, чтобы каждый запрос не ждал его таймаут заново.
// Пресет «operator» — наоборот: сначала DNS сети, запасной — Яндекс.

import (
	"bytes"
	"context"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"strings"
	"sync"
	"time"
)

const (
	dnsUDPTimeout   = 1500 * time.Millisecond
	dnsTCPTimeout   = 2500 * time.Millisecond
	dnsDoHTimeout   = 4 * time.Second
	dnsFailCooldown = 30 * time.Second
)

type dnsUpstream struct {
	kind    string // "udp" или "doh"
	addr    string // host:port для udp, URL для doh
	network bool   // DNS текущей сети (оператор / роутер Wi-Fi)
	backup  bool   // запасной: используется, только если основные не ответили
}

func (u dnsUpstream) String() string {
	if u.kind == "doh" {
		return dohEndpointLabel(u.addr)
	}
	return u.addr
}

type dnsChain struct {
	label     string
	upstreams []dnsUpstream
	client    *http.Client // для DoH, создаётся лениво

	mu          sync.Mutex
	failedUntil map[int]time.Time
	announced   map[int]bool // уже писали в лог, что перешли на этот сервер

	// exchangeFn подменяется в тестах.
	exchangeFn func(ctx context.Context, u dnsUpstream, query []byte) ([]byte, error)
}

func newDNSChain(label string, upstreams []dnsUpstream) *dnsChain {
	c := &dnsChain{
		label:       label,
		upstreams:   upstreams,
		failedUntil: map[int]time.Time{},
		announced:   map[int]bool{},
	}
	c.exchangeFn = c.exchange
	return c
}

// order — индексы серверов в порядке попыток: живые по приоритету, затем
// «остывающие» (если живых нет — лучше попробовать, чем сразу сдаться).
func (c *dnsChain) order(now time.Time) []int {
	c.mu.Lock()
	defer c.mu.Unlock()
	var alive, cooling []int
	for i := range c.upstreams {
		if now.Before(c.failedUntil[i]) {
			cooling = append(cooling, i)
		} else {
			alive = append(alive, i)
		}
	}
	return append(alive, cooling...)
}

func (c *dnsChain) markFailed(i int, err error) {
	c.mu.Lock()
	first := c.failedUntil[i].IsZero() || time.Now().After(c.failedUntil[i])
	c.failedUntil[i] = time.Now().Add(dnsFailCooldown)
	c.announced[i] = false
	c.mu.Unlock()
	if first {
		log.Printf("[DNS] %s не отвечает (%v) — пробуем следующий", c.upstreams[i], shortDNSErr(err))
	}
}

func (c *dnsChain) markOK(i int) {
	c.mu.Lock()
	delete(c.failedUntil, i)
	u := c.upstreams[i]
	announce := u.backup && !c.announced[i]
	c.announced[i] = true
	c.mu.Unlock()
	if announce {
		via := "Яндекс"
		if u.network {
			via = "DNS сети"
		}
		log.Printf("[DNS] Выбранный DNS не отвечает — запросы идут через %s (%s)", via, u)
	}
}

// resolve отправляет запрос по цепочке и возвращает первый годный ответ.
func (c *dnsChain) resolve(ctx context.Context, query []byte) ([]byte, error) {
	if len(c.upstreams) == 0 {
		return nil, errors.New("dns: нет серверов")
	}
	var lastErr error
	for _, i := range c.order(time.Now()) {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		resp, err := c.exchangeFn(ctx, c.upstreams[i], query)
		if err == nil {
			err = validateDNSReply(query, resp)
		}
		if err != nil {
			lastErr = err
			c.markFailed(i, err)
			continue
		}
		c.markOK(i)
		return resp, nil
	}
	return nil, fmt.Errorf("dns: ни один сервер не ответил: %w", lastErr)
}

// validateDNSReply: ответ на наш запрос, и сервер не отказался его
// обрабатывать (SERVFAIL/REFUSED — повод спросить следующий сервер;
// NXDOMAIN — нормальный ответ).
func validateDNSReply(query, resp []byte) error {
	if len(resp) < 12 || len(query) < 2 {
		return errors.New("короткий ответ")
	}
	if resp[0] != query[0] || resp[1] != query[1] {
		return errors.New("чужой ответ")
	}
	if resp[2]&0x80 == 0 {
		return errors.New("не ответ")
	}
	switch resp[3] & 0x0F {
	case 2:
		return errors.New("SERVFAIL")
	case 5:
		return errors.New("REFUSED")
	}
	return nil
}

func (c *dnsChain) exchange(ctx context.Context, u dnsUpstream, query []byte) ([]byte, error) {
	if u.kind == "doh" {
		c.mu.Lock()
		if c.client == nil {
			c.client = newDoHHTTPClient()
		}
		client := c.client
		c.mu.Unlock()
		return dohExchange(ctx, client, u.addr, query, dnsDoHTimeout)
	}
	resp, err := udpDNSExchange(ctx, u.addr, query)
	if err == nil && len(resp) > 2 && resp[2]&0x02 != 0 {
		// Ответ обрезан (TC) — тот же сервер по TCP.
		return tcpDNSExchange(ctx, u.addr, query)
	}
	return resp, err
}

func udpDNSExchange(ctx context.Context, addr string, query []byte) ([]byte, error) {
	d := net.Dialer{Timeout: dnsUDPTimeout}
	conn, err := d.DialContext(ctx, "udp", addr)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	deadline := time.Now().Add(dnsUDPTimeout)
	if dl, ok := ctx.Deadline(); ok && dl.Before(deadline) {
		deadline = dl
	}
	_ = conn.SetDeadline(deadline)
	if _, err := conn.Write(query); err != nil {
		return nil, err
	}
	buf := make([]byte, 4096)
	for {
		n, err := conn.Read(buf)
		if err != nil {
			return nil, err
		}
		// Пропускаем запоздавшие ответы на чужие запросы.
		if n >= 2 && buf[0] == query[0] && buf[1] == query[1] {
			return append([]byte(nil), buf[:n]...), nil
		}
	}
}

func tcpDNSExchange(ctx context.Context, addr string, query []byte) ([]byte, error) {
	d := net.Dialer{Timeout: dnsTCPTimeout}
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(dnsTCPTimeout))
	if _, err := conn.Write(frameDNSForTCP(query)); err != nil {
		return nil, err
	}
	var lenBuf [2]byte
	if _, err := io.ReadFull(conn, lenBuf[:]); err != nil {
		return nil, err
	}
	resp := make([]byte, binary.BigEndian.Uint16(lenBuf[:]))
	if _, err := io.ReadFull(conn, resp); err != nil {
		return nil, err
	}
	return resp, nil
}

func dohExchange(ctx context.Context, client *http.Client, endpoint string, query []byte, timeout time.Duration) ([]byte, error) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, bytes.NewReader(query))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/dns-message")
	req.Header.Set("Content-Type", "application/dns-message")
	if host := dohHostHeader(endpoint); host != "" {
		req.Host = host
	}
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		return nil, fmt.Errorf("HTTP %d", resp.StatusCode)
	}
	return io.ReadAll(io.LimitReader(resp.Body, 65535))
}

func shortDNSErr(err error) string {
	if err == nil {
		return ""
	}
	var ne net.Error
	if errors.As(err, &ne) && ne.Timeout() {
		return "таймаут"
	}
	s := err.Error()
	if len(s) > 80 {
		s = s[:80] + "…"
	}
	return s
}

// ─── net.Conn для net.Resolver ───

// chainConn — «соединение», которое отдаётся резолверу Go. Любой
// нестандартный net.Conn Go считает TCP-DNS (с 2-байтовой длиной), поэтому
// запрос приходит обрамлённым, а ответ возвращается так же.
type chainConn struct {
	chain *dnsChain
	ctx   context.Context

	mu       sync.Mutex
	response []byte
	readPos  int
	closed   bool
}

func (c *chainConn) Write(b []byte) (int, error) {
	query := stripDNSWireFrame(b)
	resp, err := c.chain.resolve(c.ctx, query)
	if err != nil {
		return 0, err
	}
	c.mu.Lock()
	c.response = frameDNSForTCP(resp)
	c.readPos = 0
	c.mu.Unlock()
	return len(b), nil
}

func (c *chainConn) Read(b []byte) (int, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return 0, net.ErrClosed
	}
	if c.response == nil {
		return 0, errors.New("dns: read before write")
	}
	if c.readPos >= len(c.response) {
		return 0, io.EOF
	}
	n := copy(b, c.response[c.readPos:])
	c.readPos += n
	return n, nil
}

func (c *chainConn) Close() error {
	c.mu.Lock()
	c.closed = true
	c.mu.Unlock()
	return nil
}

func (c *chainConn) LocalAddr() net.Addr              { return &net.TCPAddr{IP: net.IPv4zero} }
func (c *chainConn) RemoteAddr() net.Addr             { return &net.TCPAddr{IP: net.IPv4zero, Port: 53} }
func (c *chainConn) SetDeadline(time.Time) error      { return nil }
func (c *chainConn) SetReadDeadline(time.Time) error  { return nil }
func (c *chainConn) SetWriteDeadline(time.Time) error { return nil }

// ─── Настройка ───

// parseDNSFallback разбирает -dns-fallback: IP через запятую (IPv4/IPv6,
// можно с портом). Возвращает host:port.
func parseDNSFallback(raw string) []string {
	var out []string
	seen := map[string]bool{}
	for _, part := range strings.Split(raw, ",") {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		addr := part
		if ip := net.ParseIP(strings.Trim(part, "[]")); ip != nil {
			if ip.IsLoopback() || ip.IsUnspecified() || ip.IsLinkLocalUnicast() {
				continue
			}
			addr = net.JoinHostPort(ip.String(), "53")
		} else if _, _, err := net.SplitHostPort(part); err != nil {
			continue
		}
		if !seen[addr] {
			seen[addr] = true
			out = append(out, addr)
		}
	}
	return out
}

// buildDNSUpstreams — цепочка для аргумента -go-dns и DNS сети.
func buildDNSUpstreams(arg string, network []string) []dnsUpstream {
	var ups []dnsUpstream
	add := func(kind string, addrs []string, isNetwork, backup bool) {
		for _, a := range addrs {
			ups = append(ups, dnsUpstream{kind: kind, addr: a, network: isNetwork, backup: backup})
		}
	}
	arg = strings.TrimSpace(arg)
	switch {
	case strings.EqualFold(arg, "operator"):
		// DNS сети — основной, Яндекс — запасной. Если сеть не сообщила
		// адреса, Яндекс становится основным.
		add("udp", network, true, false)
		add("udp", goDNSServersForPreset("yandex"), false, len(network) > 0)
		return ups
	case goDNSIsDoH(arg):
		add("doh", goDoHEndpointsForArg(arg), false, false)
	default:
		add("udp", goDNSServersForArg(arg), false, false)
	}
	add("udp", network, true, true)
	return ups
}

func setupGlobalResolver(arg string, networkDNS []string) {
	ups := buildDNSUpstreams(arg, networkDNS)
	label := goDNSLabel(arg)
	var primary, fallback, network []string
	for _, u := range ups {
		if u.backup {
			fallback = append(fallback, u.String())
		} else {
			primary = append(primary, u.String())
		}
		if u.network {
			network = append(network, u.String())
		}
	}
	switch {
	case strings.EqualFold(strings.TrimSpace(arg), "operator"):
		log.Printf("[КЛИЕНТ] DNS для VK: операторский — DNS сети (%s), запасной — Яндекс", orNone(network))
	case len(fallback) > 0:
		log.Printf("[КЛИЕНТ] DNS для VK: %s (%s), запасной — DNS сети (%s)", label, strings.Join(primary, ", "), strings.Join(fallback, ", "))
	default:
		log.Printf("[КЛИЕНТ] DNS для VK: %s (%s)", label, strings.Join(primary, ", "))
	}

	chain := newDNSChain(label, ups)
	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			return &chainConn{chain: chain, ctx: ctx}, nil
		},
	}
}

func orNone(list []string) string {
	if len(list) == 0 {
		return "нет данных о DNS сети"
	}
	return strings.Join(list, ", ")
}
