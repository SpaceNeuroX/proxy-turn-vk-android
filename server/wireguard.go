package main

import (
	"fmt"
	"log"
	"strings"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/ipc"
	"golang.zx2c4.com/wireguard/tun"
)

// ==================== WireGuard ====================

type loopbackOnlyBind struct {
	conn.Bind
}

func (b *loopbackOnlyBind) Open(port uint16) ([]conn.ReceiveFunc, uint16, error) {
	receivers, actualPort, err := b.Bind.Open(port)
	if err != nil {
		return nil, 0, err
	}
	wrapped := make([]conn.ReceiveFunc, len(receivers))
	for i, receive := range receivers {
		inner := receive
		wrapped[i] = func(packets [][]byte, sizes []int, endpoints []conn.Endpoint) (int, error) {
			for {
				n, receiveErr := inner(packets, sizes, endpoints)
				if receiveErr != nil {
					return 0, receiveErr
				}
				kept := 0
				for j := 0; j < n; j++ {
					if endpoints[j] == nil || !endpoints[j].DstIP().IsLoopback() {
						continue
					}
					if kept != j {
						packets[kept] = packets[j]
						sizes[kept] = sizes[j]
						endpoints[kept] = endpoints[j]
					}
					kept++
				}
				if kept > 0 {
					return kept, nil
				}
			}
		}
	}
	return wrapped, actualPort, nil
}

func (b *loopbackOnlyBind) Send(bufs [][]byte, endpoint conn.Endpoint) error {
	if endpoint == nil || !endpoint.DstIP().IsLoopback() {
		return fmt.Errorf("WireGuard endpoint outside loopback rejected")
	}
	return b.Bind.Send(bufs, endpoint)
}

func startUserspaceWG(keys *wgKeys, wgPort int) (*device.Device, error) {
	runCmdSilent("ip", "link", "del", wgIfaceName)
	time.Sleep(100 * time.Millisecond)

	tunDev, err := tun.CreateTUN(wgIfaceName, wgMTU)
	if err != nil {
		optimizedErr := err
		tunFile, fallbackErr := createBasicTUNFile(wgIfaceName, true)
		if fallbackErr != nil {
			return nil, fmt.Errorf("CreateTUN: %v; fallback: %w", optimizedErr, fallbackErr)
		}
		tunDev, fallbackErr = tun.CreateTUNFromFile(tunFile, wgMTU)
		if fallbackErr != nil {
			tunFile.Close()
			return nil, fmt.Errorf("CreateTUN: %v; fallback init: %w", optimizedErr, fallbackErr)
		}
		log.Printf("[WG] Совместимый TUN без VNET_HDR: %v", optimizedErr)
	}

	ifaceName, err := tunDev.Name()
	if err != nil {
		tunDev.Close()
		return nil, fmt.Errorf("TUN name: %w", err)
	}

	logger := device.NewLogger(device.LogLevelError, "[WG] ")
	bind := &loopbackOnlyBind{Bind: conn.NewDefaultBind()}
	dev := device.NewDevice(tunDev, bind, logger)

	serverPrivHex, _ := b64ToHex(keys.serverPrivate)

	if err := dev.IpcSet(fmt.Sprintf(
		"private_key=%s\nlisten_port=%d\n",
		serverPrivHex, wgPort,
	)); err != nil {
		dev.Close()
		return nil, fmt.Errorf("IpcSet: %w", err)
	}

	for _, d := range db.Devices {
		pubHex, _ := b64ToHex(d.PubKey)
		if pubHex != "" {
			dev.IpcSet(fmt.Sprintf("public_key=%s\nallowed_ip=%s/32\n", pubHex, d.IP))
		}
	}

	if err := dev.Up(); err != nil {
		dev.Close()
		return nil, fmt.Errorf("device.Up: %w", err)
	}

	if err := configureInterface(ifaceName); err != nil {
		dev.Close()
		return nil, err
	}

	if err := setupFullConeNAT(ifaceName); err != nil {
		dev.Close()
		return nil, err
	}

	go func() {
		uapiFile, err := ipc.UAPIOpen(ifaceName)
		if err != nil {
			return
		}
		uapi, err := ipc.UAPIListen(ifaceName, uapiFile)
		if err != nil {
			return
		}
		defer uapi.Close()
		for {
			c, err := uapi.Accept()
			if err != nil {
				return
			}
			go dev.IpcHandle(c)
		}
	}()

	log.Printf("[WG] Запущен на порту %d", wgPort)
	return dev, nil
}

func configureInterface(ifaceName string) error {
	for _, cmd := range [][]string{
		{"ip", "addr", "add", wgServerCIDR, "dev", ifaceName},
		{"ip", "link", "set", "mtu", fmt.Sprintf("%d", wgMTU), "dev", ifaceName},
		{"ip", "link", "set", ifaceName, "up"},
	} {
		out, err := runCmd(cmd[0], cmd[1:]...)
		if err != nil && !strings.Contains(out, "File exists") {
			return fmt.Errorf("%s: %s", strings.Join(cmd, " "), out)
		}
	}
	return nil
}

func buildClientConfig(serverPublic, clientPrivate, clientIP, clientPort string) string {
	return fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = %s/32
DNS = %s
MTU = %d

[Peer]
PublicKey = %s
AllowedIPs = 0.0.0.0/0
Endpoint = 127.0.0.1:%s
PersistentKeepalive = %d`,
		clientPrivate, clientIP, dns, wgMTU,
		serverPublic, clientPort, keepalive,
	)
}
