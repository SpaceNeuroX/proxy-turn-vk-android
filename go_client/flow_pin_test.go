package main

import (
	"encoding/binary"
	"testing"
)

func ipv4Packet(proto byte, src, dst [4]byte, sport, dport uint16, size int) []byte {
	pkt := make([]byte, size)
	pkt[0] = 0x45
	binary.BigEndian.PutUint16(pkt[2:4], uint16(size))
	pkt[8] = 64
	pkt[9] = proto
	copy(pkt[12:16], src[:])
	copy(pkt[16:20], dst[:])
	binary.BigEndian.PutUint16(pkt[20:22], sport)
	binary.BigEndian.PutUint16(pkt[22:24], dport)
	return pkt
}

func ipv6UDPPacket(sport, dport uint16, size int) []byte {
	pkt := make([]byte, size)
	pkt[0] = 0x60
	pkt[6] = 17
	pkt[8], pkt[24] = 0xfd, 0xfd
	pkt[23], pkt[39] = 1, 2
	binary.BigEndian.PutUint16(pkt[40:42], sport)
	binary.BigEndian.PutUint16(pkt[42:44], dport)
	return pkt
}

func newTestWorkers(n int) []*WorkerSlot {
	ws := make([]*WorkerSlot, n)
	for i := range ws {
		ws[i] = &WorkerSlot{ID: i, SendCh: make(chan []byte, 8), PrioCh: make(chan []byte, 4)}
	}
	return ws
}

func drain(ws []*WorkerSlot) {
	for _, w := range ws {
		for len(w.PrioCh) > 0 {
			<-w.PrioCh
		}
		for len(w.SendCh) > 0 {
			<-w.SendCh
		}
	}
}

func workerHolding(ws []*WorkerSlot) *WorkerSlot {
	for _, w := range ws {
		if len(w.PrioCh)+len(w.SendCh) > 0 {
			return w
		}
	}
	return nil
}

var (
	ipA = [4]byte{10, 70, 0, 2}
	ipB = [4]byte{8, 8, 8, 8}
)

func TestParseUDPFlowKey(t *testing.T) {
	k, ok := parseUDPFlowKey(ipv4Packet(17, ipA, ipB, 5000, 53, 60))
	if !ok || k.sport != 5000 || k.dport != 53 || k.src[0] != 10 || k.dst[0] != 8 {
		t.Fatalf("ipv4 udp: ok=%v key=%+v", ok, k)
	}
	if _, ok := parseUDPFlowKey(ipv4Packet(6, ipA, ipB, 5000, 443, 60)); ok {
		t.Fatal("TCP must not be pinned")
	}
	frag := ipv4Packet(17, ipA, ipB, 5000, 53, 60)
	frag[7] = 10 // fragment offset != 0: no UDP header here
	if _, ok := parseUDPFlowKey(frag); ok {
		t.Fatal("non-first fragment must not be pinned")
	}
	if _, ok := parseUDPFlowKey(ipv4Packet(17, ipA, ipB, 1, 2, 24)[:22]); ok {
		t.Fatal("truncated packet must not be pinned")
	}
	k6, ok := parseUDPFlowKey(ipv6UDPPacket(4000, 3478, 80))
	if !ok || k6.sport != 4000 || k6.dport != 3478 || k6.src[0] != 0xfd {
		t.Fatalf("ipv6 udp: ok=%v key=%+v", ok, k6)
	}
	if _, ok := parseUDPFlowKey(nil); ok {
		t.Fatal("empty packet")
	}
}

func TestFlowPinnerKeepsFlowOnOneWorker(t *testing.T) {
	ws := newTestWorkers(4)
	var p flowPinner
	game := ipv4Packet(17, ipA, ipB, 40000, 27015, 120)

	if !p.send(ws, game, 1) {
		t.Fatal("small UDP must be pinned")
	}
	first := workerHolding(ws)
	if first == nil || len(first.PrioCh) != 1 {
		t.Fatal("pinned packet must go to the priority channel")
	}
	for i := 0; i < 20; i++ {
		drain(ws)
		if !p.send(ws, game, int64(2+i)) {
			t.Fatal("send failed")
		}
		if got := workerHolding(ws); got != first {
			t.Fatalf("packet %d went to worker %d, flow pinned to %d", i, got.ID, first.ID)
		}
	}
}

func TestFlowPinnerSpreadsNewFlows(t *testing.T) {
	ws := newTestWorkers(4)
	var p flowPinner
	used := map[int]bool{}
	for port := uint16(1); port <= 4; port++ {
		drain(ws)
		if !p.send(ws, ipv4Packet(17, ipA, ipB, port, 9999, 100), 1) {
			t.Fatal("send failed")
		}
		used[workerHolding(ws).ID] = true
	}
	if len(used) != 4 {
		t.Fatalf("4 idle workers, 4 new flows: used workers %v", used)
	}
}

func TestFlowPinnerSkipsLargeAndTCP(t *testing.T) {
	ws := newTestWorkers(2)
	var p flowPinner
	if p.send(ws, ipv4Packet(17, ipA, ipB, 1, 443, pinnedUDPMax+1), 1) {
		t.Fatal("large UDP (bulk/QUIC) must use chunk dispatch")
	}
	if p.send(ws, ipv4Packet(6, ipA, ipB, 1, 443, 100), 1) {
		t.Fatal("TCP must use chunk dispatch")
	}
	if p.send(nil, ipv4Packet(17, ipA, ipB, 1, 53, 60), 1) {
		t.Fatal("no workers")
	}
}

func TestFlowPinnerRepinsWhenWorkerGone(t *testing.T) {
	ws := newTestWorkers(3)
	var p flowPinner
	pkt := ipv4Packet(17, ipA, ipB, 1234, 53, 80)
	p.send(ws, pkt, 1)
	gone := workerHolding(ws)
	drain(ws)

	var rest []*WorkerSlot
	for _, w := range ws {
		if w != gone {
			rest = append(rest, w)
		}
	}
	if !p.send(rest, pkt, 2) {
		t.Fatal("send after worker removal failed")
	}
	if got := workerHolding(rest); got == nil {
		t.Fatal("flow was not re-pinned to a live worker")
	}
	if len(gone.PrioCh)+len(gone.SendCh) != 0 {
		t.Fatal("packet sent to an unregistered worker")
	}
}

func TestFlowPinnerMovesOffOverloadedWorker(t *testing.T) {
	ws := newTestWorkers(2)
	var p flowPinner
	pkt := ipv4Packet(17, ipA, ipB, 1, 53, 80)
	p.send(ws, pkt, 1)
	pinned := workerHolding(ws)
	for len(pinned.PrioCh) < cap(pinned.PrioCh) {
		pinned.PrioCh <- pkt
	}
	for len(pinned.SendCh) < cap(pinned.SendCh) {
		pinned.SendCh <- pkt
	}
	if !p.send(ws, pkt, 2) {
		t.Fatal("must move to the free worker instead of dropping")
	}
	other := ws[0]
	if other == pinned {
		other = ws[1]
	}
	if len(other.PrioCh) != 1 {
		t.Fatal("packet did not move to the free worker")
	}
}

func TestFlowPinnerForgetsIdleFlows(t *testing.T) {
	ws := newTestWorkers(2)
	var p flowPinner
	p.send(ws, ipv4Packet(17, ipA, ipB, 1, 53, 80), 0)
	p.send(ws, ipv4Packet(17, ipA, ipB, 2, 53, 80), pinnedFlowIdleMS+pinnedSweepEveryMS+1)
	if len(p.flows) != 1 {
		t.Fatalf("idle flow not swept: %d flows", len(p.flows))
	}
}
