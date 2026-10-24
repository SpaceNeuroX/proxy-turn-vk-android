package main

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"strings"
	"sync"
	"time"

	utls "github.com/bogdanfinn/utls"
	"github.com/pion/turn/v5"
)

// Транспорт до TURN-relay VK. Сама TURN/RTP-obfs логика от него не зависит,
// меняется только то, как телефон доходит до сервера VK:
//   - udp: как раньше;
//   - tcp: STUN/ChannelData поверх обычного TCP (turn.NewSTUNConn);
//   - tls: то же поверх TLS к turns:-адресу VK. Снаружи это обычный TLS к
//     серверу VK с его доменом в SNI — помогает там, где душат UDP и сам
//     протокол TURN.
const (
	turnTransportUDP = "udp"
	turnTransportTCP = "tcp"
	turnTransportTLS = "tls"

	turnTLSPrefix    = "turns:"
	defaultTURNPort  = "3478"
	defaultTURNSPort = "5349"
	turnDialTimeout  = 10 * time.Second
)

func normalizeTurnTransport(s string) string {
	switch strings.ToLower(strings.TrimSpace(s)) {
	case turnTransportTCP:
		return turnTransportTCP
	case turnTransportTLS, "turns":
		return turnTransportTLS
	default:
		return turnTransportUDP
	}
}

// turnURLsToAddresses приводит turn_server.urls из ответа VK к виду host:port.
// Для turns:-адресов префикс сохраняется ("turns:host:port") — по нему
// selectTurnTarget отличает TLS-эндпоинты от обычных.
func turnURLsToAddresses(urls []string) []string {
	var addresses []string
	for _, urlStr := range urls {
		urlStr = strings.TrimSpace(urlStr)
		if urlStr == "" {
			continue
		}
		clean := strings.Split(urlStr, "?")[0]
		switch {
		case strings.HasPrefix(clean, turnTLSPrefix):
			if rest := strings.TrimPrefix(clean, turnTLSPrefix); rest != "" {
				addresses = append(addresses, turnTLSPrefix+rest)
			}
		default:
			if rest := strings.TrimPrefix(clean, "turn:"); rest != "" {
				addresses = append(addresses, rest)
			}
		}
	}
	return addresses
}

// parseTurnEntry разбирает элемент Credentials.TurnURLs.
func parseTurnEntry(entry string) (host, port string, isTLS bool, err error) {
	entry = strings.TrimSpace(entry)
	if strings.HasPrefix(entry, turnTLSPrefix) {
		isTLS = true
		entry = strings.TrimPrefix(entry, turnTLSPrefix)
	}
	host, port, err = splitHostPortDefault(entry, defaultPortFor(isTLS))
	if err != nil {
		return "", "", false, fmt.Errorf("разбор TURN URL %q: %w", entry, err)
	}
	return host, port, isTLS, nil
}

func defaultPortFor(isTLS bool) string {
	if isTLS {
		return defaultTURNSPort
	}
	return defaultTURNPort
}

// splitHostPortDefault — net.SplitHostPort, но допускает адрес без порта.
func splitHostPortDefault(s, defPort string) (string, string, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return "", "", fmt.Errorf("пустой адрес")
	}
	if host, port, err := net.SplitHostPort(s); err == nil {
		if host == "" {
			return "", "", fmt.Errorf("пустой хост в %q", s)
		}
		return host, port, nil
	}
	host := strings.TrimSuffix(strings.TrimPrefix(s, "["), "]")
	if strings.ContainsAny(host, "[]") {
		return "", "", fmt.Errorf("некорректный адрес %q", s)
	}
	return host, defPort, nil
}

// parseTurnHosts разбирает список своих TURN-адресов из -turn
// ("1.2.3.4, 5.6.7.8:3478, [2001:db8::1]:443").
func parseTurnHosts(s string) []string {
	var hosts []string
	for _, part := range strings.FieldsFunc(s, func(r rune) bool {
		return r == ',' || r == ';' || r == ' ' || r == '\n' || r == '\t'
	}) {
		if part = strings.TrimSpace(part); part != "" {
			hosts = append(hosts, part)
		}
	}
	return hosts
}

// turnTarget — куда и как подключаться к TURN для одной сессии.
type turnTarget struct {
	Addr      string // host:port для dial и для turn.ClientConfig
	SNI       string // только для tls: домен VK из turns:-адреса
	Transport string
}

var tlsFallbackOnce sync.Once

// selectTurnTarget выбирает TURN-адрес для сессии idx.
//
// Из адресов VK берутся только подходящие транспорту: для tls — turns:,
// для udp/tcp — обычные. Если VK не прислал ни одного turns:-адреса, TLS
// невозможен — сессия идёт по TCP (с одним предупреждением в логе).
//
// Свои адреса (tp.Hosts) заменяют хост VK по кругу между сессиями; порт из
// своего адреса важнее -port, а -port важнее порта VK. SNI при этом остаётся
// доменом VK, поэтому TLS работает и при подстановке IP.
func selectTurnTarget(tp *TurnParams, entries []string, idx int) (turnTarget, error) {
	if len(entries) == 0 {
		return turnTarget{}, fmt.Errorf("нет TURN URL в учетных данных")
	}
	if idx < 0 {
		idx = -idx
	}
	transport := normalizeTurnTransport(tp.Transport)

	var matching []string
	for _, e := range entries {
		if strings.HasPrefix(strings.TrimSpace(e), turnTLSPrefix) == (transport == turnTransportTLS) {
			matching = append(matching, e)
		}
	}
	if len(matching) == 0 {
		if transport == turnTransportTLS {
			tlsFallbackOnce.Do(func() {
				log.Printf("[TURN] VK не прислал turns:-адресов — TLS недоступен, использую TCP")
			})
			transport = turnTransportTCP
			for _, e := range entries {
				if !strings.HasPrefix(strings.TrimSpace(e), turnTLSPrefix) {
					matching = append(matching, e)
				}
			}
		}
		if len(matching) == 0 {
			matching = entries
		}
	}

	host, port, isTLS, err := parseTurnEntry(matching[idx%len(matching)])
	if err != nil {
		return turnTarget{}, err
	}
	sni := ""
	if isTLS {
		sni = host
	} else if transport == turnTransportTLS {
		transport = turnTransportTCP
	}

	if tp.Port != "" {
		port = tp.Port
	}
	if len(tp.Hosts) > 0 {
		h, p, err := splitHostPortDefault(tp.Hosts[idx%len(tp.Hosts)], "")
		if err != nil {
			return turnTarget{}, fmt.Errorf("свой TURN-адрес: %w", err)
		}
		host = h
		if p != "" {
			port = p
		}
	}

	return turnTarget{
		Addr:      net.JoinHostPort(host, port),
		SNI:       sni,
		Transport: transport,
	}, nil
}

// connectedUDPConn — обёртка для connected UDP socket → PacketConn
type connectedUDPConn struct{ *net.UDPConn }

func (c *connectedUDPConn) WriteTo(p []byte, _ net.Addr) (int, error) { return c.Write(p) }

// dialTURNConn открывает сокет до TURN-сервера и оборачивает его в
// net.PacketConn, которого ждёт turn.ClientConfig.Conn.
//
// TCP и TLS используют штатный turn.NewSTUNConn из pion/turn (см.
// examples/turn-client/tcp): он сам разбирает STUN/ChannelData framing поверх
// потока. TCP нужен на сетях (замечено на Ростелекоме), где UDP до TURN-relay
// душится, а TCP проходит — сравни github.com/anton48/vk-turn-proxy-ios,
// который к той же VK/OK TURN-инфре по умолчанию ходит через TCP.
func dialTURNConn(ctx context.Context, t turnTarget) (net.PacketConn, io.Closer, error) {
	switch t.Transport {
	case turnTransportTCP:
		d := net.Dialer{Timeout: turnDialTimeout}
		c, err := d.DialContext(ctx, "tcp", t.Addr)
		if err != nil {
			return nil, nil, fmt.Errorf("подключение TURN TCP: %w", err)
		}
		return turn.NewSTUNConn(c), c, nil
	case turnTransportTLS:
		c, err := dialTURNTLS(ctx, t)
		if err != nil {
			return nil, nil, err
		}
		return turn.NewSTUNConn(c), c, nil
	default:
		resolved, err := net.ResolveUDPAddr("udp", t.Addr)
		if err != nil {
			return nil, nil, fmt.Errorf("резолв TURN: %w", err)
		}
		c, err := net.DialUDP("udp", nil, resolved)
		if err != nil {
			return nil, nil, fmt.Errorf("подключение TURN UDP: %w", err)
		}
		_ = c.SetReadBuffer(socketBufSize)
		_ = c.SetWriteBuffer(socketBufSize)
		return &connectedUDPConn{c}, c, nil
	}
}

// dialTURNTLS поднимает TLS до turns:-адреса с ClientHello как у Chrome
// (uTLS), чтобы по отпечатку не отличаться от браузера.
//
// Сертификат проверяется по домену VK из SNI. Если SNI нет или это IP —
// проверить нечего, и TLS служит только маскировкой: данные туннеля и так
// зашифрованы и аутентифицированы WRAP-AEAD до самого сервера.
func dialTURNTLS(ctx context.Context, t turnTarget) (net.Conn, error) {
	d := net.Dialer{Timeout: turnDialTimeout}
	raw, err := d.DialContext(ctx, "tcp", t.Addr)
	if err != nil {
		return nil, fmt.Errorf("подключение TURN TLS: %w", err)
	}
	cfg := &utls.Config{ServerName: t.SNI}
	if t.SNI == "" || net.ParseIP(t.SNI) != nil {
		cfg.ServerName = ""
		cfg.InsecureSkipVerify = true
	}
	conn := utls.UClient(raw, cfg, utls.HelloChrome_Auto, false, true, true)
	hctx, cancel := context.WithTimeout(ctx, turnDialTimeout)
	defer cancel()
	if err := conn.HandshakeContext(hctx); err != nil {
		raw.Close()
		return nil, fmt.Errorf("TLS-хендшейк с TURN %s (SNI %q): %w", t.Addr, t.SNI, err)
	}
	return conn, nil
}
