package main

import (
	"sync/atomic"
	"testing"
	"time"
)

// sim — поток с планировщиком на «виртуальных» часах: тик раз в секунду.
type sim struct {
	t    *testing.T
	now  time.Time
	l    *sessionLiveness
	p    *livenessPlanner
	sent []time.Duration // когда ушли keepalive (от начала)
	t0   time.Time
}

func newSim(t *testing.T) *sim {
	return newSimShared(t, new(atomic.Bool))
}

func newSimShared(t *testing.T, shared *atomic.Bool) *sim {
	t0 := time.Unix(1_000_000, 0)
	l := newSessionLivenessShared(t0, shared)
	return &sim{t: t, now: t0, t0: t0, l: l, p: newLivenessPlanner(l, t0)}
}

// run гоняет тики до d. answer — отвечает ли сервер на keepalive (сразу).
// Возвращает момент смерти потока (0 — жив).
func (s *sim) run(d time.Duration, sleeping, answer bool) time.Duration {
	end := s.now.Add(d)
	for s.now.Before(end) {
		s.now = s.now.Add(livenessTick)
		st := s.p.step(s.now, sleeping)
		if st.dead {
			return s.now.Sub(s.t0)
		}
		if st.sendKeepalive {
			s.sent = append(s.sent, s.now.Sub(s.t0))
			if answer {
				s.l.notePong(s.now)
			}
		}
	}
	return 0
}

// freeze — процесс стоял d (ни одного тика), потом первый тик.
func (s *sim) freeze(d time.Duration) { s.now = s.now.Add(d) }

func (s *sim) keepalivesBetween(from, to time.Duration) int {
	n := 0
	for _, at := range s.sent {
		if at > from && at <= to {
			n++
		}
	}
	return n
}

func TestLivenessOldServerNeverKills(t *testing.T) {
	s := newSim(t)
	// Старый сервер не отвечает на keepalive ни разу — проверка выключена.
	if dead := s.run(10*time.Minute, false, false); dead != 0 {
		t.Fatalf("flow declared dead at %v without any pong", dead)
	}
}

func TestLivenessAwakeDetectsDeathIn15s(t *testing.T) {
	s := newSim(t)
	s.run(30*time.Second, false, true)
	if got := s.keepalivesBetween(0, 30*time.Second); got != 6 {
		t.Fatalf("awake: %d keepalives in 30 s, want 6 (every 5 s)", got)
	}
	start := s.now.Sub(s.t0)
	dead := s.run(time.Minute, false, false)
	if dead == 0 || dead-start > 16*time.Second {
		t.Fatalf("awake: death detected after %v, want ≤ 16 s", dead-start)
	}
}

func TestLivenessScreenOffKeepalivesRarelyAndTolerates60s(t *testing.T) {
	s := newSim(t)
	s.run(10*time.Second, false, true)
	start := s.now.Sub(s.t0)
	// Экран выключен, сервер отвечает: 2 минуты без смертей, keepalive раз в 20 с.
	if dead := s.run(2*time.Minute, true, true); dead != 0 {
		t.Fatalf("screen off with answering server: died at %v", dead)
	}
	if got := s.keepalivesBetween(start, start+2*time.Minute); got < 5 || got > 7 {
		t.Fatalf("screen off: %d keepalives in 2 min, want ~6 (every 20 s)", got)
	}
	// Сервер пропал, экран выключен: смерть не раньше 60 с и не позже ~61 с.
	from := s.now.Sub(s.t0)
	dead := s.run(3*time.Minute, true, false)
	if dead == 0 || dead-from < 45*time.Second || dead-from > 62*time.Second {
		t.Fatalf("screen off: death after %v of silence, want 45..62 s", dead-from)
	}
}

func TestLivenessScreenOnChecksImmediately(t *testing.T) {
	s := newSim(t)
	s.run(10*time.Second, false, true)
	s.run(15*time.Second, true, true) // последний ответ — до 15 с назад
	on := s.now.Sub(s.t0)
	// Экран включился: keepalive уходит сразу, живой поток отвечает — жив.
	if dead := s.run(30*time.Second, false, true); dead != 0 {
		t.Fatalf("live flow died after screen on at %v", dead)
	}
	if s.keepalivesBetween(on, on+time.Second) != 1 {
		t.Fatal("keepalive must be sent on the first tick after screen on")
	}
}

func TestLivenessScreenOnDeadFlowDroppedFast(t *testing.T) {
	s := newSim(t)
	s.run(10*time.Second, false, true)
	s.run(40*time.Second, true, false) // во сне ответы перестали приходить (< 60 с — ещё жив)
	on := s.now.Sub(s.t0)
	dead := s.run(30*time.Second, false, false)
	// Первый тик (+1 с) + окно на ответ (5 с) + тик, на котором оно вышло.
	if dead == 0 || dead-on > 7*time.Second {
		t.Fatalf("dead flow after screen on dropped at +%v, want ≤ 7 s", dead-on)
	}
}

func TestLivenessSurvivesProcessFreeze(t *testing.T) {
	s := newSim(t)
	s.run(10*time.Second, false, true)
	s.freeze(10 * time.Minute)
	// Первый тик после заморозки: не смерть, сразу keepalive.
	thaw := s.now.Sub(s.t0)
	if dead := s.run(30*time.Second, false, true); dead != 0 {
		t.Fatalf("live flow died after freeze at %v", dead)
	}
	if s.keepalivesBetween(thaw, thaw+time.Second) != 1 {
		t.Fatal("keepalive must be sent right after thaw")
	}
}

func TestLivenessFreezeDeadFlowDroppedFast(t *testing.T) {
	for _, sleeping := range []bool{false, true} {
		s := newSim(t)
		s.run(10*time.Second, sleeping, true)
		s.freeze(10 * time.Minute)
		thaw := s.now.Sub(s.t0)
		dead := s.run(time.Minute, sleeping, false)
		if dead == 0 || dead-thaw > 7*time.Second {
			t.Fatalf("sleeping=%v: flow dead during freeze dropped at +%v, want ≤ 7 s", sleeping, dead-thaw)
		}
	}
}

func TestLivenessDataKeepsAlive(t *testing.T) {
	s := newSim(t)
	s.run(10*time.Second, false, true)
	// Ответы на keepalive теряются, но данные идут — поток жив.
	for i := 0; i < 60; i++ {
		s.l.noteRx(s.now)
		if dead := s.run(3*time.Second, false, false); dead != 0 {
			t.Fatalf("flow with data declared dead at %v", dead)
		}
	}
}

func TestLivenessFreshSessionCoveredByEarlierPong(t *testing.T) {
	// Сервер уже отвечал старому потоку. Новый поток подключился и не успел
	// получить ни одного ответа — сервер упал. Его всё равно надо снять.
	shared := new(atomic.Bool)
	old := newSimShared(t, shared)
	old.run(10*time.Second, false, true)

	fresh := newSimShared(t, shared)
	dead := fresh.run(time.Minute, false, false)
	if dead == 0 || dead > 16*time.Second {
		t.Fatalf("fresh flow on a server known to answer: dead at %v, want ≤ 16 s", dead)
	}
}

func TestLivenessGraceNeverShortensWindow(t *testing.T) {
	t0 := time.Unix(1000, 0)
	l := newSessionLivenessShared(t0, new(atomic.Bool))
	l.notePong(t0)
	l.grace(t0.Add(time.Second), livenessTimeout)
	if got := l.lastRx.Load(); got != t0.UnixMilli() {
		t.Fatalf("grace moved a fresh lastRx backwards: %d", got)
	}
}
