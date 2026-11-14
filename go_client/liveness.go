package main

// Проверка живости потока (воркера).
//
// Туннель — пучок потоков через разные relay VK, трафик раскидывается по
// ним по очереди. Поток может тихо умереть: VK закрыл allocation, сменилась
// сеть, отвалился NAT. Раньше клиент этого не замечал и продолжал слать в
// мёртвый поток его долю трафика — часть сайтов висела до переподключения.
//
// Теперь сервер отвечает на каждый keepalive одним байтом 0xFF
// (server/connections.go, answerKeepalive). Если ответ хоть раз пришёл,
// сервер это умеет, и долгая тишина (ни данных, ни ответов) означает, что
// поток мёртв: снимаем его и переподключаем, остальные работают дальше.
// Со старым сервером ответов нет вовсе — проверка не включается и ничего
// не ломает.

import (
	"sync/atomic"
	"time"
)

const (
	// livenessTimeout — сколько тишины считается смертью потока: три
	// пропущенных ответа на keepalive (keepaliveInterval = 5 с).
	livenessTimeout = 15 * time.Second
	livenessTick    = time.Second
)

type sessionLiveness struct {
	lastRx   atomic.Int64 // unix ms последнего пакета от сервера (данные или ответ)
	pongSeen atomic.Bool  // сервер умеет отвечать на keepalive
}

func newSessionLiveness(now time.Time) *sessionLiveness {
	l := &sessionLiveness{}
	l.lastRx.Store(now.UnixMilli())
	return l
}

// noteRx — пришёл любой пакет от сервера.
func (l *sessionLiveness) noteRx(now time.Time) { l.lastRx.Store(now.UnixMilli()) }

// notePong — пришёл ответ на keepalive.
func (l *sessionLiveness) notePong(now time.Time) {
	l.pongSeen.Store(true)
	l.noteRx(now)
}

// silentFor — сколько поток молчит, и мёртв ли он по правилам выше.
func (l *sessionLiveness) silentFor(now time.Time) (time.Duration, bool) {
	silent := now.Sub(time.UnixMilli(l.lastRx.Load()))
	return silent, l.pongSeen.Load() && silent > livenessTimeout
}
