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
