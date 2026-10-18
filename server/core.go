package main

const (
	wgIfaceName           = "glebsbp0"
	wgServerAddr          = "10.66.66.1"
	wgServerCIDR          = wgServerAddr + "/16"
	defaultInternalWGPort = 56001
	wgMTU                 = 1280
	keepalive             = 25

	// Raw-IP роутер (без WireGuard) — отдельный TUN/подсеть/NAT, полностью
	// параллельно WG-пути. Подсеть намеренно не пересекается с wgServerCIDR.
	rawIfaceName  = "glebsbpraw0"
	rawServerAddr = "10.70.66.1"
	rawServerCIDR = rawServerAddr + "/16"
	// Raw-режим не несёт WG data header (~32 байта) — только RTP-obfs (12 байт
	// заголовок + 16 байт AEAD tag + до 60 байт padding в video-режиме) и TURN
	// ChannelData/Send Indication framing (4-24 байта). Даже в худшем случае
	// (video-режим, максимальный padding) итоговый размер на проводе — около
	// 1420 байт с MTU=1300, что укладывается в стандартный Ethernet MTU 1500
	// без фрагментации.
	rawMTU = 1300
)

var dns = "8.8.8.8"
