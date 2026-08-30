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

const userIDContextKey contextKey = iota

// requireSession wraps next with bearer-session authentication for all
// Sprint 2 vault/category routes. On success, the authenticated user's ID is
// injected into the request context (retrievable via userIDFromContext).
// This is the sole place that maps a bearer token to a user ID for these
// routes -- ownership enforcement in the store layer then does the rest
// (SPEC-BASE.md Section 30, "Authorization must be enforced server-side").
func requireSession(sessionStore store.AuthStore, logger *slog.Logger, nowFunc func() time.Time, next http.HandlerFunc) http.HandlerFunc {
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

		ctx := context.WithValue(r.Context(), userIDContextKey, sess.UserID)
		next(w, r.WithContext(ctx))
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
