package main

import (
	"net"
	"sync"
	"testing"
	"time"
)

// fakeConn — заглушка net.Conn для unit-тестов rawRouter.
// register() создаёт downlinkWorker, который запускает writer-горутину;
// реальный коннект не нужен, но nil conn привёл бы к панике в run(),
// поэтому даём безопасную реализацию.
type fakeConn struct{}

func (c *fakeConn) Read(b []byte) (int, error) {
	panic("fakeConn: Read не используется в этих тестах")
}

func (c *fakeConn) Write(b []byte) (int, error) { return len(b), nil }

func (c *fakeConn) Close() error { return nil }

func (c *fakeConn) LocalAddr() net.Addr  { return nil }
func (c *fakeConn) RemoteAddr() net.Addr { return nil }

func (c *fakeConn) SetDeadline(t time.Time) error      { return nil }
func (c *fakeConn) SetReadDeadline(t time.Time) error  { return nil }
func (c *fakeConn) SetWriteDeadline(t time.Time) error { return nil }

func oldCreatedAt() int64 {
	return time.Now().UnixMilli() - massReRegisterWindowMs - 60*1000
}

// TestRawRegisterSingleDoesNotDropOld проверяет, что одиночная регистрация
// не считается волной и старые воркеры остаются в ротации.
func TestRawRegisterSingleDoesNotDropOld(t *testing.T) {
	r := &rawRouter{sessions: make(map[string]*rawClientSessions)}
	ip := "10.200.0.1"
	var workers []*downlinkWorker
	for i := 0; i < 3; i++ {
		w := r.register(ip, &fakeConn{}, "dev")
		w.createdAt = oldCreatedAt()
		workers = append(workers, w)
	}
	workers = append(workers, r.register(ip, &fakeConn{}, "dev"))

	r.mu.Lock()
	defer r.mu.Unlock()
	cs := r.sessions[ip]
	if cs == nil || len(cs.workers) != 4 {
		t.Fatalf("ожидалось 4 воркера, получено %d", len(cs.workers))
	}

	for _, w := range workers {
		w.stop()
	}
}

// TestRawMassReRegistrationDropsOld проверяет, что волна из 9+ регистраций
// одного устройства удаляет всех старых воркеров и сбрасывает round-robin.
func TestRawMassReRegistrationDropsOld(t *testing.T) {
	r := &rawRouter{sessions: make(map[string]*rawClientSessions)}
	ip := "10.200.0.2"
	for i := 0; i < 3; i++ {
		w := r.register(ip, &fakeConn{}, "dev")
		w.createdAt = oldCreatedAt()
	}
	var newWorkers []*downlinkWorker
	for i := 0; i < 9; i++ {
		newWorkers = append(newWorkers, r.register(ip, &fakeConn{}, "dev"))
	}

	threshold := time.Now().UnixMilli() - massReRegisterWindowMs

	r.mu.Lock()
	defer r.mu.Unlock()
	cs := r.sessions[ip]
	if len(cs.workers) != 9 {
		t.Fatalf("ожидалось 9 воркеров после волны, получено %d", len(cs.workers))
	}
	if cs.rrIndex >= len(cs.workers) {
		t.Fatalf("rrIndex %d вне диапазона [0,%d)", cs.rrIndex, len(cs.workers))
	}
	if cs.rrCount != 0 {
		t.Fatalf("ожидался сброс rrCount, получено %d", cs.rrCount)
	}
	if cs.chunkStartTs != 0 {
		t.Fatalf("ожидался сброс chunkStartTs, получено %d", cs.chunkStartTs)
	}
	for _, w := range cs.workers {
		if w.createdAt < threshold {
			t.Fatalf("остался воркер старше окна: createdAt=%d", w.createdAt)
		}
	}

	for _, w := range newWorkers {
		w.stop()
	}
}

// TestRawCleanRampKeepsAll проверяет, что у нового устройства нет старых
// воркеров, и 20 регистраций подряд не удаляют никого.
func TestRawCleanRampKeepsAll(t *testing.T) {
	r := &rawRouter{sessions: make(map[string]*rawClientSessions)}
	ip := "10.200.0.3"
	var workers []*downlinkWorker
	for i := 0; i < 20; i++ {
		workers = append(workers, r.register(ip, &fakeConn{}, "dev"))
	}

	r.mu.Lock()
	defer r.mu.Unlock()
	cs := r.sessions[ip]
	if len(cs.workers) != 20 {
		t.Fatalf("ожидалось 20 воркеров, получено %d", len(cs.workers))
	}

	for _, w := range workers {
		w.stop()
	}
}

// TestRawMassDropIsolatedByIP проверяет, что волна для одного IP не трогает
// сессии другого IP.
func TestRawMassDropIsolatedByIP(t *testing.T) {
	r := &rawRouter{sessions: make(map[string]*rawClientSessions)}
	ipA := "10.200.0.4"
	ipB := "10.200.0.5"

	for i := 0; i < 3; i++ {
		w := r.register(ipA, &fakeConn{}, "devA")
		w.createdAt = oldCreatedAt()
	}
	for i := 0; i < 3; i++ {
		w := r.register(ipB, &fakeConn{}, "devB")
		w.createdAt = oldCreatedAt()
	}

	for i := 0; i < 9; i++ {
		r.register(ipA, &fakeConn{}, "devA")
	}
	r.register(ipB, &fakeConn{}, "devB")

	r.mu.Lock()
	defer r.mu.Unlock()
	csA := r.sessions[ipA]
	csB := r.sessions[ipB]
	if len(csA.workers) != 9 {
		t.Fatalf("IP A: ожидалось 9 воркеров, получено %d", len(csA.workers))
	}
	if len(csB.workers) != 4 {
		t.Fatalf("IP B: ожидалось 4 воркера, получено %d", len(csB.workers))
	}

	for _, ip := range []string{ipA, ipB} {
		r.mu.Lock()
		cs := r.sessions[ip]
		var list []*downlinkWorker
		if cs != nil {
			list = append([]*downlinkWorker(nil), cs.workers...)
		}
		r.mu.Unlock()
		for _, w := range list {
			w.stop()
		}
	}
}

// TestRawDownlinkWorkerStopIdempotent проверяет, что повторный вызов stop()
// не паникует (нужно, так как mass-drop и unregister могут вызывать stop
// для одного и того же воркера).
func TestRawDownlinkWorkerStopIdempotent(t *testing.T) {
	w := newDownlinkWorker(&fakeConn{}, "dev")
	w.stop()
	select {
	case <-w.done:
	default:
		t.Fatal("stop() должен дожидаться завершения run()")
	}
	w.stop()
}

// TestRawDownlinkEnqueueAfterStopNoPanic проверяет, что enqueue после stop()
// не паникует на закрытом канале (защищено per-worker мьютексом).
func TestRawDownlinkEnqueueAfterStopNoPanic(t *testing.T) {
	w := newDownlinkWorker(&fakeConn{}, "dev")
	pkt1 := getBuf2048()[:10]
	if !w.enqueue(pkt1) {
		t.Fatal("первая enqueue до stop должна вернуть true")
	}
	w.stop()

	pkt2 := getBuf2048()[:10]
	if w.enqueue(pkt2) {
		t.Fatal("enqueue после stop должна вернуть false")
	}

	w2 := newDownlinkWorker(&fakeConn{}, "dev2")
	stopCh := make(chan struct{})
	var wg sync.WaitGroup
	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() {
				if r := recover(); r != nil {
					t.Errorf("panic в горутине enqueue: %v", r)
				}
			}()
			for {
				select {
				case <-stopCh:
					return
				default:
					pkt := getBuf2048()[:10]
					w2.enqueue(pkt)
				}
			}
		}()
	}

	time.Sleep(20 * time.Millisecond)
	close(stopCh)
	w2.stop()
	wg.Wait()
}
