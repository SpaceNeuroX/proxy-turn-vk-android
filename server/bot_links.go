package main

import (
	"encoding/json"
	"net"
	neturl "net/url"
	"strconv"
)

// Ссылки qwdtt:// и .conf для клиентов.
//
// peer — IP:порт DTLS-листенера, на котором сервер реально слушает (раньше
// порт не передавался, и при нестандартном -listen клиент стучался на 56000).
// Клиенты, которые порт в peer уже понимали, берут его как есть; новые поля
// (dk, raw, direct) старые версии приложения просто игнорируют.

func serverPeerAddress(srvIP string) string {
	port := serverDiag.Port("dtls")
	if port <= 0 {
		port = 56000
	}
	return net.JoinHostPort(srvIP, strconv.Itoa(port))
}

func buildQwdttLink(name, srvIP, hashes, password string) string {
	q := neturl.Values{}
	q.Set("name", name)
	q.Set("peer", serverPeerAddress(srvIP))
	q.Set("hashes", hashes)
	q.Set("workers", "9")
	q.Set("port", "9000")
	q.Set("pass", password)
	if token := serverDiag.Token(); token != "" {
		q.Set("dk", token)
	}
	if p := serverDiag.Port("raw"); p > 0 {
		q.Set("raw", strconv.Itoa(p))
	}
	if p := serverDiag.Port("direct"); p > 0 {
		q.Set("direct", strconv.Itoa(p))
	}
	// Порядок полей как в старых ссылках — ссылку удобнее читать глазами.
	order := []string{"name", "peer", "hashes", "workers", "port", "pass", "dk", "raw", "direct"}
	out := "qwdtt://config?"
	first := true
	for _, k := range order {
		v := q.Get(k)
		if v == "" && k != "hashes" {
			continue
		}
		if !first {
			out += "&"
		}
		first = false
		out += k + "=" + neturl.QueryEscape(v)
	}
	return out
}

type qwdttConfFile struct {
	Name           string `json:"name"`
	Peer           string `json:"peer"`
	VkHashes       string `json:"vkHashes"`
	WorkersPerHash int    `json:"workersPerHash"`
	ListenPort     int    `json:"listenPort"`
	Password       string `json:"password"`
	DiagKey        string `json:"diagKey,omitempty"`
	RawPort        int    `json:"rawPort,omitempty"`
	DirectPort     int    `json:"directPort,omitempty"`
}

func buildQwdttConf(name, srvIP, hashes, password string) []byte {
	data, _ := json.MarshalIndent(qwdttConfFile{
		Name:           name,
		Peer:           serverPeerAddress(srvIP),
		VkHashes:       hashes,
		WorkersPerHash: 9,
		ListenPort:     9000,
		Password:       password,
		DiagKey:        serverDiag.Token(),
		RawPort:        serverDiag.Port("raw"),
		DirectPort:     serverDiag.Port("direct"),
	}, "", "  ")
	return data
}
