package main

import (
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"math/rand"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/cbeuw/connutil"
	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"github.com/pion/logging"
	"github.com/pion/transport/v4/stdnet"
	"github.com/pion/turn/v5"
)

const (
	workerSendBuf      = 128
	sessionReadTimeout = 30 * time.Minute // Increased from 60s to 30min
	readBufSize        = 1600
	socketBufSize      = 625 * 1024
	keepaliveByte      = 0xFF // keepalive marker (DTLS-level или прямой obfs-кадр)
	// keepaliveInterval: 1с (как у референсного клиента) — агрессивнее держит
	// TURN permission/NAT-маппинг "тёплым" на каждом из 18-108 relay-сокетов
	// сессии, чем прежние 15с/5с.
	keepaliveInterval = 10 * time.Second
	// keepaliveMinSize/keepaliveMaxSize: keepalive-пакет теперь не
	// фиксированного размера (было 1 байт постоянно) — случайная длина
	// 25-44 байта имитирует "тишину" OPUS в реальном звонке; постоянный
	// размер через равные интервалы — легко узнаваемый паттерн для DPI.
	keepaliveMinSize = 25
	keepaliveMaxSize = 20 // диапазон добавки к keepaliveMinSize (rand.Intn(20))
)

// obfsDirectConn — net.Conn поверх TURN relay БЕЗ DTLS.
//
// RTP-obfs (ChaCha20-Poly1305/AES-GCM AEAD, obfs.go) уже даёт полноценное
// шифрование+аутентификацию каждого пакета. DTLS поверх него был чистым
// оверхедом: self-signed сертификат с InsecureSkipVerify не добавлял
// реальной защиты, зато удваивал AEAD-обработку на пакет и требовал
// отдельного хендшейка (см. handshakeSem) на каждую из 9 воркер-сессий.
// Используется только когда tp.NoDTLS=true И useWrap=true (иначе тут вообще
// нет шифрования — тогда обязателен DTLS, см. ветку ниже).
type obfsDirectConn struct {
	relay      net.PacketConn
	peer       net.Addr
	wrapKey    []byte
	cfg        *ObfsConfig
	writeState *ObfsState
	replay     replayWindow
}

func (c *obfsDirectConn) Read(b []byte) (int, error) {
	wire := make([]byte, len(b)+80) // RTP-заголовок(12) + AEAD tag + padding
	for {
		n, _, err := c.relay.ReadFrom(wire)
		if err != nil {
			return 0, err
		}
		if !obfsIsRTPPacket(wire[:n]) {
			continue
		}
		m, unwrapErr := obfsUnwrapPacket(c.wrapKey, wire[:n], b)
		if unwrapErr != nil {
			continue
		}
		if !c.replay.accept(wire[:n]) {
			continue
		}
		return m, nil
	}
}

func (c *obfsDirectConn) Write(b []byte) (int, error) {
	wrapped, err := obfsWrapPacket(c.wrapKey, b, c.cfg, c.writeState)
	if err != nil {
		return 0, err
	}
	if _, err := c.relay.WriteTo(wrapped, c.peer); err != nil {
		return 0, err
	}
	return len(b), nil
}

func (c *obfsDirectConn) Close() error                       { return nil }
func (c *obfsDirectConn) LocalAddr() net.Addr                { return c.relay.LocalAddr() }
func (c *obfsDirectConn) RemoteAddr() net.Addr               { return c.peer }
func (c *obfsDirectConn) SetDeadline(t time.Time) error      { return c.relay.SetDeadline(t) }
func (c *obfsDirectConn) SetReadDeadline(t time.Time) error  { return c.relay.SetReadDeadline(t) }
func (c *obfsDirectConn) SetWriteDeadline(t time.Time) error { return c.relay.SetWriteDeadline(t) }

// Handshake semaphore: limit to 3 concurrent DTLS handshakes
var handshakeSem = make(chan struct{}, 3)

// NullLoggerFactory подавляет логи pion
type NullLoggerFactory struct{}

func (n *NullLoggerFactory) NewLogger(_ string) logging.LeveledLogger { return &NullLogger{} }

type NullLogger struct{}

func (n *NullLogger) Trace(_ string)                    {}
func (n *NullLogger) Tracef(_ string, _ ...interface{}) {}
func (n *NullLogger) Debug(_ string)                    {}
func (n *NullLogger) Debugf(_ string, _ ...interface{}) {}
func (n *NullLogger) Info(_ string)                     {}
func (n *NullLogger) Infof(_ string, _ ...interface{})  {}
func (n *NullLogger) Warn(_ string)                     {}
func (n *NullLogger) Warnf(_ string, _ ...interface{})  {}
func (n *NullLogger) Error(_ string)                    {}
func (n *NullLogger) Errorf(_ string, _ ...interface{}) {}

func RunSession(
	ctx context.Context,
	tp *TurnParams,
	peer *net.UDPAddr,
	d *Dispatcher,
	localPort string,
	getConfig bool,
	configCh chan<- string,
	sessionID int,
	creds *Credentials,
	deviceID, password string,
	stats *Stats,
	allocateGate <-chan time.Time,
) (bool, error) {
	configDelivered := false
	var firstWrapUp uint32
	var firstWrapDown uint32
	var firstWireWrite uint32
	var firstWireRead uint32

	target, err := selectTurnTarget(tp, creds.TurnURLs, sessionID)
	if err != nil {
		return false, err
	}
	turnAddr := target.Addr

	turnConn, turnConnCloser, err := dialTURNConn(ctx, target)
	if err != nil {
		return false, err
	}
	defer turnConnCloser.Close()

	if target.SNI != "" {
		log.Printf("[СЕССИЯ #%d] TURN %s (%s, SNI %s)", sessionID, strings.ToUpper(target.Transport), turnAddr, target.SNI)
	} else {
		log.Printf("[СЕССИЯ #%d] TURN %s (%s)", sessionID, strings.ToUpper(target.Transport), turnAddr)
	}

	// RequestedAddressFamily
	var addrFamily turn.RequestedAddressFamily
	if peer.IP.To4() != nil {
		addrFamily = turn.RequestedAddressFamilyIPv4
	} else {
		addrFamily = turn.RequestedAddressFamilyIPv6
	}

	// Pion's default stdnet.NewNet enumerates network interfaces through
	// NETLINK_ROUTE. Some Huawei/Honor ROMs deny that operation to apps.
	// The zero-value stdnet.Net provides everything this UDP client uses
	// without performing the unnecessary interface enumeration.
	tc, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr:         turnAddr,
		TURNServerAddr:         turnAddr,
		Conn:                   turnConn,
		Net:                    new(stdnet.Net),
		Username:               creds.User,
		Password:               creds.Pass,
		RequestedAddressFamily: addrFamily,
		LoggerFactory:          &NullLoggerFactory{},
	})
	if err != nil {
		return false, fmt.Errorf("TURN клиент: %w", err)
	}
	defer tc.Close()

	if err = tc.Listen(); err != nil {
		return false, fmt.Errorf("TURN Listen: %w", err)
	}

	// Глобальный rate-limit на сам момент TURN Allocate (не только на старт
	// горутины воркера, см. workerDelay в group.go) — не более одной новой
	// TURN-аллокации за тик по всей группе, независимо от того, сколько
	// воркеров сейчас готовы её сделать. Без этого стаггеринг старта не
	// спасает: на нестабильной сети (ретраи/задержки Allocate) несколько
	// воркеров всё равно накладываются друг на друга и вместе выжигают
	// VK-квоту (error 486) быстрее, чем должны. Тот же приём использует
	// free-turn-proxy (internal/proxy/udprelay/loop.go, общий 200ms-тикер).
	if allocateGate != nil {
		select {
		case <-allocateGate:
		case <-ctx.Done():
			return false, ctx.Err()
		}
	}

	relay, err := tc.Allocate()
	if err != nil {
		if isAuthError(err) {
			handleAuthError(creds.CacheStreamID)
		}
		errStr := err.Error()
		if strings.Contains(errStr, "Quota") || strings.Contains(errStr, "486") {
			return false, fmt.Errorf("TURN квота: %w", err)
		}
		return false, fmt.Errorf("TURN Allocate: %w", err)
	}
	defer relay.Close()

	// Reset error count on successful allocation
	getStreamCache(creds.CacheStreamID).errorCount.Store(0)

	log.Printf("[СЕССИЯ #%d] Relay: %s", sessionID, relay.LocalAddr())

	sessCtx, sessCancel := context.WithCancel(ctx)
	defer sessCancel()

	// Keepalive goroutine (TURN binding request)
	var sessionWg sync.WaitGroup
	sessionWg.Add(1)
	go func() {
		defer sessionWg.Done()
		t := time.NewTicker(10 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-sessCtx.Done():
				return
			case <-t.C:
				tc.SendBindingRequest()
			}
		}
	}()

	useWrap := len(tp.WrapKey) == wrapKeyLen

	var activeConn net.Conn
	var relayWg sync.WaitGroup

	if useWrap && (tp.NoDTLS || tp.RawMode) {
		// ─── Прямой режим: RTP-obfs AEAD прямо поверх TURN relay, без DTLS ───
		obfsCfg, obfsErr := NewObfsConfig(tp.ObfsMode)
		if obfsErr != nil {
			return false, fmt.Errorf("RTP-obfs config: %w", obfsErr)
		}
		obfsWriteState, obfsErr := NewObfsState()
		if obfsErr != nil {
			return false, fmt.Errorf("RTP-obfs state: %w", obfsErr)
		}
		activeConn = &obfsDirectConn{
			relay:      relay,
			peer:       peer,
			wrapKey:    tp.WrapKey,
			cfg:        obfsCfg,
			writeState: obfsWriteState,
		}
		log.Printf("[ВОРКЕР #%d] [ПРЯМОЙ] Без DTLS, только RTP-obfs AEAD ✓", sessionID)
	} else {
		// ─── Классический режим: DTLS поверх RTP-obfs (обратная совместимость) ───
		pipeA, pipeB := connutil.AsyncPacketPipe()
		defer pipeA.Close()
		defer pipeB.Close()

		var dtlsObfsCfg *ObfsConfig
		var obfsWriteState *ObfsState
		if useWrap {
			dtlsObfsCfg, err = NewObfsConfig(tp.ObfsMode)
			if err != nil {
				return false, fmt.Errorf("RTP-obfs config: %w", err)
			}
			obfsWriteState, err = NewObfsState()
			if err != nil {
				return false, fmt.Errorf("RTP-obfs state: %w", err)
			}
		}

		relayWg.Add(2)
		stopRelay := context.AfterFunc(sessCtx, func() {
			_ = relay.SetDeadline(time.Now())
			_ = pipeA.SetDeadline(time.Now())
		})
		defer stopRelay()

		// relay → pipeA (UNWRAP: strip RTP header + decrypt)
		go func() {
			defer relayWg.Done()
			defer sessCancel()
			// Max incoming: RTP header (12) + AEAD tag (16) + padding.
			readBufLen := readBufSize + 80
			buf := make([]byte, readBufLen)
			plain := make([]byte, readBufSize)
			var replay replayWindow
			for {
				n, _, readErr := relay.ReadFrom(buf)
				if readErr != nil {
					return
				}
				payload := buf[:n]
				if useWrap {
					if !obfsIsRTPPacket(payload) {
						log.Printf("[СЕССИЯ #%d] OBFS unwrap: unexpected packet (n=%d)", sessionID, n)
						continue
					}
					m, wrapErr := obfsUnwrapPacket(tp.WrapKey, payload, plain)
					if wrapErr != nil {
						log.Printf("[СЕССИЯ #%d] OBFS unwrap: %v (n=%d)", sessionID, wrapErr, n)
						continue
					}
					if !replay.accept(payload) {
						continue
					}
					payload = plain[:m]
				}
				if atomic.CompareAndSwapUint32(&firstWrapUp, 0, 1) {
					log.Printf("[СЕССИЯ #%d] [ДЕБАГ] Успешно расшифрован/получен ПЕРВЫЙ пакет от TURN Relay (%d байт)", sessionID, len(payload))
				}
				if _, writeErr := pipeA.WriteTo(payload, peer); writeErr != nil {
					return
				}
			}
		}()

		// pipeA → relay (WRAP: add RTP header + encrypt)
		go func() {
			defer relayWg.Done()
			defer sessCancel()
			b := make([]byte, readBufSize)
			for {
				n, _, readErr := pipeA.ReadFrom(b)
				if readErr != nil {
					return
				}
				out := b[:n]
				if useWrap {
					if dtlsObfsCfg != nil && obfsWriteState != nil {
						wrapped, wrapErr := obfsWrapPacket(tp.WrapKey, out, dtlsObfsCfg, obfsWriteState)
						if wrapErr != nil {
							log.Printf("[СЕССИЯ #%d] OBFS wrap: %v", sessionID, wrapErr)
							return
						}
						out = wrapped
					}
				}
				if atomic.CompareAndSwapUint32(&firstWrapDown, 0, 1) {
					log.Printf("[СЕССИЯ #%d] [ДЕБАГ] Успешно зашифрован/отправлен ПЕРВЫЙ пакет на TURN Relay (%d байт)", sessionID, len(out))
				}
				if _, writeErr := relay.WriteTo(out, peer); writeErr != nil {
					return
				}
			}
		}()

		// DTLS с поддержкой Connection ID (без SNI)
		cert, err := selfsign.GenerateSelfSigned()
		if err != nil {
			return false, fmt.Errorf("генерация сертификата: %w", err)
		}

		// Acquire handshake semaphore
		select {
		case handshakeSem <- struct{}{}:
		case <-sessCtx.Done():
			return false, sessCtx.Err()
		}

		dtlsCfg := &dtls.Config{
			Certificates:          []tls.Certificate{cert},
			InsecureSkipVerify:    true,
			ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
			CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
			ConnectionIDGenerator: dtls.OnlySendCIDGenerator(),
			MTU:                   1100,
			// No ServerName (SNI) — less detectable by DPI
		}

		dtlsConn, err := dtls.Client(pipeB, peer, dtlsCfg)
		if err != nil {
			<-handshakeSem
			return false, fmt.Errorf("DTLS клиент: %w", err)
		}

		hctx, hcancel := context.WithTimeout(sessCtx, 50*time.Second)
		log.Printf("[ВОРКЕР #%d] [DTLS] Рукопожатие (Handshake)...", sessionID)
		err = dtlsConn.HandshakeContext(hctx)
		hcancel()
		<-handshakeSem // RELEASE SEMAPHORE IMMEDIATELY AFTER HANDSHAKE

		if err != nil {
			dtlsConn.Close()
			if useWrap {
				errStr := strings.ToLower(err.Error())
				if strings.Contains(errStr, "deadline") || strings.Contains(errStr, "timeout") {
					return false, fmt.Errorf("WRAP_AUTH_TIMEOUT: DTLS timeout, пароль/WRAP не подтверждён")
				}
			}
			return false, fmt.Errorf("DTLS хендшейк: %w", err)
		}
		log.Printf("[ВОРКЕР #%d] [DTLS] Соединение установлено ✓", sessionID)
		activeConn = dtlsConn
	}
	defer activeConn.Close()

	stats.ActiveConnections.Add(1)
	defer stats.ActiveConnections.Add(-1)

	// Запрос конфига
	if getConfig && configCh != nil && tp.RawMode {
		ip, dnsCSV, mtu, confErr := RequestRawConfig(activeConn, deviceID, password)
		if confErr != nil {
			errStr := confErr.Error()
			if strings.Contains(errStr, "FATAL_AUTH") {
				return false, confErr
			}
			log.Printf("[ВОРКЕР #%d] Ошибка RAW-конфига: %v", sessionID, confErr)
		} else if ip != "" {
			conf := fmt.Sprintf("RAWCONF:%s|%s|%d", ip, dnsCSV, mtu)
			select {
			case configCh <- conf:
				configDelivered = true
				log.Printf("[ВОРКЕР #%d] RAW-конфиг получен (ip=%s)", sessionID, ip)
			default:
				configDelivered = true
				log.Printf("[ВОРКЕР #%d] RAW-конфиг уже был доставлен другим воркером", sessionID)
			}
		} else {
			log.Printf("[ВОРКЕР #%d] Сервер ещё не назначил raw IP, повторим позже", sessionID)
		}
	} else if getConfig && configCh != nil {
		conf, confErr := RequestConfig(activeConn, localPort, deviceID, password)
		if confErr != nil {
			errStr := confErr.Error()
			if strings.Contains(errStr, "FATAL_AUTH") {
				return false, confErr
			}
			log.Printf("[ВОРКЕР #%d] Ошибка конфига: %v", sessionID, confErr)
		} else if conf != "" {
			select {
			case configCh <- conf:
				configDelivered = true
				log.Printf("[ВОРКЕР #%d] Конфиг получен", sessionID)
			default:
				configDelivered = true
				log.Printf("[ВОРКЕР #%d] Конфиг уже был доставлен другим воркером", sessionID)
			}
		} else {
			log.Printf("[ВОРКЕР #%d] Сервер ещё не выдал WireGuard-конфиг, повторим позже", sessionID)
		}
	} else {
		if authErr := SendAuth(activeConn, deviceID, password); authErr != nil {
			log.Printf("[ВОРКЕР #%d] Ошибка авторизации: %v", sessionID, authErr)
		}
	}

	log.Printf("[ВОРКЕР #%d] [READY] Туннель готов к работе ✓", sessionID)

	// Регистрация в диспетчере
	slot := &WorkerSlot{
		ID:     sessionID,
		SendCh: make(chan []byte, workerSendBuf),
		PrioCh: make(chan []byte, prioBuf),
	}
	d.Register(slot)
	defer d.Unregister(slot)

	// Proxy activeConn ↔ Dispatcher
	var proxyWg sync.WaitGroup
	proxyWg.Add(3) // +1 for keepalive goroutine
	sessionErrCh := make(chan error, 1)

	stopConn := context.AfterFunc(sessCtx, func() {
		_ = activeConn.SetDeadline(time.Now())
	})
	defer stopConn()

	if tp.RawMode {
		// Транспорт — UDP через TURN, у сервера нет способа узнать о разрыве
		// соединения кроме таймаута (см. handleConnRaw). Явно сообщаем о
		// намеренном отключении, чтобы сервер сразу освободил слот в
		// rawRouter вместо того чтобы ждать простоя — иначе "мёртвые"
		// соединения от прошлого сеанса засоряют round-robin в downlinkLoop
		// при быстром переподключении того же устройства.
		go func() {
			select {
			case <-ctx.Done():
				_ = activeConn.SetWriteDeadline(time.Now().Add(500 * time.Millisecond))
				_, _ = activeConn.Write([]byte("DISCONNECT_RAW:" + deviceID))
			case <-sessCtx.Done():
			}
		}()
	}

	// Keepalive: prevents TURN allocation timeout and idle disconnect.
	// Пакет не пишется напрямую в activeConn (это была бы вторая горутина,
	// конкурирующая за conn с основным Writer'ом ниже) — кладётся в
	// slot.PrioCh неблокирующе, тем же путём, что и мелкие ACK-пакеты, и
	// уходит через единственную writer-горутину.
	go func() {
		defer proxyWg.Done()
		t := time.NewTicker(keepaliveInterval)
		defer t.Stop()

		didBytes := make([]byte, 16)
		copy(didBytes, deviceID)

		for {
			select {
			case <-sessCtx.Done():
				return
			case <-t.C:
				size := keepaliveMinSize + rand.Intn(keepaliveMaxSize)
				pkt := getPktBuf(size)
				pkt[0] = keepaliveByte
				copy(pkt[1:17], didBytes)
				for i := 17; i < size; i++ {
					pkt[i] = keepaliveByte
				}
				select {
				case slot.PrioCh <- pkt:
				default:
					putPktBuf(pkt)
				}
			}
		}
	}()

	// Writer: dispatcher → activeConn. PrioCh (мелкие пакеты/ACK) всегда
	// проверяется первым, минуя SendCh с обычными данными — иначе ACK может
	// простоять в очереди за большим chunk'ом данных.
	go func() {
		defer proxyWg.Done()
		defer sessCancel()
		defer func() {
			for {
				select {
				case p := <-slot.PrioCh:
					putPktBuf(p)
				default:
					goto drainSend
				}
			}
		drainSend:
			for {
				select {
				case p := <-slot.SendCh:
					putPktBuf(p)
				default:
					return
				}
			}
		}()
		for {
			var pkt []byte
			var ok bool
			select {
			case pkt, ok = <-slot.PrioCh:
			default:
				select {
				case <-sessCtx.Done():
					return
				case pkt, ok = <-slot.PrioCh:
				case pkt, ok = <-slot.SendCh:
				}
			}
			if !ok {
				return
			}
			// 3с, не sessionReadTimeout (30 мин). Запись пишет в
			// UDP-сокет к TURN relay, а не читает долгоживущее соединение;
			// 30-минутный дедлайн означал, что зависшая запись (переполненный
			// сокет-буфер ОС, плохая сеть) держала бы Writer колом полчаса,
			// пока очередь SendCh/PrioCh этого воркера копится и/или дропается
			// диспетчером выше (см. readLoop в dispatcher.go).
			_ = activeConn.SetWriteDeadline(time.Now().Add(3 * time.Second))
			if atomic.CompareAndSwapUint32(&firstWireWrite, 0, 1) {
				log.Printf("[ВОРКЕР #%d] [ДЕБАГ] Отправлен ПЕРВЫЙ пакет в соединение (%d байт)", sessionID, len(pkt))
			}
			_, writeErr := activeConn.Write(pkt)
			putPktBuf(pkt)
			if writeErr != nil {
				log.Printf("[ВОРКЕР #%d] Ошибка Writer: %v", sessionID, writeErr)
				select {
				case sessionErrCh <- fmt.Errorf("transport writer: %w", writeErr):
				default:
				}
				return
			}
		}
	}()

	// Reader: activeConn → dispatcher
	go func() {
		defer proxyWg.Done()
		defer sessCancel()
		b := make([]byte, 2000)
		for {
			_ = activeConn.SetReadDeadline(time.Now().Add(sessionReadTimeout))
			n, readErr := activeConn.Read(b)
			if readErr != nil {
				if sessCtx.Err() != nil {
					return
				}
				if ne, ok := readErr.(net.Error); ok && ne.Timeout() {
					continue
				}
				log.Printf("[ВОРКЕР #%d] Ошибка Reader: %v", sessionID, readErr)
				select {
				case sessionErrCh <- fmt.Errorf("transport reader: %w", readErr):
				default:
				}
				return
			}

			// Skip keepalive pong from server
			if n == 1 && b[0] == keepaliveByte {
				continue
			}

			if atomic.CompareAndSwapUint32(&firstWireRead, 0, 1) {
				log.Printf("[ВОРКЕР #%d] [ДЕБАГ] Получен ПЕРВЫЙ пакет из соединения (%d байт)", sessionID, n)
			}

			pkt := getPktBuf(n)
			copy(pkt, b[:n])
			select {
			case d.ReturnCh <- pkt:
			case <-sessCtx.Done():
				putPktBuf(pkt)
				return
			default:
				// ReturnCh полон — пакет дропается, но эта горутина не
				// блокируется. Раньше здесь не было default: если writeLoop
				// не успевал разгружать ReturnCh (например TUN.Write тормозит
				// на конкретном устройстве), Reader вставал колом на этом
				// select и переставал читать activeConn.Read() вообще — то
				// есть новые пакеты от сервера (включая реальные ответы на
				// пользовательский трафик) переставали вычитываться из
				// UDP-сокета ОС и терялись там, а не здесь. Один раз
				// заполнившийся канал убивал весь дальнейший приём для этого
				// воркера — select должен быть неблокирующим.
				putPktBuf(pkt)
			}
		}
	}()

	proxyWg.Wait()
	sessCancel()
	relayWg.Wait()
	sessionWg.Wait()
	log.Printf("[СЕССИЯ #%d] Завершена", sessionID)
	select {
	case sessionErr := <-sessionErrCh:
		return configDelivered, sessionErr
	default:
	}
	return configDelivered, nil
}

func RunPing(
	ctx context.Context,
	tp *TurnParams,
	peer *net.UDPAddr,
	creds *Credentials,
) (int64, error) {
	startPing := time.Now()

	target, err := selectTurnTarget(tp, creds.TurnURLs, 0)
	if err != nil {
		return 0, err
	}
	turnAddr := target.Addr

	turnConn, turnConnCloser, err := dialTURNConn(ctx, target)
	if err != nil {
		return 0, err
	}
	defer turnConnCloser.Close()

	var addrFamily turn.RequestedAddressFamily
	if peer.IP.To4() != nil {
		addrFamily = turn.RequestedAddressFamilyIPv4
	} else {
		addrFamily = turn.RequestedAddressFamilyIPv6
	}

	tc, err := turn.NewClient(&turn.ClientConfig{
		STUNServerAddr:         turnAddr,
		TURNServerAddr:         turnAddr,
		Conn:                   turnConn,
		Net:                    new(stdnet.Net),
		Username:               creds.User,
		Password:               creds.Pass,
		RequestedAddressFamily: addrFamily,
		LoggerFactory:          &NullLoggerFactory{},
	})
	if err != nil {
		return 0, err
	}
	defer tc.Close()

	if err = tc.Listen(); err != nil {
		return 0, err
	}

	relay, err := tc.Allocate()
	if err != nil {
		return 0, err
	}
	defer relay.Close()

	pipeA, pipeB := connutil.AsyncPacketPipe()
	defer pipeA.Close()
	defer pipeB.Close()

	sessCtx, sessCancel := context.WithCancel(ctx)
	defer sessCancel()

	var relayWg sync.WaitGroup
	relayWg.Add(2)

	useWrap := len(tp.WrapKey) == wrapKeyLen
	var obfsCfg *ObfsConfig
	var obfsWriteState *ObfsState
	if useWrap {
		obfsCfg, err = NewObfsConfig(tp.ObfsMode)
		if err != nil {
			return 0, fmt.Errorf("RTP-obfs config: %w", err)
		}
		obfsWriteState, err = NewObfsState()
		if err != nil {
			return 0, fmt.Errorf("RTP-obfs state: %w", err)
		}
	}

	// relay → pipeA
	go func() {
		defer relayWg.Done()
		defer sessCancel()
		buf := make([]byte, readBufSize+80)
		plain := make([]byte, readBufSize)
		var replay replayWindow
		for {
			n, _, err := relay.ReadFrom(buf)
			if err != nil {
				return
			}
			payload := buf[:n]
			if useWrap {
				if !obfsIsRTPPacket(payload) {
					continue
				}
				m, err := obfsUnwrapPacket(tp.WrapKey, payload, plain)
				if err != nil {
					continue
				}
				if !replay.accept(payload) {
					continue
				}
				payload = plain[:m]
			}
			_, _ = pipeA.WriteTo(payload, peer)
		}
	}()

	// pipeA → relay
	go func() {
		defer relayWg.Done()
		defer sessCancel()
		b := make([]byte, readBufSize)
		for {
			n, _, err := pipeA.ReadFrom(b)
			if err != nil {
				return
			}
			out := b[:n]
			if useWrap {
				wrapped, err := obfsWrapPacket(tp.WrapKey, out, obfsCfg, obfsWriteState)
				if err != nil {
					return
				}
				out = wrapped
			}
			_, _ = relay.WriteTo(out, peer)
		}
	}()

	cert, err := selfsign.GenerateSelfSigned()
	if err != nil {
		return 0, err
	}

	dtlsCfg := &dtls.Config{
		Certificates:          []tls.Certificate{cert},
		InsecureSkipVerify:    true,
		ExtendedMasterSecret:  dtls.RequireExtendedMasterSecret,
		CipherSuites:          []dtls.CipherSuiteID{dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256},
		ConnectionIDGenerator: dtls.OnlySendCIDGenerator(),
		MTU:                   1100,
	}

	dtlsConn, err := dtls.Client(pipeB, peer, dtlsCfg)
	if err != nil {
		return 0, err
	}
	defer dtlsConn.Close()

	hctx, hcancel := context.WithTimeout(sessCtx, 15*time.Second)
	defer hcancel()

	err = dtlsConn.HandshakeContext(hctx)
	if err != nil {
		return 0, err
	}

	rtt := time.Since(startPing).Milliseconds()
	// Handshake completes -> we have a successful round trip!
	return rtt, nil
}
