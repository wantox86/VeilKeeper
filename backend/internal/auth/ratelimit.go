package auth

import (
	"sync"
	"time"
)

// IPLimiter is a minimal in-memory sliding-window rate limiter, keyed by
// client IP, applied to the auth endpoints per SPEC-BASE.md Section 30
// ("Rate-limit authentication endpoints"). It's intentionally simple: no
// external dependency, single-process/in-memory only (fine for this
// project's single-instance homelab deployment target -- see repo
// CLAUDE.md/SPEC-BASE.md Section 52, "do not introduce infrastructure
// solely to optimize theoretical high-scale workloads"). Restarting the
// process resets all counters; that's an accepted tradeoff, not a bug.
type IPLimiter struct {
	mu       sync.Mutex
	requests map[string][]time.Time
	limit    int
	window   time.Duration
}

// NewIPLimiter creates a limiter allowing at most limit requests per window,
// per IP.
func NewIPLimiter(limit int, window time.Duration) *IPLimiter {
	return &IPLimiter{
		requests: make(map[string][]time.Time),
		limit:    limit,
		window:   window,
	}
}

// Allow reports whether a request from ip should be permitted right now,
// recording it if so.
func (l *IPLimiter) Allow(ip string) bool {
	now := time.Now()

	l.mu.Lock()
	defer l.mu.Unlock()

	cutoff := now.Add(-l.window)
	times := l.requests[ip]

	kept := times[:0]
	for _, t := range times {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}

	if len(kept) >= l.limit {
		l.requests[ip] = kept
		return false
	}

	kept = append(kept, now)
	l.requests[ip] = kept
	return true
}

// AccountLockout tracks failed login attempts per (normalized) email,
// independent of whether the email corresponds to a real account -- so a
// lockout being triggered (or not) never leaks account existence. After
// maxFailures failed attempts inside window, further attempts for that
// email are rejected until lockFor has elapsed since the last failure.
type AccountLockout struct {
	mu          sync.Mutex
	attempts    map[string][]time.Time
	maxFailures int
	window      time.Duration
	lockFor     time.Duration
}

func NewAccountLockout(maxFailures int, window, lockFor time.Duration) *AccountLockout {
	return &AccountLockout{
		attempts:    make(map[string][]time.Time),
		maxFailures: maxFailures,
		window:      window,
		lockFor:     lockFor,
	}
}

// Locked reports whether email is currently locked out.
func (a *AccountLockout) Locked(email string) bool {
	email = normalizeEmail(email)
	now := time.Now()

	a.mu.Lock()
	defer a.mu.Unlock()

	times := a.attempts[email]
	if len(times) < a.maxFailures {
		return false
	}
	last := times[len(times)-1]
	return now.Before(last.Add(a.lockFor))
}

// RecordFailure records a failed attempt for email.
func (a *AccountLockout) RecordFailure(email string) {
	email = normalizeEmail(email)
	now := time.Now()
	cutoff := now.Add(-a.window)

	a.mu.Lock()
	defer a.mu.Unlock()

	times := a.attempts[email]
	kept := times[:0]
	for _, t := range times {
		if t.After(cutoff) {
			kept = append(kept, t)
		}
	}
	kept = append(kept, now)
	a.attempts[email] = kept
}

// Reset clears failure history for email (called on successful login).
func (a *AccountLockout) Reset(email string) {
	email = normalizeEmail(email)
	a.mu.Lock()
	defer a.mu.Unlock()
	delete(a.attempts, email)
}
