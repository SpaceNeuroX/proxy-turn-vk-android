package main

import (
	"context"
	"errors"
	"log"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/dtls/v3"
	"golang.zx2c4.com/wireguard/device"
)

var credentialConnections = struct {
	sync.Mutex
	items map[string]map[net.Conn]string
}{items: make(map[string]map[net.Conn]string)}

func trackCredentialConnection(password, deviceID string, conn net.Conn) func() {
	ownerID := wrapKeyID(password)
	credentialConnections.Lock()
	connections := credentialConnections.items[ownerID]
	if connections == nil {
		connections = make(map[net.Conn]string)
		credentialConnections.items[ownerID] = connections
	}
	connections[conn] = deviceID
	credentialConnections.Unlock()
	return func() {
		credentialConnections.Lock()
		delete(connections, conn)
		if len(connections) == 0 {
			delete(credentialConnections.items, ownerID)
		}
		credentialConnections.Unlock()
	}
}

func disconnectCredentialConnections(password string) {
	disconnectCredentialDeviceConnections(password, "")
}

func disconnectCredentialDeviceConnections(password, deviceID string) {
	ownerID := wrapKeyID(password)
	credentialConnections.Lock()
	connections := credentialConnections.items[ownerID]
	list := make([]net.Conn, 0, len(connections))
	for conn, activeDeviceID := range connections {
		if deviceID != "" && activeDeviceID != deviceID {
			continue
		}
		list = append(list, conn)
		delete(connections, conn)
	}
	if len(connections) == 0 {
		delete(credentialConnections.items, ownerID)
	}
	credentialConnections.Unlock()
	for _, conn := range list {
		conn.Close()
	}
}

// ==================== Обработка соединений ====================

// directConn — net.Conn поверх уже обфусцированного (RTP-obfs AEAD) UDP-потока
// от wrapPacketListener, без DTLS. Используется для клиентов go_client -notls.
type directConn struct {
	pc   net.PacketConn
	addr net.Addr
}

func (c *directConn) Read(b []byte) (int, error) {
	// wrapPacketConn.ReadFrom возвращает ошибку и на битый/нерасшифрованный
	// пакет (обычное дело при потерях/переупорядочивании на мобильной сети),
	// и на настоящий сбой сокета. Раньше любая из них рвала всю сессию —
	// один битый пакет убивал воркера. Ретраим всё, кроме net.Error (реальная
	// сетевая ошибка/таймаут/закрытие), как уже делает клиентский obfsDirectConn.Read.
	for {
		n, _, err := c.pc.ReadFrom(b)
		if err != nil {
			var netErr net.Error
			if errors.As(err, &netErr) {
				return 0, err
			}
			continue
		}
		return n, nil
	}
}
func (c *directConn) Write(b []byte) (int, error)        { return c.pc.WriteTo(b, c.addr) }
func (c *directConn) Close() error                       { return c.pc.Close() }
func (c *directConn) LocalAddr() net.Addr                { return c.pc.LocalAddr() }
func (c *directConn) RemoteAddr() net.Addr               { return c.addr }
func (c *directConn) SetDeadline(t time.Time) error      { return c.pc.SetDeadline(t) }
func (c *directConn) SetReadDeadline(t time.Time) error  { return c.pc.SetReadDeadline(t) }
func (c *directConn) SetWriteDeadline(t time.Time) error { return c.pc.SetWriteDeadline(t) }

func handleConn(ctx context.Context, clientConn net.Conn, wgEndpoint string, wgDev *device.Device, keys *wgKeys) {
	atomic.AddInt64(&totalConns, 1)

	var connDeviceID string
	var authenticatedPassword string

	// DTLS-клиенты (обратная совместимость): хендшейк перед чтением данных.
	// Прямые (-notls) клиенты приходят уже как directConn — RTP-obfs AEAD
	// уже дал шифрование+аутентификацию, второй хендшейк не нужен.
	if dtlsConn, ok := clientConn.(*dtls.Conn); ok {
		hctx, hcancel := context.WithTimeout(ctx, 60*time.Second)
		if err := dtlsConn.HandshakeContext(hctx); err != nil {
			hcancel()
			log.Printf("[DTLS] [ERR] Handshake failed from %s: %v", clientConn.RemoteAddr().String(), err)
			return
		}
		hcancel()
	}

	atomic.AddInt32(&activeConns, 1)
	defer atomic.AddInt32(&activeConns, -1)

	buf := make([]byte, 1600)
	clientConn.SetReadDeadline(time.Now().Add(30 * time.Second))
	n, err := clientConn.Read(buf)
	if err != nil {
		return
	}
	clientConn.SetReadDeadline(time.Time{})

	firstPacket := buf[:n]
	firstStr := string(firstPacket)

	if strings.HasPrefix(firstStr, "GETCONF:") {
		parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(firstStr, "GETCONF:")), "|")
		clientPort := "9000"
		deviceID := "unknown"
		password := ""
		if len(parts) > 0 {
			clientPort = parts[0]
		}
		if len(parts) > 1 {
			deviceID = parts[1]
		}
		if len(parts) > 2 {
			password = parts[2]
		}
		if !connectionCredentialMatches(clientConn, password) {
			clientConn.Write([]byte("DENIED:wrong_password"))
			return
		}

		dbMutex.Lock()

		// Проверяем пароль
		isMainPass := password != "" && password == db.MainPassword
		entry, isGenPass := db.Passwords[password]
		valid := isMainPass || (isGenPass && !isPasswordExpired(entry))

		if valid && isGenPass && entry.IsDeactivated {
			clientConn.Write([]byte("DENIED:deactivated"))
			log.Printf("[WG] Отказ: пароль %s деактивирован, запрос от %s", maskPassword(password), deviceID)
			dbMutex.Unlock()
			return
		} else if valid && isGenPass && !entry.canConnectAndBind(deviceID) {
			// Достигнут лимит устройств или привязано к другому устройству
			clientConn.Write([]byte("DENIED:device_mismatch"))
			log.Printf("[WG] Отказ: пароль %s достиг лимита устройств (%d), запрос от %s", maskPassword(password), entry.MaxDevices, deviceID)
			dbMutex.Unlock()
			return
		} else if valid {
			connDeviceID = deviceID
			authenticatedPassword = password

			// Сохраняем БД, так как canConnectAndBind мог внести привязку нового устройства
			saveDB()

			dev, exists := db.Devices[deviceID]
			if !exists {
				dev = &ClientDevice{DeviceID: deviceID, IP: getNextIP()}
			}
			setDeviceOwner(dev, password)
			// Устройство могло быть создано раньше только Raw-путём
			// (GETCONF_RAW, см. handleConnRaw) — там PrivKey/PubKey никогда
			// не генерируются, только IP/RawIP. Без этой проверки такое
			// устройство при первом переключении на VPN(WireGuard) получало
			// бы конфиг с пустым PrivateKey навсегда (BadConfigException на
			// клиенте при каждой попытке), потому что ветка ниже раньше
			// генерировала ключи только для !exists.
			if dev.PrivKey == "" || dev.PubKey == "" {
				if dev.IP == "" {
					dev.IP = getNextIP()
				}
				privB64, pubB64, keyErr := generateKeyPair()
				if keyErr == nil && dev.IP != "" {
					dev.PrivKey = privB64
					dev.PubKey = pubB64
					db.Devices[deviceID] = dev
					saveDB()
					log.Printf("[WG] Сгенерированы ключи для устройства %s (IP: %s)", deviceID, dev.IP)
				} else {
					dev = nil
				}
			}
			if dev != nil {
				upsertPeerInWG(wgDev, dev)
				clientConn.Write([]byte(buildClientConfig(keys.serverPublic, dev.PrivKey, dev.IP, clientPort)))
			} else {
				clientConn.Write([]byte("NOCONF"))
				dbMutex.Unlock()
				return
			}
			dbMutex.Unlock()
		} else {
			if isGenPass && isPasswordExpired(entry) {
				clientConn.Write([]byte("DENIED:expired"))
				log.Printf("[WG] Отказ: пароль %s истёк, от %s", maskPassword(password), deviceID)
			} else {
				clientConn.Write([]byte("DENIED:wrong_password"))
				log.Printf("[WG] Отказ (неверный пароль) от %s", deviceID)
			}
			dbMutex.Unlock()
			return
		}

		clientConn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
		firstStr = string(firstPacket)
	} else if strings.HasPrefix(firstStr, "AUTH:") {
		parts := strings.Split(strings.TrimSpace(strings.TrimPrefix(firstStr, "AUTH:")), "|")
		deviceID := "unknown"
		password := ""
		if len(parts) > 0 {
			deviceID = parts[0]
		}
		if len(parts) > 1 {
			password = parts[1]
		}
		if !connectionCredentialMatches(clientConn, password) {
			clientConn.Write([]byte("DENIED:wrong_password"))
			return
		}

		dbMutex.Lock()
		isMainPass := password != "" && password == db.MainPassword
		entry, isGenPass := db.Passwords[password]
		valid := isMainPass || (isGenPass && !isPasswordExpired(entry) && !entry.IsDeactivated)
		bound := isMainPass
		if valid && isGenPass {
			bound = passwordEntryHasDevice(entry, deviceID)
		}
		if !valid || !bound {
			dbMutex.Unlock()
			clientConn.Write([]byte("DENIED:device_mismatch"))
			return
		}
		saveDB()
		dbMutex.Unlock()

		connDeviceID = deviceID
		authenticatedPassword = password

		clientConn.SetReadDeadline(time.Now().Add(5 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
		firstStr = string(firstPacket)
	}
	if authenticatedPassword == "" {
		return
	}
	untrackCredential := trackCredentialConnection(authenticatedPassword, connDeviceID, clientConn)
	defer untrackCredential()

	if firstStr == "READY" {
		clientConn.Write([]byte("READY_OK"))
		clientConn.SetReadDeadline(time.Now().Add(10 * time.Minute))
		n, err = clientConn.Read(buf)
		if err != nil {
			return
		}
		clientConn.SetReadDeadline(time.Time{})
		firstPacket = buf[:n]
	}

	// WG прокси
	wgConn, err := net.Dial("udp", wgEndpoint)
	if err != nil {
		return
	}
	defer wgConn.Close()

	if uc, ok := wgConn.(*net.UDPConn); ok {
		uc.SetReadBuffer(2 * 1024 * 1024)
		uc.SetWriteBuffer(2 * 1024 * 1024)
	}

	if _, err := wgConn.Write(firstPacket); err != nil {
		return
	}
	atomic.AddInt64(&totalBytesFromClient, int64(len(firstPacket)))

	// Трекинг онлайн-статуса
	if connDeviceID != "" {
		activeDevicesMu.Lock()
		activeDevices[connDeviceID]++
		activeDevicesMu.Unlock()
		defer func() {
			activeDevicesMu.Lock()
			activeDevices[connDeviceID]--
			if activeDevices[connDeviceID] <= 0 {
				delete(activeDevices, connDeviceID)
			}
			activeDevicesMu.Unlock()
		}()
	}

	pctx, pcancel := context.WithCancel(ctx)
	defer pcancel()

	context.AfterFunc(pctx, func() {
		clientConn.SetDeadline(time.Now())
		wgConn.SetDeadline(time.Now())
	})

	var proxyWg sync.WaitGroup
	proxyWg.Add(2)

	// Клиент → WG
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		b := getBuf()
		defer putBuf(b)
		for {
			select {
			case <-pctx.Done():
				return
			default:
			}
			clientConn.SetReadDeadline(time.Now().Add(30 * time.Minute))
			nn, err := clientConn.Read(*b)
			if err != nil {
				return
			}
			// Skip keepalive packets: первый байт 0xFF, размер переменный
			// (25-44 байта, было жёстко 1 байт) — см. комментарий в
			// handleConnRaw. WireGuard-пакеты всегда начинаются с байта
			// типа сообщения 1-4, никогда не 0xFF.
			if nn > 0 && (*b)[0] == 0xFF {
				continue
			}
			atomic.AddInt64(&totalBytesFromClient, int64(nn))
			// Учёт трафика теперь происходит через IpcGet()

			if _, err := wgConn.Write((*b)[:nn]); err != nil {
				return
			}
		}
	}()

	// WG → Клиент
	go func() {
		defer proxyWg.Done()
		defer pcancel()
		b := getBuf()
		defer putBuf(b)
		for {
			select {
			case <-pctx.Done():
				return
			default:
			}
			wgConn.SetReadDeadline(time.Now().Add(30 * time.Minute))
			nn, err := wgConn.Read(*b)
			if err != nil {
				if isNetTimeout(err) {
					if pctx.Err() != nil {
						return
					}
					continue
				}
				return
			}
			atomic.AddInt64(&totalBytesToClient, int64(nn))
			// Учёт трафика теперь происходит через IpcGet()

			if _, err := clientConn.Write((*b)[:nn]); err != nil {
				return
			}
		}
	}()

	proxyWg.Wait()
}

const wrapKeyLen = 32
