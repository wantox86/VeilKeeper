package auth

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func TestHashAndVerifyAuthKey_RoundTrip(t *testing.T) {
	key := []byte("a-32-byte-ish-authkey-value-123")

	encoded, err := HashAuthKey(key)
	if err != nil {
		t.Fatalf("HashAuthKey: %v", err)
	}

	ok, err := VerifyAuthKey(key, encoded)
	if err != nil {
		t.Fatalf("VerifyAuthKey: %v", err)
	}
	if !ok {
		t.Fatal("expected correct auth key to verify")
	}
}

func TestVerifyAuthKey_WrongKeyRejected(t *testing.T) {
	encoded, err := HashAuthKey([]byte("correct-key"))
	if err != nil {
		t.Fatalf("HashAuthKey: %v", err)
	}

	ok, err := VerifyAuthKey([]byte("wrong-key"), encoded)
	if err != nil {
		t.Fatalf("VerifyAuthKey: %v", err)
	}
	if ok {
		t.Fatal("expected wrong auth key to be rejected")
	}
}

func TestHashAuthKey_DistinctSaltsPerCall(t *testing.T) {
	// Same input key, hashed twice, must produce different encoded output
	// (unique per-hash salt) -- otherwise identical AuthKeys would be
	// visibly identical in the DB.
	key := []byte("same-key-both-times")

	h1, err := HashAuthKey(key)
	if err != nil {
		t.Fatalf("HashAuthKey: %v", err)
	}
	h2, err := HashAuthKey(key)
	if err != nil {
		t.Fatalf("HashAuthKey: %v", err)
	}
	if h1 == h2 {
		t.Fatal("expected distinct encoded hashes for two calls with the same key (salt reuse?)")
	}
}

func TestHashAuthKey_RejectsEmptyKey(t *testing.T) {
	if _, err := HashAuthKey(nil); err == nil {
		t.Fatal("expected error hashing an empty auth key")
	}
}

func TestVerifyAuthKey_MalformedHashRejected(t *testing.T) {
	if _, err := VerifyAuthKey([]byte("anything"), "not-a-real-hash"); err == nil {
		t.Fatal("expected error verifying against a malformed hash string")
	}
}

func TestVerifyAgainstDummyHash_DoesNotPanic(t *testing.T) {
	// Just exercising the timing-safety helper: it must never panic
	// regardless of input, since it runs on the "user not found" login path.
	VerifyAgainstDummyHash([]byte("anything"))
	VerifyAgainstDummyHash(nil)
}

func TestNewHasher_RejectsNonPositive(t *testing.T) {
	for _, n := range []int{0, -1, -50} {
		if _, err := NewHasher(n, DefaultArgon2AcquireTimeout); err == nil {
			t.Fatalf("expected NewHasher(%d, default) to fail", n)
		}
	}
	for _, d := range []time.Duration{0, -time.Millisecond, -time.Second} {
		if _, err := NewHasher(1, d); err == nil {
			t.Fatalf("expected NewHasher(1, %s) to fail", d)
		}
	}
}

func TestHasher_EnforcesMaxConcurrency(t *testing.T) {
	const maxConcurrent = 3
	const totalWorkers = 18

	h, err := NewHasher(maxConcurrent, DefaultArgon2AcquireTimeout)
	if err != nil {
		t.Fatalf("NewHasher: %v", err)
	}

	var inFlight atomic.Int32
	var maxSeen atomic.Int32
	h.idKeyFn = func(password, salt []byte, iterations, memory uint32, threads uint8, keyLen uint32) []byte {
		cur := inFlight.Add(1)
		for {
			prev := maxSeen.Load()
			if cur <= prev || maxSeen.CompareAndSwap(prev, cur) {
				break
			}
		}
		time.Sleep(15 * time.Millisecond)
		inFlight.Add(-1)
		out := make([]byte, keyLen)
		copy(out, password)
		return out
	}

	encoded, err := h.HashAuthKey(context.Background(), []byte("seed-key"))
	if err != nil {
		t.Fatalf("seed HashAuthKey: %v", err)
	}
	maxSeen.Store(0)

	var wg sync.WaitGroup
	errCh := make(chan error, totalWorkers)

	for i := 0; i < totalWorkers; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			ctx := context.Background()
			switch idx % 3 {
			case 0:
				if _, err := h.HashAuthKey(ctx, []byte("worker-key")); err != nil {
					errCh <- err
				}
			case 1:
				if _, err := h.VerifyAuthKey(ctx, []byte("seed-key"), encoded); err != nil {
					errCh <- err
				}
			case 2:
				if err := h.VerifyAgainstDummyHash(ctx, []byte("worker-key")); err != nil {
					errCh <- err
				}
			}
		}(i)
	}

	wg.Wait()
	close(errCh)

	for err := range errCh {
		t.Fatalf("unexpected worker error: %v", err)
	}

	if got := maxSeen.Load(); got > maxConcurrent {
		t.Fatalf("concurrency limit exceeded: maxSeen=%d, limit=%d", got, maxConcurrent)
	}
	if got := maxSeen.Load(); got < maxConcurrent {
		t.Fatalf("expected concurrency to reach limit=%d, only saw %d", maxConcurrent, got)
	}
}

func TestHasher_ContextCancelledWhileWaiting(t *testing.T) {
	h, err := NewHasher(1, DefaultArgon2AcquireTimeout)
	if err != nil {
		t.Fatalf("NewHasher: %v", err)
	}

	entered := make(chan struct{})
	release := make(chan struct{})
	var blockOnce sync.Once

	h.idKeyFn = func(password, salt []byte, iterations, memory uint32, threads uint8, keyLen uint32) []byte {
		blockOnce.Do(func() {
			close(entered)
			<-release
		})
		out := make([]byte, keyLen)
		copy(out, password)
		return out
	}

	// Occupy the single semaphore slot.
	holderDone := make(chan error, 1)
	go func() {
		_, err := h.HashAuthKey(context.Background(), []byte("holder-key"))
		holderDone <- err
	}()
	<-entered

	// Waiting caller with short timeout must fail with ErrArgon2Busy.
	waitCtx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	if _, err := h.HashAuthKey(waitCtx, []byte("waiting-key")); !errors.Is(err, ErrArgon2Busy) {
		t.Fatalf("HashAuthKey: expected ErrArgon2Busy, got %v", err)
	}

	// Pre-cancelled context on VerifyAuthKey and VerifyAgainstDummyHash must also return ErrArgon2Busy.
	cancelledCtx, cancelNow := context.WithCancel(context.Background())
	cancelNow()
	if _, err := h.VerifyAuthKey(cancelledCtx, []byte("k"), dummyAuthKeyHash); !errors.Is(err, ErrArgon2Busy) {
		t.Fatalf("VerifyAuthKey: expected ErrArgon2Busy, got %v", err)
	}
	if err := h.VerifyAgainstDummyHash(cancelledCtx, []byte("k")); !errors.Is(err, ErrArgon2Busy) {
		t.Fatalf("VerifyAgainstDummyHash: expected ErrArgon2Busy, got %v", err)
	}

	// Release holder and verify the slot is usable again.
	close(release)
	if err := <-holderDone; err != nil {
		t.Fatalf("holder failed: %v", err)
	}
	if _, err := h.HashAuthKey(context.Background(), []byte("after-release")); err != nil {
		t.Fatalf("expected subsequent HashAuthKey to succeed after slot release, got %v", err)
	}
}

func TestHasher_AcquireTimeoutWithoutRequestDeadline(t *testing.T) {
	const acquireTimeout = 50 * time.Millisecond

	entered := make(chan struct{})
	release := make(chan struct{})
	var blockOnce sync.Once

	h, err := NewHasherWithKeyFunc(1, acquireTimeout, func(password, salt []byte, iterations, memory uint32, threads uint8, keyLen uint32) []byte {
		blockOnce.Do(func() {
			close(entered)
			<-release
		})
		out := make([]byte, keyLen)
		copy(out, password)
		return out
	})
	if err != nil {
		t.Fatalf("NewHasherWithKeyFunc: %v", err)
	}

	// Occupy the single semaphore slot longer than acquireTimeout to also
	// verify the timeout only bounds slot waiting, not the hash itself.
	holderDone := make(chan error, 1)
	go func() {
		_, err := h.HashAuthKey(context.Background(), []byte("holder-key"))
		holderDone <- err
	}()
	<-entered

	// Request context has NO deadline: acquire must fail fast around
	// acquireTimeout with ErrArgon2Busy instead of hanging indefinitely.
	start := time.Now()
	_, err = h.HashAuthKey(context.Background(), []byte("waiting-key"))
	elapsed := time.Since(start)
	if !errors.Is(err, ErrArgon2Busy) {
		t.Fatalf("HashAuthKey: expected ErrArgon2Busy, got %v", err)
	}
	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("HashAuthKey: expected wrapped context.DeadlineExceeded, got %v", err)
	}
	if elapsed < acquireTimeout/2 || elapsed > 10*acquireTimeout {
		t.Fatalf("HashAuthKey: expected wait around %s, took %s", acquireTimeout, elapsed)
	}

	// VerifyAuthKey and VerifyAgainstDummyHash also fail with ErrArgon2Busy
	// when the request context has no deadline.
	if _, err := h.VerifyAuthKey(context.Background(), []byte("k"), dummyAuthKeyHash); !errors.Is(err, ErrArgon2Busy) {
		t.Fatalf("VerifyAuthKey: expected ErrArgon2Busy, got %v", err)
	}
	if err := h.VerifyAgainstDummyHash(context.Background(), []byte("k")); !errors.Is(err, ErrArgon2Busy) {
		t.Fatalf("VerifyAgainstDummyHash: expected ErrArgon2Busy, got %v", err)
	}

	// Release holder; it ran longer than acquireTimeout and must still succeed.
	close(release)
	if err := <-holderDone; err != nil {
		t.Fatalf("holder failed: %v", err)
	}

	// Slot must be free again (no leak on timeout paths).
	if got := len(h.sem); got != 0 {
		t.Fatalf("expected semaphore to be empty after holder release, got %d occupied", got)
	}
	if _, err := h.HashAuthKey(context.Background(), []byte("after-timeout")); err != nil {
		t.Fatalf("expected subsequent HashAuthKey to succeed after timeout and release, got %v", err)
	}
}
