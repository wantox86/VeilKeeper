package httpserver

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

func newTestHandler() http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})
}

func TestCORSMiddleware_AllowedOrigin(t *testing.T) {
	h := corsMiddleware([]string{"http://localhost:5173"}, newTestHandler())

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "http://localhost:5173" {
		t.Fatalf("expected Access-Control-Allow-Origin to echo the matched origin, got %q", got)
	}
	if got := rec.Header().Get("Vary"); got != "Origin" {
		t.Fatalf("expected Vary: Origin, got %q", got)
	}
	if got := rec.Header().Get("Access-Control-Allow-Credentials"); got != "" {
		t.Fatalf("Access-Control-Allow-Credentials must never be set (bearer-token auth, not cookies), got %q", got)
	}
	if rec.Body.String() != "ok" {
		t.Fatalf("expected request to reach the wrapped handler, got body %q", rec.Body.String())
	}
}

func TestCORSMiddleware_DisallowedOrigin_NoHeaderButStillProcessed(t *testing.T) {
	h := corsMiddleware([]string{"http://localhost:5173"}, newTestHandler())

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	req.Header.Set("Origin", "https://evil.example.com")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("non-allowlisted origin must not get Access-Control-Allow-Origin, got %q", got)
	}
	// The request still reaches the handler -- it's the browser's own
	// enforcement (missing header) that blocks JS from reading the
	// response, not a server-side rejection.
	if rec.Code != http.StatusOK || rec.Body.String() != "ok" {
		t.Fatalf("expected request to still be processed normally, got code=%d body=%q", rec.Code, rec.Body.String())
	}
}

func TestCORSMiddleware_NoOriginHeader_Transparent(t *testing.T) {
	// Simulates the Android app / curl / any native client: no Origin
	// header at all. Must be completely unaffected.
	h := corsMiddleware([]string{"http://localhost:5173"}, newTestHandler())

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("request without Origin header must not get any CORS header, got %q", got)
	}
	if rec.Code != http.StatusOK || rec.Body.String() != "ok" {
		t.Fatalf("expected request to be processed normally, got code=%d body=%q", rec.Code, rec.Body.String())
	}
}

func TestCORSMiddleware_Preflight_AllowedOrigin(t *testing.T) {
	h := corsMiddleware([]string{"http://localhost:5173"}, newTestHandler())

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/auth/login", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	req.Header.Set("Access-Control-Request-Method", "POST")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("expected 204 for preflight, got %d", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "http://localhost:5173" {
		t.Fatalf("expected Access-Control-Allow-Origin on preflight, got %q", got)
	}
	if got := rec.Header().Get("Access-Control-Allow-Methods"); got == "" {
		t.Fatalf("expected Access-Control-Allow-Methods on preflight")
	}
	if got := rec.Header().Get("Access-Control-Allow-Headers"); got == "" {
		t.Fatalf("expected Access-Control-Allow-Headers on preflight")
	}
	if got := rec.Header().Get("Access-Control-Max-Age"); got == "" {
		t.Fatalf("expected Access-Control-Max-Age on preflight")
	}
	if rec.Body.Len() != 0 {
		t.Fatalf("preflight response body must be empty, got %q", rec.Body.String())
	}
}

func TestCORSMiddleware_Preflight_NeverReachesNextHandler(t *testing.T) {
	called := false
	next := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		called = true
		w.WriteHeader(http.StatusOK)
	})
	h := corsMiddleware([]string{"http://localhost:5173"}, next)

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/auth/login", nil)
	req.Header.Set("Origin", "http://localhost:5173")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if called {
		t.Fatalf("preflight OPTIONS must be short-circuited, never reach the wrapped handler")
	}
}

func TestCORSMiddleware_Preflight_DisallowedOrigin_NoHeaders(t *testing.T) {
	h := corsMiddleware([]string{"http://localhost:5173"}, newTestHandler())

	req := httptest.NewRequest(http.MethodOptions, "/api/v1/auth/login", nil)
	req.Header.Set("Origin", "https://evil.example.com")
	rec := httptest.NewRecorder()
	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusNoContent {
		t.Fatalf("expected 204 even for disallowed preflight (browser refuses to honor it without headers), got %d", rec.Code)
	}
	if got := rec.Header().Get("Access-Control-Allow-Origin"); got != "" {
		t.Fatalf("disallowed preflight must not get Access-Control-Allow-Origin, got %q", got)
	}
}
