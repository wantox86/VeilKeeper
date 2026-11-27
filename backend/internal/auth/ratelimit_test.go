package auth

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"
)

func TestIPLimiter_AllowsUpToLimit(t *testing.T) {
	l := NewIPLimiter(3, time.Minute)
	defer l.Close()

	for i := 0; i < 3; i++ {
		if !l.Allow("1.2.3.4") {
			t.Fatalf("request %d should have been allowed", i+1)
		}
	}
	if l.Allow("1.2.3.4") {
		t.Fatal("4th request within the window should have been rejected")
	}
}

func TestIPLimiter_PerIPIndependent(t *testing.T) {
	l := NewIPLimiter(1, time.Minute)
	defer l.Close()

	if !l.Allow("1.1.1.1") {
		t.Fatal("first request from 1.1.1.1 should be allowed")
	}
	if !l.Allow("2.2.2.2") {
		t.Fatal("first request from a different IP should be allowed independently")
	}
	if l.Allow("1.1.1.1") {
		t.Fatal("second request from 1.1.1.1 should be rejected")
	}
}

func TestIPLimiter_WindowExpires(t *testing.T) {
	l := NewIPLimiter(1, 20*time.Millisecond)
	defer l.Close()

	if !l.Allow("3.3.3.3") {
		t.Fatal("first request should be allowed")
	}
	if l.Allow("3.3.3.3") {
		t.Fatal("second immediate request should be rejected")
	}
	time.Sleep(30 * time.Millisecond)
	if !l.Allow("3.3.3.3") {
		t.Fatal("request after window expiry should be allowed again")
	}
}

func TestIPLimiter_SweeperEvictsExpiredKeys(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	l := NewIPLimiterWithContext(ctx, 5, 25*time.Millisecond, 100, 10*time.Millisecond)
	defer l.Close()

	l.Allow("10.0.0.1")
	l.Allow("10.0.0.2")
	l.Allow("10.0.0.3")
	if got := l.Len(); got != 3 {
		t.Fatalf("expected 3 keys, got %d", got)
	}

	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if l.Len() == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("expected sweeper to evict expired keys, still have %d", l.Len())
}

func TestIPLimiter_MaxKeysEvictsExpiredFirstThenOldest(t *testing.T) {
	// Part 1: expired keys purged first when full.
	l := NewIPLimiterWithContext(context.Background(), 2, 20*time.Millisecond, 3, time.Hour)
	defer l.Close()

	l.Allow("1.1.1.1")
	l.Allow("2.2.2.2")
	l.Allow("3.3.3.3")
	time.Sleep(30 * time.Millisecond)

	l.Allow("4.4.4.4")
	if got := l.Len(); got != 1 {
		t.Fatalf("expected expired keys to be purged first leaving 1 key, got %d", got)
	}

	// Part 2: when all keys are active and full, oldest key is evicted.
	l2 := NewIPLimiterWithContext(context.Background(), 1, time.Minute, 3, time.Hour)
	defer l2.Close()

	l2.Allow("ip-1")
	l2.Allow("ip-2")
	l2.Allow("ip-3")
	l2.Allow("ip-4")

	if got := l2.Len(); got != 3 {
		t.Fatalf("expected Len capped at maxKeys=3, got %d", got)
	}
	// ip-1 was oldest so it was evicted; a new request for ip-1 should be allowed again
	// (which will evict ip-2).
	if !l2.Allow("ip-1") {
		t.Fatal("expected evicted oldest key ip-1 to be allowed again")
	}
	if got := l2.Len(); got != 3 {
		t.Fatalf("expected Len still capped at 3, got %d", got)
	}
}

func TestIPLimiter_RejectedRequestUpdatesLRU(t *testing.T) {
	l := NewIPLimiterWithContext(context.Background(), 1, time.Minute, 2, time.Hour)
	defer l.Close()

	if !l.Allow("ip-1") {
		t.Fatal("expected first request for ip-1 to be allowed")
	}
	if !l.Allow("ip-2") {
		t.Fatal("expected first request for ip-2 to be allowed")
	}
	// Rejected request for ip-1 should still move ip-1 to MRU, making ip-2 the LRU.
	if l.Allow("ip-1") {
		t.Fatal("expected second request for ip-1 to be rejected")
	}
	// Adding ip-3 should evict ip-2 (LRU), keeping ip-1 rate-limited.
	if !l.Allow("ip-3") {
		t.Fatal("expected first request for ip-3 to be allowed")
	}
	if l.Allow("ip-1") {
		t.Fatal("expected ip-1 to remain tracked and rate-limited after ip-3 evicted ip-2")
	}
	if !l.Allow("ip-2") {
		t.Fatal("expected ip-2 to have been evicted as LRU and allowed again")
	}
}

func TestIPLimiter_StopsOnContextCancel(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	l := NewIPLimiterWithContext(ctx, 5, time.Minute, 100, 10*time.Millisecond)
	cancel()

	select {
	case <-l.done:
	case <-time.After(500 * time.Millisecond):
		t.Fatal("expected sweeper goroutine to exit when context cancelled")
	}
	// Close after context cancel must be a safe no-op.
	l.Close()
}

func TestIPLimiter_ParallelRaceSafe(t *testing.T) {
	l := NewIPLimiterWithContext(context.Background(), 5, 20*time.Millisecond, 16, 5*time.Millisecond)
	defer l.Close()

	var wg sync.WaitGroup
	for g := 0; g < 16; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				ip := fmt.Sprintf("198.51.100.%d", (id*17+i)%40)
				_ = l.Allow(ip)
				if i%20 == 0 {
					_ = l.Len()
				}
			}
		}(g)
	}
	wg.Wait()

	if got := l.Len(); got > 16 {
		t.Fatalf("expected Len <= maxKeys(16), got %d", got)
	}
}

func TestAccountLockout_LocksAfterMaxFailures(t *testing.T) {
	l := NewAccountLockout(3, time.Minute, time.Minute)
	defer l.Close()

	email := "victim@example.com"
	if l.Locked(email) {
		t.Fatal("should not be locked before any failures")
	}
	for i := 0; i < 3; i++ {
		l.RecordFailure(email)
	}
	if !l.Locked(email) {
		t.Fatal("expected account to be locked after reaching max failures")
	}
}

func TestAccountLockout_ResetClearsHistory(t *testing.T) {
	l := NewAccountLockout(2, time.Minute, time.Minute)
	defer l.Close()
	email := "someone@example.com"

	l.RecordFailure(email)
	l.RecordFailure(email)
	if !l.Locked(email) {
		t.Fatal("expected locked after 2 failures")
	}
	l.Reset(email)
	if l.Locked(email) {
		t.Fatal("expected lockout to be cleared after Reset")
	}
}

func TestAccountLockout_AppliesRegardlessOfAccountExistence(t *testing.T) {
	// Lockout bookkeeping is keyed purely by the email string the client
	// sent, so it must behave identically whether or not that email
	// corresponds to a real account -- this is what keeps it from being an
	// enumeration oracle.
	l := NewAccountLockout(1, time.Minute, time.Minute)
	defer l.Close()

	l.RecordFailure("nonexistent@example.com")
	if !l.Locked("nonexistent@example.com") {
		t.Fatal("expected lockout to apply to a nonexistent-account email just like a real one")
	}
}

func TestAccountLockout_UnlocksAfterLockDuration(t *testing.T) {
	l := NewAccountLockout(1, time.Minute, 20*time.Millisecond)
	defer l.Close()
	email := "temp@example.com"

	l.RecordFailure(email)
	if !l.Locked(email) {
		t.Fatal("expected locked immediately after crossing threshold")
	}
	time.Sleep(30 * time.Millisecond)
	if l.Locked(email) {
		t.Fatal("expected lock to expire after lockFor duration")
	}
}

func TestAccountLockout_SweeperEvictsExpiredKeys(t *testing.T) {
	l := NewAccountLockoutWithContext(context.Background(), 2, 20*time.Millisecond, 20*time.Millisecond, 100, 10*time.Millisecond)
	defer l.Close()

	l.RecordFailure("a@example.com")
	l.RecordFailure("b@example.com")
	if got := l.Len(); got != 2 {
		t.Fatalf("expected 2 keys, got %d", got)
	}

	deadline := time.Now().Add(500 * time.Millisecond)
	for time.Now().Before(deadline) {
		if l.Len() == 0 {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("expected sweeper to evict expired lockout keys, still have %d", l.Len())
}

func TestAccountLockout_MaxKeysEnforced(t *testing.T) {
	l := NewAccountLockoutWithContext(context.Background(), 1, time.Minute, time.Minute, 3, time.Hour)
	defer l.Close()

	l.RecordFailure("u1@example.com")
	l.RecordFailure("u2@example.com")
	l.RecordFailure("u3@example.com")
	l.RecordFailure("u4@example.com")

	if got := l.Len(); got != 3 {
		t.Fatalf("expected Len capped at 3, got %d", got)
	}
	if l.Locked("u1@example.com") {
		t.Fatal("expected oldest key u1@example.com to have been evicted")
	}
	if !l.Locked("u4@example.com") {
		t.Fatal("expected newest key u4@example.com to remain locked")
	}
}

func TestAccountLockout_ParallelRaceSafe(t *testing.T) {
	l := NewAccountLockoutWithContext(context.Background(), 3, 20*time.Millisecond, 20*time.Millisecond, 16, 5*time.Millisecond)
	defer l.Close()

	var wg sync.WaitGroup
	for g := 0; g < 16; g++ {
		wg.Add(1)
		go func(id int) {
			defer wg.Done()
			for i := 0; i < 100; i++ {
				email := fmt.Sprintf("user%d@example.com", (id*13+i)%40)
				l.RecordFailure(email)
				_ = l.Locked(email)
				if i%10 == 0 {
					l.Reset(email)
				}
				if i%25 == 0 {
					_ = l.Len()
				}
			}
		}(g)
	}
	wg.Wait()

	if got := l.Len(); got > 16 {
		t.Fatalf("expected Len <= maxKeys(16), got %d", got)
	}
}
