package main

import (
	"net"
	"testing"
)

func TestHelloFields(t *testing.T) {
	if got := helloFields("dev", "pw", 0); got != "dev|pw" {
		t.Errorf("without a generation: %q", got)
	}
	if got := helloFields("dev", "pw", 1732500000000); got != "dev|pw|gen=1732500000000" {
		t.Errorf("with a generation: %q", got)
	}
}

func TestSendAuthCarriesGeneration(t *testing.T) {
	for _, c := range []struct {
		gen  int64
		want string
	}{
		{0, "AUTH:dev|pw"},
		{77, "AUTH:dev|pw|gen=77"},
	} {
		a, b := net.Pipe()
		go func() { _ = SendAuth(a, "dev", "pw", c.gen) }()
		buf := make([]byte, 64)
		n, err := b.Read(buf)
		_ = a.Close()
		_ = b.Close()
		if err != nil || string(buf[:n]) != c.want {
			t.Errorf("SendAuth gen=%d sent %q (%v), want %q", c.gen, buf[:n], err, c.want)
		}
	}
}
