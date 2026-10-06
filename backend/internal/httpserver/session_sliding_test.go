package httpserver

import (
	"context"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
)

// slidingFixture creates a session in fs with the given created/expires
// offsets relative to a fixed "now" and returns a handler wrapped with
// requireSession using the fixed clock.
func slidingFixture(t *testing.T, fs *fakeAuthStore, token string, createdAgo, expiresIn, ttl, maxLife time.Duration) (http.HandlerFunc, time.Time, string) {
	t.Helper()
	now := time.Date(2026, 1, 1, 12, 0, 0, 0, time.UTC)
	hash := auth.HashSessionToken(token)
	if _, err := fs.CreateSession(context.Background(), 1, 1, hash, now.Add(expiresIn)); err != nil {
		t.Fatal(err)
	}
	s := fs.sessions[hash]
	s.CreatedAt = now.Add(-createdAgo)
	fs.sessions[hash] = s
	h := requireSession(fs, discardLogger(), func() time.Time { return now }, ttl, maxLife,
		func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	return h, now, hash
}

func callWith(h http.HandlerFunc, token string) int {
	req := httptest.NewRequest(http.MethodGet, "/x", nil)
	req.Header.Set("Authorization", "Bearer "+token)
	rec := httptest.NewRecorder()
	h(rec, req)
	return rec.Code
}

func TestSlidingSession_ExtendsWhenBelowHalfTTL(t *testing.T) {
	fs := newFakeAuthStore()
	ttl := 720 * time.Hour
	h, now, hash := slidingFixture(t, fs, "tok-a", 29*24*time.Hour, 24*time.Hour, ttl, 0)
	if code := callWith(h, "tok-a"); code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	if got := fs.sessions[hash].ExpiresAt; !got.Equal(now.Add(ttl)) {
		t.Fatalf("expected expiry %v, got %v", now.Add(ttl), got)
	}
}

func TestSlidingSession_NotExtendedAboveHalfTTL(t *testing.T) {
	fs := newFakeAuthStore()
	ttl := 720 * time.Hour
	h, now, hash := slidingFixture(t, fs, "tok-b", time.Hour, ttl-time.Hour, ttl, 0)
	before := fs.sessions[hash].ExpiresAt
	if code := callWith(h, "tok-b"); code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	if got := fs.sessions[hash].ExpiresAt; !got.Equal(before) || fs.extendCalls != 0 {
		t.Fatalf("expiry must not change (now=%v): before=%v got=%v calls=%d", now, before, got, fs.extendCalls)
	}
}

func TestSlidingSession_ExpiredRejectedAndNotRevived(t *testing.T) {
	fs := newFakeAuthStore()
	h, _, hash := slidingFixture(t, fs, "tok-c", 31*24*time.Hour, -time.Minute, 720*time.Hour, 0)
	before := fs.sessions[hash].ExpiresAt
	if code := callWith(h, "tok-c"); code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", code)
	}
	if !fs.sessions[hash].ExpiresAt.Equal(before) || fs.extendCalls != 0 {
		t.Fatal("expired session must not be extended")
	}
}

func TestSlidingSession_RevokedRejectedAndNotExtended(t *testing.T) {
	fs := newFakeAuthStore()
	h, _, hash := slidingFixture(t, fs, "tok-d", 29*24*time.Hour, time.Hour, 720*time.Hour, 0)
	if err := fs.RevokeSession(context.Background(), hash); err != nil {
		t.Fatal(err)
	}
	before := fs.sessions[hash].ExpiresAt
	if code := callWith(h, "tok-d"); code != http.StatusUnauthorized {
		t.Fatalf("want 401, got %d", code)
	}
	if !fs.sessions[hash].ExpiresAt.Equal(before) || fs.extendCalls != 0 {
		t.Fatal("revoked session must not be extended")
	}
}

func TestSlidingSession_AbsoluteMaxLifetimeCap(t *testing.T) {
	fs := newFakeAuthStore()
	ttl := 720 * time.Hour
	// created 89 days ago, max 90 days -> cap is created+90d = now+1d.
	h, now, hash := slidingFixture(t, fs, "tok-e", 89*24*time.Hour, 24*time.Hour-time.Second, ttl, 90*24*time.Hour)
	if code := callWith(h, "tok-e"); code != http.StatusOK {
		t.Fatalf("want 200, got %d", code)
	}
	want := now.Add(24 * time.Hour)
	if got := fs.sessions[hash].ExpiresAt; !got.Equal(want) {
		t.Fatalf("expected capped expiry %v, got %v", want, got)
	}
	// Already at the cap: no further extension.
	fs.extendCalls = 0
	callWith(h, "tok-e")
	if fs.extendCalls != 0 {
		t.Fatal("must not extend past the absolute cap")
	}
}
