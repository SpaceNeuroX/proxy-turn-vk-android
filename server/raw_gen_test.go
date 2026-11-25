package main

import (
	"net"
	"testing"
)

func TestParseRawHello(t *testing.T) {
	cases := []struct {
		in       string
		dev, pwd string
		gen      int64
	}{
		{"dev1|secret", "dev1", "secret", 0},
		{"dev1|secret|gen=1732500000000", "dev1", "secret", 1732500000000},
		{" dev1|secret|gen=42 \n", "dev1", "secret", 42},
		// An unnamed third field (another server's MTU, say) is not a generation.
		{"dev1|secret|1400", "dev1", "secret", 0},
		{"dev1|secret|1400|gen=7", "dev1", "secret", 7},
		{"dev1|secret|gen=abc", "dev1", "secret", 0},
		{"dev1|secret|gen=-5", "dev1", "secret", 0},
		{"dev1", "dev1", "", 0},
	}
	for _, c := range cases {
		dev, pwd, gen := parseRawHello(c.in)
		if dev != c.dev || pwd != c.pwd || gen != c.gen {
			t.Errorf("parseRawHello(%q) = %q, %q, %d; want %q, %q, %d", c.in, dev, pwd, gen, c.dev, c.pwd, c.gen)
		}
	}
}

func testPipeConn(t *testing.T) net.Conn {
	t.Helper()
	a, b := net.Pipe()
	t.Cleanup(func() {
		_ = a.Close()
		_ = b.Close()
	})
	return a
}

func workersOf(r *rawRouter, ip string) []*downlinkWorker {
	r.mu.RLock()
	defer r.mu.RUnlock()
	if cs := r.sessions[ip]; cs != nil {
		return append([]*downlinkWorker(nil), cs.workers...)
	}
	return nil
}

func sameWorkers(got []*downlinkWorker, want ...*downlinkWorker) bool {
	if len(got) != len(want) {
		return false
	}
	for i := range got {
		if got[i] != want[i] {
			return false
		}
	}
	return true
}

// A newer generation of the same device replaces the streams of older
// generations at once; clients without generations and other devices are
// left alone, and a lower generation that arrives later replaces nobody.
func TestRegisterNewGenerationReplacesOlderStreams(t *testing.T) {
	r := &rawRouter{sessions: make(map[string]*rawClientSessions)}
	const ip, otherIP = "10.70.0.2", "10.70.0.3"

	old1 := r.register(ip, testPipeConn(t), "dev", 100)
	old2 := r.register(ip, testPipeConn(t), "dev", 100)
	legacy := r.register(ip, testPipeConn(t), "dev", 0)
	other := r.register(otherIP, testPipeConn(t), "dev2", 50)
	cur := r.register(ip, testPipeConn(t), "dev", 200)

	if got := workersOf(r, ip); !sameWorkers(got, legacy, cur) {
		t.Fatalf("workers after a new generation: %d, want [legacy, cur]", len(got))
	}
	if !old1.kicked.Load() || !old2.kicked.Load() {
		t.Fatal("streams of the old generation are not kicked")
	}
	if legacy.kicked.Load() || cur.kicked.Load() || other.kicked.Load() {
		t.Fatal("a stream that must stay was kicked")
	}
	if got := workersOf(r, otherIP); !sameWorkers(got, other) {
		t.Fatal("another device's streams changed")
	}

	// The read of a kicked stream returns at once, so handleConnRaw ends.
	if _, err := old1.conn.Read(make([]byte, 1)); err == nil || !isNetTimeout(err) {
		t.Fatalf("read of a kicked stream: %v, want a timeout", err)
	}

	// A lower generation later (the phone clock moved back) replaces nobody.
	late := r.register(ip, testPipeConn(t), "dev", 150)
	if got := workersOf(r, ip); !sameWorkers(got, legacy, cur, late) {
		t.Fatalf("workers after a lower generation: %d, want [legacy, cur, late]", len(got))
	}
	if cur.kicked.Load() || late.kicked.Load() {
		t.Fatal("a lower generation kicked a stream")
	}

	for _, w := range []*downlinkWorker{old1, old2, legacy, cur, late} {
		r.unregister(ip, w)
	}
	r.unregister(otherIP, other)
	if len(r.sessions) != 0 {
		t.Fatalf("sessions left after unregister: %d", len(r.sessions))
	}
}
