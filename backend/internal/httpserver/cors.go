package httpserver

import "net/http"

// corsMaxAgeSeconds bounds how long a browser may cache a preflight
// response before re-checking (600s = 10 minutes -- long enough to avoid a
// preflight round trip on every request, short enough that an allowlist
// change propagates quickly).
const corsMaxAgeSeconds = "600"

// corsMiddleware adds CORS response headers for requests from an
// allowlisted browser Origin, and short-circuits preflight OPTIONS
// requests. It is intentionally minimal (Go stdlib only, no third-party CORS
// framework, matching this backend's stdlib-only design) and intentionally
// narrow:
//
//   - No Origin header at all (native HTTP clients like the Android app,
//     curl, server-to-server calls) -> completely transparent. The request
//     is passed through to next unmodified, no CORS headers attached, no
//     behavior change whatsoever. This is what keeps the live Android app
//     unaffected by this change.
//   - Origin header present but NOT in allowedOrigins -> also transparent
//     for non-preflight requests (the request still reaches next; the
//     browser will block the response client-side because no
//     Access-Control-Allow-Origin header appears in the response, which is
//     the browser's own enforcement, not this server's). A non-allowlisted
//     preflight OPTIONS request gets a plain 204 with no CORS headers,
//     which the browser will also refuse to honor.
//   - Origin header present AND allowlisted -> the matched origin is echoed
//     back in Access-Control-Allow-Origin (never "*" -- this is an
//     authenticated API using bearer tokens, and even though credentials
//     mode is never enabled here, echoing a specific matched origin is
//     still the more conservative choice). Access-Control-Allow-Credentials
//     is deliberately never set: auth uses a Bearer token in the
//     Authorization header, not cookies, so it isn't needed.
//
// Preflight (OPTIONS) requests are answered directly with 204 and never
// forwarded to next, per the standard CORS preflight contract.
func corsMiddleware(allowedOrigins []string, next http.Handler) http.Handler {
	allowed := make(map[string]bool, len(allowedOrigins))
	for _, o := range allowedOrigins {
		allowed[o] = true
	}

	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")

		if origin != "" && allowed[origin] {
			h := w.Header()
			h.Set("Access-Control-Allow-Origin", origin)
			h.Set("Vary", "Origin")
			h.Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			h.Set("Access-Control-Allow-Headers", "Authorization, Content-Type")
			h.Set("Access-Control-Max-Age", corsMaxAgeSeconds)
		}

		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}

		next.ServeHTTP(w, r)
	})
}
