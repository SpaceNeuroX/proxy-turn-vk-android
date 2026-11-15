package main

// Проверка живости потока (воркера) и расписание keepalive.
//
// Туннель — пучок потоков через разные relay VK, трафик раскидывается по
// ним по очереди. Поток может тихо умереть: VK закрыл allocation, сменилась
// сеть, отвалился NAT. Раньше клиент этого не замечал и продолжал слать в
// мёртвый поток его долю трафика — часть сайтов висела до переподключения.
//
// Сервер отвечает на каждый keepalive одним байтом 0xFF
// (server/connections.go, answerKeepalive). Если ответ хоть раз пришёл,
// сервер это умеет, и долгая тишина (ни данных, ни ответов) означает, что
// поток мёртв: снимаем его и переподключаем, остальные работают дальше.
// Со старым сервером ответов нет вовсе — проверка не включается.
//
// Экран выключен (приложение присылает SCREEN_OFF): keepalive реже, чтобы
// радио спало, и порог смерти длиннее. Включение экрана и выход процесса из
// заморозки (Doze, сон устройства) — сразу keepalive и короткое окно на
// ответ: живой поток отвечает за доли секунды, мёртвый снимается быстро,
// а время, пока процесс стоял, тишиной не считается.

import (
	"sync/atomic"
	"time"
)

const (
	livenessTick = time.Second

	// Экран включён: keepalive раз в 5 с, смерть — 15 с тишины.
	keepaliveInterval = 5 * time.Second
	livenessTimeout   = 15 * time.Second

	// Экран выключен: keepalive раз в 20 с, смерть — 60 с тишины. NAT
	// операторов и TURN-разрешения (300 с) это переживают; сервер
	// закрывает простаивающую Raw-сессию через 90 с — с запасом.
	keepaliveIntervalSleep = 20 * time.Second
	livenessTimeoutSleep   = 60 * time.Second

	// livenessWakeWindow — сколько ждать ответа после пробуждения.
	livenessWakeWindow = 5 * time.Second

	// livenessFreezeGap — если между двумя тиками прошло больше, процесс
	// был заморожен (сон телефона, Doze, заморозка фоновых приложений).
	livenessFreezeGap = 5 * time.Second
)

// lowPowerMode — экран выключен (команды SCREEN_OFF/SCREEN_ON из stdin).
var lowPowerMode atomic.Bool

// serverAnswersKeepalive — сервер хоть раз ответил на keepalive (любому
// потоку). Признак общий на весь клиент: один процесс — один сервер. Если
// держать его в каждом потоке отдельно, поток, переподключившийся прямо
// перед падением сервера, не успевал получить первый ответ, проверка для
// него не включалась, и мёртвые потоки висели как живые.
var serverAnswersKeepalive atomic.Bool

type sessionLiveness struct {
	lastRx   atomic.Int64 // unix ms последнего пакета от сервера (данные или ответ)
	pongSeen *atomic.Bool // сервер умеет отвечать на keepalive (общий признак)
}

func newSessionLiveness(now time.Time) *sessionLiveness {
	return newSessionLivenessShared(now, &serverAnswersKeepalive)
}

// newSessionLivenessShared — с явным общим признаком (для тестов).
func newSessionLivenessShared(now time.Time, pongSeen *atomic.Bool) *sessionLiveness {
	l := &sessionLiveness{pongSeen: pongSeen}
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

// grace оставляет потоку ровно livenessWakeWindow на ответ при текущем
// пороге timeout (если тишина и так короче — ничего не меняет).
func (l *sessionLiveness) grace(now time.Time, timeout time.Duration) {
	floor := now.Add(-(timeout - livenessWakeWindow)).UnixMilli()
	for {
		cur := l.lastRx.Load()
		if cur >= floor || l.lastRx.CompareAndSwap(cur, floor) {
			return
		}
	}
}

// silentFor — сколько поток молчит, и мёртв ли он при пороге timeout.
func (l *sessionLiveness) silentFor(now time.Time, timeout time.Duration) (time.Duration, bool) {
	silent := now.Sub(time.UnixMilli(l.lastRx.Load()))
	return silent, l.pongSeen.Load() && silent > timeout
}

// livenessPlanner решает на каждом тике: слать ли keepalive и не умер ли
// поток. Отдельно от сети, чтобы все переходы проверялись тестами.
type livenessPlanner struct {
	l             *sessionLiveness
	lastTick      time.Time
	nextKeepalive time.Time
	wasSleeping   bool
}

func newLivenessPlanner(l *sessionLiveness, now time.Time) *livenessPlanner {
	return &livenessPlanner{l: l, lastTick: now, nextKeepalive: now.Add(keepaliveInterval)}
}

type livenessStep struct {
	sendKeepalive bool
	dead          bool
	silent        time.Duration
	woke          bool // процесс вышел из заморозки или включился экран
}

// step — один тик. Время берётся по настенным часам, как и lastRx: они
// идут и во время сна устройства, поэтому провал виден и при заморозке
// процесса, и при suspend.
func (p *livenessPlanner) step(now time.Time, sleeping bool) livenessStep {
	timeout, interval := livenessTimeout, keepaliveInterval
	if sleeping {
		timeout, interval = livenessTimeoutSleep, keepaliveIntervalSleep
	}

	var st livenessStep
	froze := now.Sub(p.lastTick) > livenessFreezeGap
	screenOn := p.wasSleeping && !sleeping
	p.lastTick = now
	p.wasSleeping = sleeping

	if froze || screenOn {
		// Пока процесс стоял или радио спало, ответы прийти не могли —
		// это не смерть потока. Проверяем сразу и коротко.
		p.l.grace(now, timeout)
		p.nextKeepalive = now
		st.woke = true
	}

	st.silent, st.dead = p.l.silentFor(now, timeout)
	if st.dead {
		return st
	}
	if !now.Before(p.nextKeepalive) {
		st.sendKeepalive = true
		p.nextKeepalive = now.Add(interval)
	}
	return st
}
