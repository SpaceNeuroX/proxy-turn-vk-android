package main

import (
	"context"
	"fmt"
	"io"
	"net"
	"strings"
	"time"

	"github.com/pion/turn/v5"
)

// Транспорт до TURN-relay VK. Сама TURN/RTP-obfs логика от него не зависит,
// меняется только то, как телефон доходит до сервера VK:
//   - udp: как раньше;
//   - tcp: STUN/ChannelData поверх обычного TCP (turn.NewSTUNConn).
//
// TURN по TLS проверяли: VK не выдаёт turns:-адресов и не принимает TLS на
// своих TURN-серверах, поэтому такого транспорта нет.
const (
	turnTransportUDP = "udp"
	turnTransportTCP = "tcp"

	defaultTURNPort = "3478"
	turnDialTimeout = 10 * time.Second
)

func normalizeTurnTransport(s string) string {
	if strings.EqualFold(strings.TrimSpace(s), turnTransportTCP) {
		return turnTransportTCP
	}
	return turnTransportUDP
}

// turnURLsToAddresses приводит turn_server.urls из ответа VK к виду host:port.
func turnURLsToAddresses(urls []string) []string {
	var addresses []string
	for _, urlStr := range urls {
		clean := strings.Split(strings.TrimSpace(urlStr), "?")[0]
		address := strings.TrimPrefix(strings.TrimPrefix(clean, "turn:"), "turns:")
		if address != "" {
			addresses = append(addresses, address)
		}
	}
	return addresses
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
	Transport string
}

// selectTurnTarget выбирает TURN-адрес для сессии idx: адреса VK по кругу.
// Свои адреса (tp.Hosts) заменяют хост VK, тоже по кругу между сессиями;
// порт из своего адреса важнее -port, а -port важнее порта VK.
func selectTurnTarget(tp *TurnParams, entries []string, idx int) (turnTarget, error) {
	if len(entries) == 0 {
		return turnTarget{}, fmt.Errorf("нет TURN URL в учетных данных")
	}
	if idx < 0 {
		idx = -idx
	}
	entry := entries[idx%len(entries)]
	host, port, err := splitHostPortDefault(entry, defaultTURNPort)
	if err != nil {
		return turnTarget{}, fmt.Errorf("разбор TURN URL %q: %w", entry, err)
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
		Transport: normalizeTurnTransport(tp.Transport),
	}, nil
}

// connectedUDPConn — обёртка для connected UDP socket → PacketConn
type connectedUDPConn struct{ *net.UDPConn }

func (c *connectedUDPConn) WriteTo(p []byte, _ net.Addr) (int, error) { return c.Write(p) }

// dialTURNConn открывает сокет до TURN-сервера и оборачивает его в
// net.PacketConn, которого ждёт turn.ClientConfig.Conn.
//
// TCP использует штатный turn.NewSTUNConn из pion/turn (см.
// examples/turn-client/tcp): он сам разбирает STUN/ChannelData framing поверх
// потока. Нужен на сетях (замечено на Ростелекоме), где UDP до TURN-relay
// душится, а TCP проходит — сравни github.com/anton48/vk-turn-proxy-ios,
// который к той же VK/OK TURN-инфре по умолчанию ходит через TCP.
func dialTURNConn(ctx context.Context, t turnTarget) (net.PacketConn, io.Closer, error) {
	if t.Transport == turnTransportTCP {
		d := net.Dialer{Timeout: turnDialTimeout}
		c, err := d.DialContext(ctx, "tcp", t.Addr)
		if err != nil {
			return nil, nil, fmt.Errorf("подключение TURN TCP: %w", err)
		}
		return turn.NewSTUNConn(c), c, nil
	}
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
