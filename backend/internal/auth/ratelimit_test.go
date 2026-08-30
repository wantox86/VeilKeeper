package auth

import (
	"testing"
	"time"
)

func TestIPLimiter_AllowsUpToLimit(t *testing.T) {
	l := NewIPLimiter(3, time.Minute)

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

func TestAccountLockout_LocksAfterMaxFailures(t *testing.T) {
	l := NewAccountLockout(3, time.Minute, time.Minute)

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

	l.RecordFailure("nonexistent@example.com")
	if !l.Locked("nonexistent@example.com") {
		t.Fatal("expected lockout to apply to a nonexistent-account email just like a real one")
	}
}

func TestAccountLockout_UnlocksAfterLockDuration(t *testing.T) {
	l := NewAccountLockout(1, time.Minute, 20*time.Millisecond)
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
