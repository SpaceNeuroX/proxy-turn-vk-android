package main

import (
	"bytes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/binary"
	"errors"
	"fmt"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	dtlsnet "github.com/pion/dtls/v3/pkg/net"
	pionudp "github.com/pion/transport/v4/udp"
	"golang.org/x/crypto/chacha20poly1305"
)

// ==================== RTP Обфускация ====================

type ObfsConfig struct {
	SSRC        uint32
	PayloadType uint8
	PaddingMax  int
}

var aeadCache sync.Map
var wrapCredentialBindings sync.Map

const replayWindowSpan = uint64(4096 * 961)
const replayWindowMaxEntries = 8192

type replayWindow struct {
	mu          sync.Mutex
	seen        map[[12]byte]uint64
	ssrc        uint32
	highestTime uint64
	initialized bool
}

func (w *replayWindow) accept(wire []byte) bool {
	if len(wire) < rtpHeaderLen {
		return false
	}
	ssrc := binary.BigEndian.Uint32(wire[8:12])
	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	var nonce [12]byte
	copy(nonce[:], obfsBuildNonce(ssrc, seq, ts))
	w.mu.Lock()
	defer w.mu.Unlock()
	if !w.initialized {
		w.ssrc = ssrc
		w.highestTime = uint64(ts)
		w.seen = make(map[[12]byte]uint64, 4096)
		w.initialized = true
	} else if w.ssrc != ssrc {
		return false
	}
	if _, exists := w.seen[nonce]; exists {
		return false
	}
	base := w.highestTime &^ uint64(0xffffffff)
	extended := base | uint64(ts)
	if extended+(1<<31) < w.highestTime {
		extended += 1 << 32
	} else if extended > w.highestTime+(1<<31) && extended >= 1<<32 {
		extended -= 1 << 32
	}
	if extended+replayWindowSpan < w.highestTime {
		return false
	}
	if extended > w.highestTime {
		w.highestTime = extended
	}
	if len(w.seen) >= replayWindowMaxEntries {
		cutoff := w.highestTime - min(w.highestTime, replayWindowSpan)
		for value, packetTime := range w.seen {
			if packetTime < cutoff {
				delete(w.seen, value)
			}
		}
		if len(w.seen) >= replayWindowMaxEntries {
			return false
		}
	}
	w.seen[nonce] = extended
	return true
}

func getAEAD(key []byte) (cipher.AEAD, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes", wrapKeyLen)
	}
	keyStr := string(key)
	if val, ok := aeadCache.Load(keyStr); ok {
		return val.(cipher.AEAD), nil
	}
	aead, err := chacha20poly1305.New(key)
	if err != nil {
		return nil, err
	}
	aeadCache.Store(keyStr, aead)
	return aead, nil
}

func evictAEAD(key []byte) {
	if len(key) == wrapKeyLen {
		aeadCache.Delete(string(key))
	}
}

type ObfsState struct {
	mu      sync.Mutex
	initSeq uint16
	initTs  uint32
	count   uint64
}

func NewObfsConfig(mode string) (*ObfsConfig, error) {
	var buf [4]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return nil, err
	}
	pt := uint8(111)
	pad := 24
	if strings.EqualFold(strings.TrimSpace(mode), "video") {
		pt = 96
		pad = 60
	}
	return &ObfsConfig{
		SSRC:        binary.BigEndian.Uint32(buf[:]),
		PayloadType: pt,
		PaddingMax:  pad,
	}, nil
}

func NewObfsState() (*ObfsState, error) {
	var buf [6]byte
	if _, err := rand.Read(buf[:]); err != nil {
		return nil, err
	}
	return &ObfsState{
		initSeq: binary.BigEndian.Uint16(buf[0:2]),
		initTs:  binary.BigEndian.Uint32(buf[2:6]),
		count:   0,
	}, nil
}

func obfsBuildNonce(ssrc uint32, seq uint16, ts uint32) []byte {
	n := make([]byte, 12)
	binary.BigEndian.PutUint32(n[0:4], ssrc)
	binary.BigEndian.PutUint16(n[4:6], seq)
	binary.BigEndian.PutUint32(n[8:12], ts)
	return n
}

// rtpHeaderLen — bare 12-байтный RTP заголовок, без RFC 8285 extension.
// Должно совпадать байт-в-байт с go_client/obfs.go — отдельный Go-модуль,
// общий код не шарится.
const rtpHeaderLen = 12

// obfsWrapPacket упаковывает payload в RTP-obfs кадр. dst переиспользуется
// как выходной буфер, если его вместимости хватает (передайте nil, чтобы
// всегда аллоцировать новый — используется клиентской стороной go_client,
// отдельным модулем, где эта функция не переиспользуется построчно).
func obfsWrapPacket(key, payload []byte, cfg *ObfsConfig, state *ObfsState, dst []byte) ([]byte, error) {
	if len(key) != wrapKeyLen {
		return nil, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(payload) == 0 {
		return nil, errors.New("obfs: empty payload")
	}
	state.mu.Lock()
	c := state.count
	state.count++
	state.mu.Unlock()

	seq := state.initSeq + uint16(c)
	ts := state.initTs + uint32(c)*960 + uint32(c>>16)

	nonce := obfsBuildNonce(cfg.SSRC, seq, ts)
	padRand := 0
	if cfg.PaddingMax > 0 {
		var rndBuf [1]byte
		if _, err := rand.Read(rndBuf[:]); err != nil {
			return nil, fmt.Errorf("obfs: padding random: %w", err)
		}
		padRand = int(rndBuf[0]) % cfg.PaddingMax
	}
	padTotal := padRand + 1
	outLen := rtpHeaderLen + len(payload) + chacha20poly1305.Overhead + padTotal
	out := dst
	if cap(out) < outLen {
		out = make([]byte, outLen)
	} else {
		out = out[:outLen]
	}

	out[0] = 0x80 | 0x20 // V=2, X=0 (no extension), P=1 (padding present)
	out[1] = cfg.PayloadType & 0x7F
	binary.BigEndian.PutUint16(out[2:4], seq)
	binary.BigEndian.PutUint32(out[4:8], ts)
	binary.BigEndian.PutUint32(out[8:12], cfg.SSRC)

	aead, err := getAEAD(key)
	if err != nil {
		return nil, fmt.Errorf("obfs: cipher init: %w", err)
	}
	sealed := aead.Seal(out[rtpHeaderLen:rtpHeaderLen], nonce, payload, out[:rtpHeaderLen])
	padStart := rtpHeaderLen + len(sealed)
	if padRand > 0 {
		if _, err := rand.Read(out[padStart : padStart+padRand]); err != nil {
			return nil, fmt.Errorf("obfs: padding bytes: %w", err)
		}
	}
	out[outLen-1] = byte(padTotal)
	return out, nil
}

func obfsUnwrapPacket(key, wire, dst []byte) (int, error) {
	if len(key) != wrapKeyLen {
		return 0, fmt.Errorf("obfs: key must be %d bytes (got %d)", wrapKeyLen, len(key))
	}
	if len(wire) < rtpHeaderLen+1 {
		return 0, errors.New("obfs: packet too short")
	}
	if (wire[0] >> 6) != 2 {
		return 0, errors.New("obfs: not RTP v2")
	}
	seq := binary.BigEndian.Uint16(wire[2:4])
	ts := binary.BigEndian.Uint32(wire[4:8])
	ssrc := binary.BigEndian.Uint32(wire[8:12])

	payloadEnd := len(wire)
	if wire[0]&0x20 != 0 {
		padLen := int(wire[len(wire)-1])
		if padLen == 0 || padLen > payloadEnd-rtpHeaderLen {
			return 0, fmt.Errorf("obfs: invalid padding length %d", padLen)
		}
		payloadEnd -= padLen
	}
	ciphertextLen := payloadEnd - rtpHeaderLen
	if ciphertextLen <= chacha20poly1305.Overhead {
		return 0, errors.New("obfs: no payload")
	}
	if ciphertextLen-chacha20poly1305.Overhead > len(dst) {
		return 0, errors.New("obfs: dst buffer too small")
	}
	nonce := obfsBuildNonce(ssrc, seq, ts)
	aead, err := getAEAD(key)
	if err != nil {
		return 0, fmt.Errorf("obfs: cipher init: %w", err)
	}
	plain, err := aead.Open(dst[:0], nonce, wire[rtpHeaderLen:payloadEnd], wire[:rtpHeaderLen])
	if err != nil {
		return 0, fmt.Errorf("obfs: auth: %w", err)
	}
	return len(plain), nil
}
func obfsIsRTPPacket(wire []byte) bool {
	if len(wire) < rtpHeaderLen+1 {
		return false
	}
	if (wire[0] >> 6) != 2 {
		return false
	}
	pt := wire[1] & 0x7F
	return pt == 111 || pt == 96
}

// socketBufSize — размер SO_RCVBUF/SO_SNDBUF на общем UDP-сокете листенера.
// Без явной установки сокет сидит на дефолте ядра (обычно ~212KB), хотя
// net.core.rmem_max/wmem_max уже подняты sysctl'ом (см. enableBBR). При
// сотнях одновременных клиентов на одном сокете дефолтный буфер — источник
// молчаливых ENOBUFS-дропов под всплеском нагрузки.
const socketBufSize = 8 * 1024 * 1024

// listenWrapped поднимает UDP-листенер с RTP-obfs. name — "dtls", "direct"
// или "raw": попадает в ответы на диагностические пробы и в журнал.
func listenWrapped(name string, addr *net.UDPAddr, keys *wrapKeyStore) (dtlsnet.PacketListener, error) {
	if keys == nil || keys.Count() == 0 {
		return nil, errors.New("wrap: no active keys")
	}
	lc := pionudp.ListenConfig{ReadBufferSize: socketBufSize, WriteBufferSize: socketBufSize}
	inner, err := lc.Listen("udp", addr)
	if err != nil {
		return nil, fmt.Errorf("wrap: udp listen: %w", err)
	}
	return &wrapPacketListener{
		inner: dtlsnet.PacketListenerFromListener(inner),
		keys:  keys,
		name:  name,
	}, nil
}

type wrapPacketListener struct {
	inner dtlsnet.PacketListener
	keys  *wrapKeyStore
	name  string
}

func (l *wrapPacketListener) Accept() (net.PacketConn, net.Addr, error) {
	pc, addr, err := l.inner.Accept()
	if err != nil {
		return pc, addr, err
	}
	return &wrapPacketConn{inner: pc, keys: l.keys, listener: l.name}, addr, nil
}

func (l *wrapPacketListener) Close() error   { return l.inner.Close() }
func (l *wrapPacketListener) Addr() net.Addr { return l.inner.Addr() }

type wrapPacketConn struct {
	inner     net.PacketConn
	keys      *wrapKeyStore
	listener  string // dtls / direct / raw — см. listenWrapped
	key       []byte
	keyID     string
	bindingID string
	selected  int32
	authLog   int32
	obfsCfg   *ObfsConfig
	obfsWrite *ObfsState
	replay    replayWindow
}

func wrapConnectionBindingID(local, remote net.Addr) string {
	if local == nil || remote == nil {
		return ""
	}
	return local.String() + "|" + remote.String()
}

func connectionCredentialMatches(conn net.Conn, password string) bool {
	if conn == nil || password == "" {
		return false
	}
	id := wrapConnectionBindingID(conn.LocalAddr(), conn.RemoteAddr())
	value, ok := wrapCredentialBindings.Load(id)
	return ok && value == "pass:"+wrapKeyID(password)
}

// wrapReadBufPool — промежуточный буфер под RTP-заголовок+AEAD-тег+padding
// поверх p (см. ReadFrom ниже). Раньше аллоцировался заново на КАЖДЫЙ входящий
// пакет — при сотнях клиентов и тысячах pps это заметное GC-давление в
// downlink-пути. p приходит из bufPool (1600 байт, см. handleConn/handleConnRaw),
// поэтому 1700 с запасом хватает под len(p)+80.
var wrapReadBufPool = sync.Pool{
	New: func() interface{} {
		b := make([]byte, 1700)
		return &b
	},
}

func (c *wrapPacketConn) ReadFrom(p []byte) (int, net.Addr, error) {
	// Extra space for RTP header (12) + AEAD tag (16) + padding.
	bufPtr := wrapReadBufPool.Get().(*[]byte)
	defer wrapReadBufPool.Put(bufPtr)
	need := len(p) + 80
	if cap(*bufPtr) < need {
		*bufPtr = make([]byte, need)
	}
	buf := (*bufPtr)[:need]
	n, addr, err := c.inner.ReadFrom(buf)
	if err != nil {
		return 0, addr, err
	}
	raw := buf[:n]

	if atomic.LoadInt32(&c.selected) == 0 {
		key, keyID, m, uErr := c.keys.Unwrap(raw, p)
		if uErr != nil {
			// Проба ключом диагностики сервера (см. netdiag.go): отвечаем и
			// закрываем соединение, сессию по этому ключу не открываем.
			if serverDiag.tryDiagKey(c, raw, p, addr) {
				return 0, addr, errDiagnosticProbe
			}
			if obfsIsRTPPacket(raw) {
				serverDiag.noteAuthFailure(c.listener, addr)
			}
			if atomic.CompareAndSwapInt32(&c.authLog, 0, 1) {
				log.Printf("[WRAP] Отказ: RTP AEAD auth failed from %s (keys=%d)", addr.String(), c.keys.Count())
			}
			return 0, addr, uErr
		}
		if req, ok := parseDiagRequest(p[:m]); ok {
			// Проба ключом пароля: пароль верный, сообщаем его статус.
			serverDiag.answerDiagProbe(c, raw, req, key, keyID, addr)
			zeroBytes(key)
			return 0, addr, errDiagnosticProbe
		}
		cfg, cfgErr := NewObfsConfig("audio")
		if cfgErr != nil {
			return 0, addr, fmt.Errorf("wrap: random config: %w", cfgErr)
		}
		writeState, stateErr := NewObfsState()
		if stateErr != nil {
			return 0, addr, fmt.Errorf("wrap: random state: %w", stateErr)
		}
		c.key = append([]byte(nil), key...) // Клонируем ключ в независимую память!
		c.keyID = keyID
		c.bindingID = wrapConnectionBindingID(c.LocalAddr(), addr)
		wrapCredentialBindings.Store(c.bindingID, keyID)
		c.obfsCfg = cfg
		if len(raw) > 1 {
			c.obfsCfg.PayloadType = raw[1] & 0x7F
			if c.obfsCfg.PayloadType == 96 {
				c.obfsCfg.PaddingMax = 60
			}
		}
		c.obfsWrite = writeState
		atomic.StoreInt32(&c.selected, 1)
		if !c.replay.accept(raw) {
			return 0, addr, errors.New("wrap: replay")
		}
		if atomic.CompareAndSwapInt32(&c.authLog, 0, 1) {
			log.Printf("[WRAP] OK: ключ выбран для %s (keys=%d)", addr.String(), c.keys.Count())
		}
		return m, addr, nil
	}

	m, uErr := obfsUnwrapPacket(c.key, raw, p)
	if uErr != nil {
		// Если расшифровка старым ключом провалилась — возможно, пароль обновился!
		// Пробуем пере-верифицировать пакет по всем активным ключам
		key, keyID, m2, uErr2 := c.keys.Unwrap(raw, p)
		if uErr2 == nil {
			if !bytes.Equal(key, c.key) {
				c.replay = replayWindow{}
			}
			if !c.replay.accept(raw) {
				return 0, addr, errors.New("wrap: replay")
			}
			cfg, cfgErr := NewObfsConfig("audio")
			if cfgErr != nil {
				return 0, addr, fmt.Errorf("wrap: random config: %w", cfgErr)
			}
			writeState, stateErr := NewObfsState()
			if stateErr != nil {
				return 0, addr, fmt.Errorf("wrap: random state: %w", stateErr)
			}
			c.key = append([]byte(nil), key...) // На лету обновляем ключ сессии!
			c.keyID = keyID
			c.bindingID = wrapConnectionBindingID(c.LocalAddr(), addr)
			wrapCredentialBindings.Store(c.bindingID, keyID)
			c.obfsCfg = cfg
			if len(raw) > 1 {
				c.obfsCfg.PayloadType = raw[1] & 0x7F
				if c.obfsCfg.PayloadType == 96 {
					c.obfsCfg.PaddingMax = 60
				}
			}
			c.obfsWrite = writeState
			log.Printf("[WRAP] Обновлен ключ на лету для %s (пароль изменился/обновился)", addr.String())
			return m2, addr, nil
		}
		return 0, addr, fmt.Errorf("obfs unwrap: %w", uErr)
	}
	if !c.replay.accept(raw) {
		return 0, addr, errors.New("wrap: replay")
	}
	return m, addr, nil
}

func (c *wrapPacketConn) WriteTo(p []byte, addr net.Addr) (int, error) {
	if atomic.LoadInt32(&c.selected) == 0 || len(c.key) != wrapKeyLen {
		return 0, errors.New("wrap: key not selected")
	}
	if c.obfsCfg == nil || c.obfsWrite == nil {
		cfg, cfgErr := NewObfsConfig("audio")
		if cfgErr != nil {
			return 0, fmt.Errorf("wrap: random config: %w", cfgErr)
		}
		writeState, stateErr := NewObfsState()
		if stateErr != nil {
			return 0, fmt.Errorf("wrap: random state: %w", stateErr)
		}
		c.obfsCfg = cfg
		c.obfsWrite = writeState
	}
	bufPtr := wrapReadBufPool.Get().(*[]byte)
	defer wrapReadBufPool.Put(bufPtr)
	wrapped, wErr := obfsWrapPacket(c.key, p, c.obfsCfg, c.obfsWrite, *bufPtr)
	if wErr != nil {
		return 0, fmt.Errorf("obfs wrap: %w", wErr)
	}
	if _, err := c.inner.WriteTo(wrapped, addr); err != nil {
		return 0, err
	}
	return len(p), nil
}

func (c *wrapPacketConn) Close() error {
	if c.bindingID != "" {
		wrapCredentialBindings.Delete(c.bindingID)
	}
	evikey := c.key
	c.key = nil
	zeroBytes(evikey)
	return c.inner.Close()
}
func (c *wrapPacketConn) LocalAddr() net.Addr                { return c.inner.LocalAddr() }
func (c *wrapPacketConn) SetDeadline(t time.Time) error      { return c.inner.SetDeadline(t) }
func (c *wrapPacketConn) SetReadDeadline(t time.Time) error  { return c.inner.SetReadDeadline(t) }
func (c *wrapPacketConn) SetWriteDeadline(t time.Time) error { return c.inner.SetWriteDeadline(t) }
