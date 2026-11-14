package main

import (
	"testing"
	"time"
)

func TestWorkerRetryDelay(t *testing.T) {
	cases := []struct {
		attempt int
		base    time.Duration
	}{
		{0, 500 * time.Millisecond},
		{1, 500 * time.Millisecond},
		{2, time.Second},
		{3, 2 * time.Second},
		{5, 8 * time.Second},
		{50, 8 * time.Second},
	}
	for _, c := range cases {
		for i := 0; i < 20; i++ {
			d := workerRetryDelay(c.attempt)
			if d < c.base || d >= c.base+500*time.Millisecond {
				t.Fatalf("attempt %d: delay %v, want [%v, %v)", c.attempt, d, c.base, c.base+500*time.Millisecond)
			}
		}
	}
}
