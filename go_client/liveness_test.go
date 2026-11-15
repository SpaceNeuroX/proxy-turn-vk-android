package main

import (
	"testing"
	"time"
)

func TestLivenessOldServerNeverKills(t *testing.T) {
	t0 := time.Unix(1000, 0)
	l := newSessionLiveness(t0)
	// Старый сервер на keepalive не отвечает: сколько бы ни молчал — не трогаем.
	if _, dead := l.silentFor(t0.Add(10 * time.Minute)); dead {
		t.Fatal("without pongs the session must never be declared dead")
	}
}

func TestLivenessDetectsSilence(t *testing.T) {
	t0 := time.Unix(1000, 0)
	l := newSessionLiveness(t0)
	l.notePong(t0.Add(time.Second))
	if _, dead := l.silentFor(t0.Add(10 * time.Second)); dead {
		t.Fatal("9 s of silence is not death yet")
	}
	silent, dead := l.silentFor(t0.Add(17 * time.Second))
	if !dead || silent != 16*time.Second {
		t.Fatalf("16 s of silence after a pong must be death, got %v %v", silent, dead)
	}
}

func TestLivenessDataKeepsAlive(t *testing.T) {
	t0 := time.Unix(1000, 0)
	l := newSessionLiveness(t0)
	l.notePong(t0)
	// Ответы на keepalive не дошли, но данные идут — поток жив.
	for s := 5; s <= 60; s += 5 {
		l.noteRx(t0.Add(time.Duration(s) * time.Second))
		if _, dead := l.silentFor(t0.Add(time.Duration(s+3) * time.Second)); dead {
			t.Fatalf("flow with data at %ds declared dead", s)
		}
	}
}

func TestLivenessSurvivesProcessFreeze(t *testing.T) {
	t0 := time.Unix(1000, 0)
	l := newSessionLiveness(t0)
	l.notePong(t0)
	for s := 1; s <= 3; s++ {
		l.tick(t0.Add(time.Duration(s) * time.Second))
	}
	// Телефон уснул на 10 минут: таймеры стояли, ответов не было.
	wake := t0.Add(10 * time.Minute)
	if !l.tick(wake) {
		t.Fatal("a 10-minute gap between ticks must be detected as a freeze")
	}
	if _, dead := l.silentFor(wake); dead {
		t.Fatal("silence during a freeze must not kill the flow")
	}
	// Поток действительно умер во сне: ответов нет и после пробуждения —
	// через 15 с он снимается, как обычно.
	for s := 1; s <= 16; s++ {
		l.tick(wake.Add(time.Duration(s) * time.Second))
	}
	if _, dead := l.silentFor(wake.Add(16 * time.Second)); !dead {
		t.Fatal("a flow still silent 16 s after waking must be declared dead")
	}
}

func TestLivenessNormalTicksAreNotFreeze(t *testing.T) {
	t0 := time.Unix(1000, 0)
	l := newSessionLiveness(t0)
	for s := 0; s <= 30; s++ {
		// Тики с небольшим дрожанием — не заморозка.
		if l.tick(t0.Add(time.Duration(s)*time.Second + time.Duration(s%3)*300*time.Millisecond)) {
			t.Fatalf("tick %d wrongly detected as a freeze", s)
		}
	}
}
