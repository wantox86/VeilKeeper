package httpserver

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// contextKey avoids collisions with other packages' context values.
type contextKey int

const (
	userIDContextKey contextKey = iota
	deviceIDContextKey
)

// requireSession wraps next with bearer-session authentication for all
// Sprint 2 vault/category routes. On success, the authenticated user's ID is
// injected into the request context (retrievable via userIDFromContext).
// This is the sole place that maps a bearer token to a user ID for these
// routes -- ownership enforcement in the store layer then does the rest
// (SPEC-BASE.md Section 30, "Authorization must be enforced server-side").
func requireSession(sessionStore store.AuthStore, logger *slog.Logger, nowFunc func() time.Time, ttl, maxLifetime time.Duration, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		token, ok := bearerToken(r)
		if !ok {
			writeError(w, http.StatusUnauthorized, "unauthorized", "missing or malformed Authorization header")
			return
		}

		sess, err := sessionStore.GetSessionByTokenHash(r.Context(), auth.HashSessionToken(token))
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusUnauthorized, "unauthorized", "invalid session")
			return
		}
		if err != nil {
			logger.Error("session auth: store lookup failed", "error", err.Error())
			writeInternalError(w)
			return
		}

		now := time.Now()
		if nowFunc != nil {
			now = nowFunc()
		}
		if !sess.Valid(now) {
			writeError(w, http.StatusUnauthorized, "unauthorized", "session expired or revoked")
			return
		}

		maybeExtendSession(r.Context(), sessionStore, logger, sess, auth.HashSessionToken(token), now, ttl, maxLifetime)

		ctx := context.WithValue(r.Context(), userIDContextKey, sess.UserID)
		ctx = context.WithValue(ctx, deviceIDContextKey, sess.DeviceID)
		next(w, r.WithContext(ctx))
	}
}

// maybeExtendSession implements the sliding session window: a successfully
// authenticated request pushes expires_at out to now+ttl, so a session only
// expires after ttl of inactivity. To keep DB writes off the hot path it only
// writes once less than half of ttl remains (so at most ~1 write per ttl/2 per
// session). If maxLifetime > 0 the new expiry is capped at
// created_at+maxLifetime (absolute lifetime). Failures are logged and
// swallowed: the request was already authenticated and a missed extension is
// harmless (the next request retries).
func maybeExtendSession(ctx context.Context, st store.AuthStore, logger *slog.Logger, sess store.Session, tokenHash string, now time.Time, ttl, maxLifetime time.Duration) {
	if ttl <= 0 {
		return
	}
	if sess.ExpiresAt.Sub(now) >= ttl/2 {
		return
	}
	newExpiry := now.Add(ttl)
	if maxLifetime > 0 {
		if capAt := sess.CreatedAt.Add(maxLifetime); newExpiry.After(capAt) {
			newExpiry = capAt
		}
	}
	if !newExpiry.After(sess.ExpiresAt) {
		return
	}
	if _, err := st.ExtendSession(ctx, tokenHash, newExpiry, now); err != nil {
		logger.Error("session auth: extend failed", "error", err.Error())
	}
}

// userIDFromContext returns the authenticated user's ID set by
// requireSession. It panics if called on a request that didn't go through
// requireSession -- a programming error (missing middleware on a route),
// not a runtime condition callers should handle gracefully.
func userIDFromContext(ctx context.Context) int64 {
	id, ok := ctx.Value(userIDContextKey).(int64)
	if !ok {
		panic("httpserver: userIDFromContext called without requireSession middleware")
	}
	return id
}

// deviceIDFromContext returns the device ID of the session that
// authenticated the current request, as set by requireSession. Unlike
// userIDFromContext it does not panic on a miss -- it returns (0, false) --
// since it's expected to be called from code paths (Phase 1's device
// handlers, see plan.md) that need to distinguish "this is the device
// making the request" from "this is some other device in the list" without
// treating a miss as a programming error.
func deviceIDFromContext(ctx context.Context) (int64, bool) {
	id, ok := ctx.Value(deviceIDContextKey).(int64)
	return id, ok
}
