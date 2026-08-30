package httpserver

import (
	"net"
	"net/http"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
)

// rateLimited wraps next with a per-IP sliding-window rate limiter
// (SPEC-BASE.md Section 30, "Rate-limit authentication endpoints"). On
// rejection it returns 429 without hitting the store or auth logic at all,
// so a flood of requests never reaches Argon2id hashing.
//
// Known limitation: client IP is taken from r.RemoteAddr only (no
// X-Forwarded-For trust), which is correct for a direct connection but
// would need a trusted-proxy allowlist if this API is later placed behind a
// reverse proxy that isn't already stripping/setting that header itself.
func rateLimited(limiter *auth.IPLimiter, next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ip := clientIP(r)
		if !limiter.Allow(ip) {
			writeError(w, http.StatusTooManyRequests, "rate_limited", "too many requests, slow down")
			return
		}
		next(w, r)
	}
}

func clientIP(r *http.Request) string {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
}
