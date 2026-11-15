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
	// livenessFreezeGap — если между двумя тиками прошло больше, процесс
	// был заморожен (сон телефона, Doze, заморозка фоновых приложений).
	// Пока он стоял, ответы на keepalive прийти не могли, и это не смерть
	// потока: даём ему новое окно, а не снимаем все потоки разом.
	livenessFreezeGap = 5 * time.Second
)

type sessionLiveness struct {
	lastRx   atomic.Int64 // unix ms последнего пакета от сервера (данные или ответ)
	lastTick atomic.Int64 // unix ms последнего тика проверки
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

// tick вызывается на каждом тике проверки. true — между тиками был
// провал (процесс стоял): окно тишины начинается заново с этого момента.
// Время — по настенным часам, как и lastRx: они идут и во время сна
// устройства, поэтому провал виден и при заморозке, и при suspend.
func (l *sessionLiveness) tick(now time.Time) bool {
	nowMs := now.UnixMilli()
	prev := l.lastTick.Swap(nowMs)
	if prev == 0 || time.Duration(nowMs-prev)*time.Millisecond <= livenessFreezeGap {
		return false
	}
	l.lastRx.Store(nowMs)
	return true
}

// silentFor — сколько поток молчит, и мёртв ли он по правилам выше.
func (l *sessionLiveness) silentFor(now time.Time) (time.Duration, bool) {
	silent := now.Sub(time.UnixMilli(l.lastRx.Load()))
	return silent, l.pongSeen.Load() && silent > livenessTimeout
}
