package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/pion/dtls/v3"
	"github.com/pion/dtls/v3/pkg/crypto/selfsign"
	"golang.zx2c4.com/wireguard/device"
)

// ==================== Main ====================

func main() {
	listen := flag.String("listen", "0.0.0.0:56000", "DTLS адрес")
	listenDirect := flag.String("listen-direct", "", "адрес для клиентов без DTLS (RTP-obfs AEAD напрямую); пусто = выключено")
	listenRaw := flag.String("listen-raw", "", "адрес для raw-IP клиентов без WireGuard (свой TUN/NAT); пусто = выключено")
	adminListen := flag.String("admin-listen", "", "HTTPS адрес admin API; пусто = выключено")
	adminTokenFile := flag.String("admin-token-file", "", "файл токена admin API")
	adminCert := flag.String("admin-cert", "", "TLS сертификат admin API")
	adminKey := flag.String("admin-key", "", "TLS ключ admin API")
	wgPort := flag.Int("wg-port", defaultInternalWGPort, "WireGuard UDP порт")
	configDir := flag.String("config-dir", "/etc/glebsbp", "директория конфигурации")
	mainPass := flag.String("password", "", "пароль владельца")
	mainPassFile := flag.String("password-file", "", "файл пароля владельца")
	adminID := flag.String("admin", "", "Telegram Admin ID")
	botToken := flag.String("bot-token", "", "Telegram Bot Token")
	botTokenFile := flag.String("bot-token-file", "", "файл Telegram Bot Token")
	dnsFlag := flag.String("dns", "8.8.8.8", "DNS серверы для клиентов")
	flag.Parse()
	dns = *dnsFlag
	mainPasswordValue, err := loadOptionalSecret(*mainPass, *mainPassFile)
	if err != nil {
		log.Fatalf("[CONFIG] Пароль владельца: %v", err)
	}
	botTokenValue, err := loadOptionalSecret(*botToken, *botTokenFile)
	if err != nil {
		log.Fatalf("[CONFIG] Telegram Bot Token: %v", err)
	}

	log.SetFlags(log.Ldate | log.Ltime | log.Lmicroseconds)
	log.Println("══════════════════════════════════════════")
	log.Println("   GLEBSBP Server v2 (Multi-User)")
	log.Println("══════════════════════════════════════════")

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGTERM, syscall.SIGINT, syscall.SIGHUP)
	var wgDev *device.Device
	go func() {
		for s := range sig {
			if s == syscall.SIGHUP {
				log.Println("[SYS] Получен сигнал SIGHUP. Перезагрузка базы паролей...")
				if wgDev == nil {
					log.Println("[ERR] WireGuard еще не запущен, пропуск SIGHUP")
					continue
				}
				if err := reloadDB(wgDev); err != nil {
					log.Printf("[ERR] Ошибка перезагрузки базы паролей: %v", err)
				} else {
					log.Println("[SYS] База паролей успешно перезагружена! Активных ключей в памяти:", serverWrapKeys.Count())
				}
			} else {
				cancel()
				dbMutex.Lock()
				flushRawDeviceTrafficLocked()
				saveDB()
				dbMutex.Unlock()
				time.Sleep(2 * time.Second)
				os.Exit(0)
			}
		}
	}()

	initDB(*configDir, mainPasswordValue, *adminID, botTokenValue)
	botTokenRef = botTokenValue
	loadPaymentConfig(*configDir)
	initInvoicesStore(*configDir)

	keys, err := loadOrGenerateKeys(*configDir)
	if err != nil {
		log.Fatalf("[WG] Ключи: %v", err)
	}

	enableBBR()

	wgDev, err = startUserspaceWG(keys, *wgPort)
	if err != nil {
		log.Fatalf("[WG] Запуск: %v", err)
	}
	globalWgDev = wgDev
	if removed := cleanupExpiredPasswords(wgDev); removed > 0 {
		log.Printf("[DB] Удалено истёкших паролей при старте: %d", removed)
	}
	syncPersistedPeersToWG(wgDev)
	defer func() {
		wgDev.Close()
		runCmdSilent("ip", "link", "del", wgIfaceName)
	}()

	go statsLoop(ctx, *configDir)
	go expiredPasswordJanitor(ctx, wgDev)
	go botLoop(botTokenValue, *adminID, wgDev)
	if anyPaymentEnabled() {
		go paymentJanitorLoop()
		log.Println("[PAY] Платёжные системы активны")
	}

	go func() {
		mux := http.NewServeMux()
		mux.HandleFunc("/api/profile/challenge", handleAPIProfileChallenge)
		mux.HandleFunc("/api/profile/status", handleAPIProfileStatus)
		mux.HandleFunc("/api/profile/unbind", handleAPIProfileUnbind)

		log.Printf("[API] Запуск HTTP API на %s (TCP)...", *listen)
		server := &http.Server{
			Addr:              *listen,
			Handler:           http.MaxBytesHandler(mux, 64<<10),
			ReadHeaderTimeout: 5 * time.Second,
			ReadTimeout:       10 * time.Second,
			WriteTimeout:      10 * time.Second,
			IdleTimeout:       30 * time.Second,
			MaxHeaderBytes:    16 << 10,
		}
		if err := server.ListenAndServe(); err != nil {
			log.Printf("[API] [ERR] Ошибка запуска HTTP API: %v", err)
		}
	}()

	if *adminListen != "" {
		tokenBytes, tokenErr := os.ReadFile(*adminTokenFile)
		if tokenErr != nil || strings.TrimSpace(string(tokenBytes)) == "" {
			log.Fatalf("[ADMIN API] токен: %v", tokenErr)
		}
		if *adminCert == "" || *adminKey == "" {
			log.Fatal("[ADMIN API] нужны -admin-cert и -admin-key")
		}
		setAdminAPIToken(strings.TrimSpace(string(tokenBytes)))
		go func() {
			mux := http.NewServeMux()
			registerAdminAPIRoutes(mux)
			server := &http.Server{
				Addr:              *adminListen,
				Handler:           http.MaxBytesHandler(mux, 64<<10),
				ReadHeaderTimeout: 5 * time.Second,
				ReadTimeout:       10 * time.Second,
				WriteTimeout:      10 * time.Second,
				IdleTimeout:       30 * time.Second,
				MaxHeaderBytes:    16 << 10,
				TLSConfig: &tls.Config{
					MinVersion: tls.VersionTLS12,
				},
			}
			log.Printf("[ADMIN API] HTTPS на %s", *adminListen)
			if err := server.ListenAndServeTLS(*adminCert, *adminKey); err != nil {
				log.Printf("[ADMIN API] [ERR] %v", err)
			}
		}()
	}

	addr, _ := net.ResolveUDPAddr("udp", *listen)
	cert, certErr := selfsign.GenerateSelfSigned()
	if certErr != nil {
		log.Fatalf("[DTLS] Не удалось создать сертификат: %v", certErr)
	}
	if serverWrapKeys.Count() == 0 {
		log.Fatalf("[WRAP] нет активных паролей для WRAP")
	}

	wrapListener, err := listenWrapped(addr, serverWrapKeys)
	if err != nil {
		log.Fatalf("[WRAP] %v", err)
	}

	listener, err := dtls.NewListenerWithOptions(wrapListener, dtls.WithCertificates(cert), dtls.WithExtendedMasterSecret(dtls.RequireExtendedMasterSecret), dtls.WithCipherSuites(dtls.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256), dtls.WithConnectionIDGenerator(dtls.RandomCIDGenerator(8)), dtls.WithMTU(1100))
	if err != nil {
		log.Fatalf("[DTLS] %v", err)
	}
	context.AfterFunc(ctx, func() { listener.Close() })

	wgEndpoint := fmt.Sprintf("127.0.0.1:%d", *wgPort)

	log.Printf("   DTLS: %s | WG: %s | NAT: %s", *listen, wgEndpoint, natType)
	log.Printf("   WRAP: password HKDF + RTP AEAD | keys: %d", serverWrapKeys.Count())

	var wg sync.WaitGroup

	// Прямой (без DTLS) листенер — тот же RTP-obfs AEAD, но без второго
	// слоя шифрования и без DTLS-хендшейка. Отдельный порт ради обратной
	// совместимости: старые клиенты продолжают ходить через -listen/DTLS,
	// новые — сюда через -notls в go_client. Включается явно оператором.
	if *listenDirect != "" {
		directAddr, err := net.ResolveUDPAddr("udp", *listenDirect)
		if err != nil {
			log.Fatalf("[DIRECT] адрес: %v", err)
		}
		directWrapListener, err := listenWrapped(directAddr, serverWrapKeys)
		if err != nil {
			log.Fatalf("[DIRECT] %v", err)
		}
		context.AfterFunc(ctx, func() { directWrapListener.Close() })
		log.Printf("   DIRECT (no-DTLS): %s", *listenDirect)

		go func() {
			for {
				pc, remoteAddr, acceptErr := directWrapListener.Accept()
				if acceptErr != nil {
					select {
					case <-ctx.Done():
						return
					default:
					}
					continue
				}
				wg.Add(1)
				go func(pc net.PacketConn, addr net.Addr) {
					defer wg.Done()
					c := &directConn{pc: pc, addr: addr}
					defer c.Close()
					handleConn(ctx, c, wgEndpoint, wgDev, keys)
				}(pc, remoteAddr)
			}
		}()
	}

	// Raw-IP (без WireGuard) листенер — свой TUN/NAT/подсеть. Полностью
	// опционально: если флаг не передан, ничего не создаётся и не трогает
	// существующие WG-пути (56000/-listen-direct).
	if *listenRaw != "" {
		router, err := newRawRouter()
		if err != nil {
			log.Fatalf("[RAW] %v", err)
		}

		rawAddr, err := net.ResolveUDPAddr("udp", *listenRaw)
		if err != nil {
			log.Fatalf("[RAW] адрес: %v", err)
		}
		rawWrapListener, err := listenWrapped(rawAddr, serverWrapKeys)
		if err != nil {
			log.Fatalf("[RAW] %v", err)
		}
		context.AfterFunc(ctx, func() { rawWrapListener.Close() })
		log.Printf("   RAW (без WireGuard, без DTLS): %s", *listenRaw)

		go func() {
			for {
				pc, remoteAddr, acceptErr := rawWrapListener.Accept()
				if acceptErr != nil {
					select {
					case <-ctx.Done():
						return
					default:
					}
					continue
				}
				wg.Add(1)
				go func(pc net.PacketConn, addr net.Addr) {
					defer wg.Done()
					c := &directConn{pc: pc, addr: addr}
					defer c.Close()
					handleConnRaw(ctx, c, router)
				}(pc, remoteAddr)
			}
		}()
	}

	log.Println("[SERVER] Готов")

	for {
		dtlsConn, err := listener.Accept()
		if err != nil {
			select {
			case <-ctx.Done():
				wg.Wait()
				return
			default:
			}
			continue
		}
		wg.Add(1)
		go func(c net.Conn) {
			defer wg.Done()
			defer c.Close()
			handleConn(ctx, c, wgEndpoint, wgDev, keys)
		}(dtlsConn)
	}
}
