package main

import (
	"fmt"
	"log"
	"os"
	"os/exec"
)

// ==================== NAT ====================

func setupFullConeNAT(wgIface string) error {
	log.Println("[NAT] ══════════════════════════════════════")

	os.WriteFile("/proc/sys/net/ipv4/ip_forward", []byte("1"), 0644)

	extIface := getDefaultInterface()
	log.Printf("[NAT] Внешний: %s", extIface)

	switch {
	case commandExists("iptables"):
		for i := 0; i < 5; i++ {
			exec.Command("iptables", "-t", "nat", "-D", "POSTROUTING", "-s", wgServerCIDR, "-o", extIface, "-m", "comment", "--comment", "GLEBSBP_MANAGED", "-j", "MASQUERADE").Run()
		}
		exec.Command("iptables", "-t", "nat", "-I", "POSTROUTING", "1", "-s", wgServerCIDR, "-o", extIface, "-m", "comment", "--comment", "GLEBSBP_MANAGED", "-j", "MASQUERADE").Run()
		natType = "MASQUERADE iptables ✅"
		setupForwardRules(wgIface)
	case commandExists("nft"):
		setupNftNAT(extIface)
		natType = "MASQUERADE nft ✅"
		setupForwardRules(wgIface)
	default:
		natType = "NAT не настроен: нет iptables/nft"
		log.Printf("[NAT] WARNING: %s", natType)
	}

	log.Printf("[NAT] Режим: %s", natType)
	log.Println("[NAT] ══════════════════════════════════════")
	return nil
}

func setupNftNAT(extIface string) {
	exec.Command("nft", "add", "table", "ip", "glebsbp").Run()
	exec.Command("nft", "add", "chain", "ip", "glebsbp", "postrouting", "{ type nat hook postrouting priority 100; }").Run()
	exec.Command("nft", "add", "rule", "ip", "glebsbp", "postrouting", "ip", "saddr", wgServerCIDR, "oifname", extIface, "masquerade").Run()
}

// setupRawNAT — NAT для raw-IP (без WireGuard) TUN-интерфейса. Полностью
// отдельные таблицы/цепочки/CIDR от WG-пути (setupFullConeNAT/setupNftNAT
// выше не трогаются), setupForwardRules переиспользуется как есть — она уже
// параметризована только именем интерфейса.
func setupRawNAT(rawIface string) error {
	extIface := getDefaultInterface()
	log.Printf("[RAW-NAT] Внешний: %s", extIface)

	switch {
	case commandExists("iptables"):
		for i := 0; i < 5; i++ {
			exec.Command("iptables", "-t", "nat", "-D", "POSTROUTING", "-s", rawServerCIDR, "-o", extIface, "-m", "comment", "--comment", "GLEBSBP_RAW_MANAGED", "-j", "MASQUERADE").Run()
		}
		exec.Command("iptables", "-t", "nat", "-I", "POSTROUTING", "1", "-s", rawServerCIDR, "-o", extIface, "-m", "comment", "--comment", "GLEBSBP_RAW_MANAGED", "-j", "MASQUERADE").Run()
		setupForwardRules(rawIface)
		setupRawMSSClamping()
	case commandExists("nft"):
		exec.Command("nft", "add", "table", "ip", "glebsbpraw").Run()
		exec.Command("nft", "add", "chain", "ip", "glebsbpraw", "postrouting", "{ type nat hook postrouting priority 100; }").Run()
		exec.Command("nft", "add", "rule", "ip", "glebsbpraw", "postrouting", "ip", "saddr", rawServerCIDR, "oifname", extIface, "masquerade").Run()
		setupForwardRules(rawIface)
		setupRawMSSClamping()
	default:
		return fmt.Errorf("нет iptables/nft для NAT raw-интерфейса")
	}
	return nil
}

// setupRawMSSClamping чинит PMTU-чёрную-дыру для TCP через raw-подсеть:
// без этого клиенты за строгими firewall (блокирующими ICMP Fragmentation
// Needed) молча теряют большие TCP-сегменты вместо получения корректной
// фрагментации/ретрансмита с меньшим MSS. deploy.sh делает то же самое для
// WG-подсети (fw_add_mss_clamping) — raw-подсеть настраивается здесь, т.к.
// её NAT/forward поднимается сервером напрямую, а не через deploy.sh.
func setupRawMSSClamping() {
	if commandExists("iptables") {
		for _, dir := range []string{"-s", "-d"} {
			exec.Command("iptables", "-t", "mangle", "-D", "FORWARD", dir, rawServerCIDR,
				"-p", "tcp", "-m", "tcp", "--tcp-flags", "SYN,RST", "SYN",
				"-m", "comment", "--comment", "GLEBSBP_RAW_MANAGED",
				"-j", "TCPMSS", "--clamp-mss-to-pmtu").Run()
			exec.Command("iptables", "-t", "mangle", "-I", "FORWARD", dir, rawServerCIDR,
				"-p", "tcp", "-m", "tcp", "--tcp-flags", "SYN,RST", "SYN",
				"-m", "comment", "--comment", "GLEBSBP_RAW_MANAGED",
				"-j", "TCPMSS", "--clamp-mss-to-pmtu").Run()
		}
		return
	}
	if commandExists("nft") {
		exec.Command("nft", "add", "table", "inet", "glebsbpraw_mangle").Run()
		exec.Command("nft", "add", "chain", "inet", "glebsbpraw_mangle", "forward",
			"{ type filter hook forward priority -150; policy accept; }").Run()
		exec.Command("nft", "add", "rule", "inet", "glebsbpraw_mangle", "forward",
			"ip", "saddr", rawServerCIDR, "tcp", "flags", "syn",
			"tcp", "option", "maxseg", "size", "set", "rt", "mtu").Run()
		exec.Command("nft", "add", "rule", "inet", "glebsbpraw_mangle", "forward",
			"ip", "daddr", rawServerCIDR, "tcp", "flags", "syn",
			"tcp", "option", "maxseg", "size", "set", "rt", "mtu").Run()
	}
}

func setupForwardRules(wgIface string) {
	if commandExists("iptables") {
		for i := 0; i < 5; i++ {
			exec.Command("iptables", "-D", "FORWARD", "-i", wgIface, "-m", "comment", "--comment", "GLEBSBP_MANAGED", "-j", "ACCEPT").Run()
			exec.Command("iptables", "-D", "FORWARD", "-o", wgIface, "-m", "comment", "--comment", "GLEBSBP_MANAGED", "-j", "ACCEPT").Run()
		}
		exec.Command("iptables", "-A", "FORWARD", "-i", wgIface, "-m", "comment", "--comment", "GLEBSBP_MANAGED", "-j", "ACCEPT").Run()
		exec.Command("iptables", "-A", "FORWARD", "-o", wgIface, "-m", "comment", "--comment", "GLEBSBP_MANAGED", "-j", "ACCEPT").Run()
		return
	}
	if commandExists("nft") {
		exec.Command("nft", "add", "table", "inet", "glebsbp").Run()
		exec.Command("nft", "add", "chain", "inet", "glebsbp", "forward", "{ type filter hook forward priority 0; policy accept; }").Run()
		exec.Command("nft", "add", "rule", "inet", "glebsbp", "forward", "iifname", wgIface, "accept").Run()
		exec.Command("nft", "add", "rule", "inet", "glebsbp", "forward", "oifname", wgIface, "accept").Run()
	}
}
