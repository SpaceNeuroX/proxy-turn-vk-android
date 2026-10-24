package main

// Закрепление мелких UDP-потоков за одним воркером (только -mode rawtun:
// только там диспетчер видит сырые IP-пакеты; в WG-режиме весь трафик —
// один зашифрованный UDP-поток локального WireGuard, внутрь не заглянуть).
//
// Игры, голос, звонки в мессенджерах, DNS шлют небольшие UDP-пакеты, которым
// важны задержка и порядок. Chunk-раздача (см. readLoop) гоняет их между
// разными TURN-relay с разным latency → пакеты одного потока приходят не по
// порядку и с джиттером. Поэтому такой поток закрепляется за одним воркером
// по 5-tuple и идёт через его приоритетный канал, минуя очередь объёмных
// данных. Крупные UDP-пакеты (QUIC-загрузки и т.п.) и TCP по-прежнему
// раскидываются chunk'ами для максимальной скорости.

const (
	// pinnedUDPMax — UDP-пакеты до этого размера (байт, с IP-заголовком)
	// закрепляются за воркером.
	pinnedUDPMax = 600
	// pinnedFlowIdleMS — через сколько тишины поток забывается.
	pinnedFlowIdleMS = 30_000
	// pinnedFlowMax — потолок таблицы потоков.
	pinnedFlowMax = 4096
	// pinnedSweepEveryMS — как часто чистить устаревшие потоки.
	pinnedSweepEveryMS = 5_000
)

type udpFlowKey struct {
	src, dst     [16]byte
	sport, dport uint16
}

type pinnedFlow struct {
	worker   *WorkerSlot
	lastSeen int64
}

type flowPinner struct {
	flows     map[udpFlowKey]*pinnedFlow
	lastSweep int64
	rr        int
}

// parseUDPFlowKey достаёт 5-tuple из IPv4/IPv6 UDP-пакета. Фрагменты IPv4
// без UDP-заголовка и IPv6 с extension headers не закрепляются.
func parseUDPFlowKey(pkt []byte) (udpFlowKey, bool) {
	var k udpFlowKey
	if len(pkt) < 1 {
		return k, false
	}
	switch pkt[0] >> 4 {
	case 4:
		if len(pkt) < 20 {
			return k, false
		}
		ihl := int(pkt[0]&0x0f) * 4
		if ihl < 20 || pkt[9] != 17 || len(pkt) < ihl+8 {
			return k, false
		}
		if fragOffset := (uint16(pkt[6])&0x1f)<<8 | uint16(pkt[7]); fragOffset != 0 {
			return k, false
		}
		copy(k.src[:4], pkt[12:16])
		copy(k.dst[:4], pkt[16:20])
		k.sport = uint16(pkt[ihl])<<8 | uint16(pkt[ihl+1])
		k.dport = uint16(pkt[ihl+2])<<8 | uint16(pkt[ihl+3])
		return k, true
	case 6:
		if len(pkt) < 48 || pkt[6] != 17 {
			return k, false
		}
		copy(k.src[:], pkt[8:24])
		copy(k.dst[:], pkt[24:40])
		k.sport = uint16(pkt[40])<<8 | uint16(pkt[41])
		k.dport = uint16(pkt[42])<<8 | uint16(pkt[43])
		return k, true
	}
	return k, false
}

// send пытается отправить мелкий UDP-пакет через закреплённый за его потоком
// воркер. false — пакет не мелкий UDP или все подходящие очереди заняты;
// тогда вызывающий отправляет его обычным путём. Вызывается под d.mu.
func (p *flowPinner) send(workers []*WorkerSlot, pkt []byte, now int64) bool {
	if len(pkt) > pinnedUDPMax || len(workers) == 0 {
		return false
	}
	key, ok := parseUDPFlowKey(pkt)
	if !ok {
		return false
	}
	if p.flows == nil {
		p.flows = make(map[udpFlowKey]*pinnedFlow)
	}
	if now-p.lastSweep >= pinnedSweepEveryMS {
		p.sweep(now)
	}

	f := p.flows[key]
	if f != nil && !containsWorker(workers, f.worker) {
		f = nil // воркер переподключился — перезакрепим
	}
	if f == nil {
		if len(p.flows) >= pinnedFlowMax {
			p.sweep(now)
			if len(p.flows) >= pinnedFlowMax {
				p.flows = make(map[udpFlowKey]*pinnedFlow)
			}
		}
		f = &pinnedFlow{worker: p.leastLoaded(workers)}
		p.flows[key] = f
	}
	f.lastSeen = now

	if trySendWorker(f.worker, pkt) {
		return true
	}
	// Закреплённый воркер захлебнулся — переезжаем на самый свободный.
	// Один переезд даёт максимум один переупорядоченный пакет, а не потерю.
	if alt := p.leastLoaded(workers); alt != f.worker {
		f.worker = alt
		return trySendWorker(alt, pkt)
	}
	return false
}

func trySendWorker(w *WorkerSlot, pkt []byte) bool {
	select {
	case w.PrioCh <- pkt:
		return true
	default:
	}
	select {
	case w.SendCh <- pkt:
		return true
	default:
		return false
	}
}

// leastLoaded — воркер с самой короткой очередью; при равенстве новые потоки
// раскладываются по кругу, а не все на первый.
func (p *flowPinner) leastLoaded(workers []*WorkerSlot) *WorkerSlot {
	n := len(workers)
	start := p.rr % n
	p.rr++
	best := workers[start]
	bestLoad := len(best.SendCh) + len(best.PrioCh)
	for i := 1; i < n && bestLoad > 0; i++ {
		w := workers[(start+i)%n]
		if load := len(w.SendCh) + len(w.PrioCh); load < bestLoad {
			best, bestLoad = w, load
		}
	}
	return best
}

func (p *flowPinner) sweep(now int64) {
	p.lastSweep = now
	for k, f := range p.flows {
		if now-f.lastSeen > pinnedFlowIdleMS {
			delete(p.flows, k)
		}
	}
}

func containsWorker(workers []*WorkerSlot, w *WorkerSlot) bool {
	for _, x := range workers {
		if x == w {
			return true
		}
	}
	return false
}
