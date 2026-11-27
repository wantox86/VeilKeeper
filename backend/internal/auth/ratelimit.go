package auth

import (
	"container/list"
	"context"
	"sync"
	"time"
)

const (
	// DefaultMaxKeys bounds the in-memory map size for IPLimiter and
	// AccountLockout so a flood of unique IPs or emails cannot grow memory
	// without bound.
	DefaultMaxKeys = 100_000

	defaultIPLimiterSweepInterval = time.Minute
	defaultLockoutSweepInterval   = 5 * time.Minute
)

type ipEntry struct {
	key   string
	times []time.Time
	elem  *list.Element
}

// IPLimiter is a minimal in-memory sliding-window rate limiter, keyed by
// client IP, applied to the auth endpoints per SPEC-BASE.md Section 30
// ("Rate-limit authentication endpoints"). It's intentionally simple: no
// external dependency, single-process/in-memory only (fine for this
// project's single-instance homelab deployment target -- see repo
// CLAUDE.md/SPEC-BASE.md Section 52, "do not introduce infrastructure
// solely to optimize theoretical high-scale workloads"). Restarting the
// process resets all counters; that's an accepted tradeoff, not a bug.
//
// A background sweeper periodically evicts expired keys, and maxKeys caps
// total entries so memory stays bounded. Call Close (or cancel the context
// passed to NewIPLimiterWithContext) to stop the sweeper goroutine.
type IPLimiter struct {
	mu       sync.Mutex
	requests map[string]*ipEntry
	order    *list.List // front = most recently updated, back = oldest
	limit    int
	window   time.Duration
	maxKeys  int

	cancel    context.CancelFunc
	done      chan struct{}
	closeOnce sync.Once
}

// NewIPLimiter creates a limiter allowing at most limit requests per window,
// per IP, with DefaultMaxKeys cap and a periodic background sweeper.
func NewIPLimiter(limit int, window time.Duration) *IPLimiter {
	return NewIPLimiterWithContext(context.Background(), limit, window, DefaultMaxKeys, defaultIPLimiterSweepInterval)
}

// NewIPLimiterWithContext creates an IPLimiter whose background sweeper
// stops when ctx is cancelled or Close is called. Non-positive maxKeys or
// sweepInterval fall back to their defaults.
func NewIPLimiterWithContext(ctx context.Context, limit int, window time.Duration, maxKeys int, sweepInterval time.Duration) *IPLimiter {
	if maxKeys <= 0 {
		maxKeys = DefaultMaxKeys
	}
	if sweepInterval <= 0 {
		sweepInterval = defaultIPLimiterSweepInterval
	}
	if ctx == nil {
		ctx = context.Background()
	}

	sweepCtx, cancel := context.WithCancel(ctx)
	l := &IPLimiter{
		requests: make(map[string]*ipEntry),
		order:    list.New(),
		limit:    limit,
		window:   window,
		maxKeys:  maxKeys,
		cancel:   cancel,
		done:     make(chan struct{}),
	}

	go l.runSweeper(sweepCtx, sweepInterval)
	return l
}

func (l *IPLimiter) runSweeper(ctx context.Context, interval time.Duration) {
	defer close(l.done)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			l.sweep(now)
		}
	}
}

// Close stops the background sweeper goroutine and waits for it to exit.
// Safe to call multiple times.
func (l *IPLimiter) Close() {
	l.closeOnce.Do(func() {
		l.cancel()
		<-l.done
	})
}

// Len returns the current number of tracked IP keys.
func (l *IPLimiter) Len() int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return len(l.requests)
}

func (l *IPLimiter) sweep(now time.Time) {
	l.mu.Lock()
	defer l.mu.Unlock()
	l.sweepLocked(now)
}

func (l *IPLimiter) sweepLocked(now time.Time) {
	cutoff := now.Add(-l.window)
	for elem := l.order.Back(); elem != nil; {
		prev := elem.Prev()
		entry := elem.Value.(*ipEntry)
		if len(entry.times) == 0 || !entry.times[len(entry.times)-1].After(cutoff) {
			delete(l.requests, entry.key)
			l.order.Remove(elem)
			elem = prev
			continue
		}
		// order is sorted MRU->LRU by last recorded timestamp, so once we
		// reach a non-expired entry, all newer entries ahead of it are active.
		break
	}
}

// Allow reports whether a request from ip should be permitted right now,
// recording it if so.
func (l *IPLimiter) Allow(ip string) bool {
	now := time.Now()

	l.mu.Lock()
	defer l.mu.Unlock()

	cutoff := now.Add(-l.window)
	entry, exists := l.requests[ip]
	if exists {
		kept := entry.times[:0]
		for _, t := range entry.times {
			if t.After(cutoff) {
				kept = append(kept, t)
			}
		}
		if len(kept) >= l.limit {
			entry.times = kept
			l.order.MoveToFront(entry.elem)
			return false
		}
		entry.times = append(kept, now)
		l.order.MoveToFront(entry.elem)
		return true
	}

	if len(l.requests) >= l.maxKeys {
		l.sweepLocked(now)
		if len(l.requests) >= l.maxKeys {
			if oldest := l.order.Back(); oldest != nil {
				oldEntry := oldest.Value.(*ipEntry)
				delete(l.requests, oldEntry.key)
				l.order.Remove(oldest)
			}
		}
	}

	if l.limit <= 0 {
		return false
	}

	entry = &ipEntry{
		key:   ip,
		times: []time.Time{now},
	}
	entry.elem = l.order.PushFront(entry)
	l.requests[ip] = entry
	return true
}

type lockoutEntry struct {
	key   string
	times []time.Time
	elem  *list.Element
}

// AccountLockout tracks failed login attempts per (normalized) email,
// independent of whether the email corresponds to a real account -- so a
// lockout being triggered (or not) never leaks account existence. After
// maxFailures failed attempts inside window, further attempts for that
// email are rejected until lockFor has elapsed since the last failure.
//
// A background sweeper periodically evicts expired keys, and maxKeys caps
// total entries so memory stays bounded. Call Close (or cancel the context
// passed to NewAccountLockoutWithContext) to stop the sweeper goroutine.
type AccountLockout struct {
	mu          sync.Mutex
	attempts    map[string]*lockoutEntry
	order       *list.List // front = most recently updated, back = oldest
	maxFailures int
	window      time.Duration
	lockFor     time.Duration
	maxKeys     int

	cancel    context.CancelFunc
	done      chan struct{}
	closeOnce sync.Once
}

func NewAccountLockout(maxFailures int, window, lockFor time.Duration) *AccountLockout {
	return NewAccountLockoutWithContext(context.Background(), maxFailures, window, lockFor, DefaultMaxKeys, defaultLockoutSweepInterval)
}

// NewAccountLockoutWithContext creates an AccountLockout whose background
// sweeper stops when ctx is cancelled or Close is called. Non-positive
// maxKeys or sweepInterval fall back to their defaults.
func NewAccountLockoutWithContext(ctx context.Context, maxFailures int, window, lockFor time.Duration, maxKeys int, sweepInterval time.Duration) *AccountLockout {
	if maxKeys <= 0 {
		maxKeys = DefaultMaxKeys
	}
	if sweepInterval <= 0 {
		sweepInterval = defaultLockoutSweepInterval
	}
	if ctx == nil {
		ctx = context.Background()
	}

	sweepCtx, cancel := context.WithCancel(ctx)
	a := &AccountLockout{
		attempts:    make(map[string]*lockoutEntry),
		order:       list.New(),
		maxFailures: maxFailures,
		window:      window,
		lockFor:     lockFor,
		maxKeys:     maxKeys,
		cancel:      cancel,
		done:        make(chan struct{}),
	}

	go a.runSweeper(sweepCtx, sweepInterval)
	return a
}

func (a *AccountLockout) runSweeper(ctx context.Context, interval time.Duration) {
	defer close(a.done)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case now := <-ticker.C:
			a.sweep(now)
		}
	}
}

// Close stops the background sweeper goroutine and waits for it to exit.
// Safe to call multiple times.
func (a *AccountLockout) Close() {
	a.closeOnce.Do(func() {
		a.cancel()
		<-a.done
	})
}

// Len returns the current number of tracked email keys.
func (a *AccountLockout) Len() int {
	a.mu.Lock()
	defer a.mu.Unlock()
	return len(a.attempts)
}

func (a *AccountLockout) retentionDuration() time.Duration {
	if a.lockFor > a.window {
		return a.lockFor
	}
	return a.window
}

func (a *AccountLockout) sweep(now time.Time) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.sweepLocked(now)
}

func (a *AccountLockout) sweepLocked(now time.Time) {
	cutoff := now.Add(-a.retentionDuration())
	for elem := a.order.Back(); elem != nil; {
		prev := elem.Prev()
		entry := elem.Value.(*lockoutEntry)
		if len(entry.times) == 0 || !entry.times[len(entry.times)-1].After(cutoff) {
			delete(a.attempts, entry.key)
			a.order.Remove(elem)
			elem = prev
			continue
		}
		break
	}
}

// Locked reports whether email is currently locked out.
func (a *AccountLockout) Locked(email string) bool {
	email = normalizeEmail(email)
	now := time.Now()

	a.mu.Lock()
	defer a.mu.Unlock()

	entry, exists := a.attempts[email]
	if !exists || len(entry.times) < a.maxFailures {
		return false
	}
	last := entry.times[len(entry.times)-1]
	return now.Before(last.Add(a.lockFor))
}

// RecordFailure records a failed attempt for email.
func (a *AccountLockout) RecordFailure(email string) {
	email = normalizeEmail(email)
	now := time.Now()
	cutoff := now.Add(-a.window)

	a.mu.Lock()
	defer a.mu.Unlock()

	if entry, exists := a.attempts[email]; exists {
		kept := entry.times[:0]
		for _, t := range entry.times {
			if t.After(cutoff) {
				kept = append(kept, t)
			}
		}
		entry.times = append(kept, now)
		a.order.MoveToFront(entry.elem)
		return
	}

	if len(a.attempts) >= a.maxKeys {
		a.sweepLocked(now)
		if len(a.attempts) >= a.maxKeys {
			if oldest := a.order.Back(); oldest != nil {
				oldEntry := oldest.Value.(*lockoutEntry)
				delete(a.attempts, oldEntry.key)
				a.order.Remove(oldest)
			}
		}
	}

	entry := &lockoutEntry{
		key:   email,
		times: []time.Time{now},
	}
	entry.elem = a.order.PushFront(entry)
	a.attempts[email] = entry
}

// Reset clears failure history for email (called on successful login).
func (a *AccountLockout) Reset(email string) {
	email = normalizeEmail(email)
	a.mu.Lock()
	defer a.mu.Unlock()
	if entry, exists := a.attempts[email]; exists {
		delete(a.attempts, email)
		a.order.Remove(entry.elem)
	}
}
