package main

import (
	"context"
	"crypto/tls"
	"net"
	"net/http"
	neturl "net/url"
	"strings"
	"time"

	"golang.org/x/net/http2"
)

func goDNSIsDoH(arg string) bool {
	arg = strings.ToLower(strings.TrimSpace(arg))
	return strings.HasPrefix(arg, "doh:") || strings.HasPrefix(arg, "doh-")
}

func goDoHEndpointsForPreset(preset string) []string {
	switch strings.ToLower(strings.TrimSpace(preset)) {
	case "doh-cloudflare":
		return []string{
			"https://1.1.1.1/dns-query",
			"https://cloudflare-dns.com/dns-query",
		}
	case "doh-google":
		return []string{
			"https://8.8.8.8/dns-query",
			"https://dns.google/dns-query",
		}
	default:
		return []string{
			"https://77.88.8.8/dns-query",
			"https://common.dot.dns.yandex.net/dns-query",
		}
	}
}

func goDoHEndpointsForArg(arg string) []string {
	arg = strings.TrimSpace(arg)
	if strings.HasPrefix(arg, "doh:") {
		raw := strings.TrimPrefix(arg, "doh:")
		var out []string
		for _, part := range strings.Split(raw, ",") {
			part = strings.TrimSpace(part)
			if part == "" {
				continue
			}
			if ep, ok := normalizeDoHEndpoint(part); ok {
				out = append(out, ep)
			}
		}
		if len(out) > 0 {
			return out
		}
		return goDoHEndpointsForPreset("doh-yandex")
	}
	return goDoHEndpointsForPreset(arg)
}

func normalizeDoHEndpoint(raw string) (string, bool) {
	raw = strings.TrimSpace(raw)
	if raw == "" {
		return "", false
	}
	if !strings.HasPrefix(strings.ToLower(raw), "https://") {
		return "", false
	}
	u, err := neturl.Parse(raw)
	if err != nil || u.Scheme != "https" || u.Host == "" {
		return "", false
	}
	path := strings.TrimSpace(u.Path)
	if path == "" || path == "/" {
		path = "/dns-query"
	}
	u.Path = path
	u.RawQuery = ""
	u.Fragment = ""
	return u.String(), true
}

func dohHostHeader(endpoint string) string {
	u, err := neturl.Parse(endpoint)
	if err != nil {
		return ""
	}
	host := u.Hostname()
	if net.ParseIP(host) == nil {
		return ""
	}
	switch host {
	case "1.1.1.1", "1.0.0.1":
		return "cloudflare-dns.com"
	case "8.8.8.8", "8.8.4.4":
		return "dns.google"
	case "77.88.8.8", "77.88.8.1":
		return "common.dot.dns.yandex.net"
	default:
		return ""
	}
}

func formatDoHEndpoints(endpoints []string) string {
	seen := make(map[string]struct{})
	parts := make([]string, 0, len(endpoints))
	for _, ep := range endpoints {
		label := dohEndpointLabel(ep)
		if label == "" {
			continue
		}
		if _, ok := seen[label]; ok {
			continue
		}
		seen[label] = struct{}{}
		parts = append(parts, label)
	}
	return strings.Join(parts, ", ")
}

func dohEndpointLabel(endpoint string) string {
	if host := dohHostHeader(endpoint); host != "" {
		return host
	}
	u, err := neturl.Parse(endpoint)
	if err != nil {
		return endpoint
	}
	host := u.Hostname()
	if path := strings.Trim(u.Path, "/"); path != "" && path != "dns-query" {
		return host + "/" + path
	}
	return host
}

func newDoHBootstrapDialer() *net.Dialer {
	return &net.Dialer{
		Timeout:   5 * time.Second,
		KeepAlive: 30 * time.Second,
		Resolver: &net.Resolver{
			PreferGo: false, // системный DNS, не DoH — иначе рекурсия
		},
	}
}

func newDoHHTTPClient() *http.Client {
	bootstrap := newDoHBootstrapDialer()
	transport := &http2.Transport{
		DialTLSContext: func(ctx context.Context, network, addr string, cfg *tls.Config) (net.Conn, error) {
			host, _, err := net.SplitHostPort(addr)
			if err != nil {
				return nil, err
			}
			serverName := host
			if sni := dohHostHeader("https://" + host + "/"); sni != "" {
				serverName = sni
			}
			if cfg == nil {
				cfg = &tls.Config{}
			} else {
				cfg = cfg.Clone()
			}
			cfg.ServerName = serverName
			conn, err := bootstrap.DialContext(ctx, network, addr)
			if err != nil {
				return nil, err
			}
			tlsConn := tls.Client(conn, cfg)
			if err := tlsConn.HandshakeContext(ctx); err != nil {
				_ = conn.Close()
				return nil, err
			}
			return tlsConn, nil
		},
	}

	return &http.Client{
		Timeout:   10 * time.Second,
		Transport: transport,
	}
}

func stripDNSWireFrame(payload []byte) []byte {
	if len(payload) < 4 {
		return payload
	}
	ln := int(payload[0])<<8 | int(payload[1])
	if ln > 0 && ln+2 == len(payload) {
		return payload[2:]
	}
	return payload
}

func frameDNSForTCP(payload []byte) []byte {
	out := make([]byte, 2+len(payload))
	out[0] = byte(len(payload) >> 8)
	out[1] = byte(len(payload))
	copy(out[2:], payload)
	return out
}
