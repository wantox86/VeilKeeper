package httpserver

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/config"
)

// testAuthConfig returns a minimal AuthConfig suitable for tests that don't
// exercise the auth routes directly (health/ready tests only need NewMux to
// construct without panicking).
func testAuthConfig() config.AuthConfig {
	return config.AuthConfig{
		ServerPepper:               []byte("test-pepper-not-a-real-secret"),
		SessionTTL:                 24 * time.Hour,
		RateLimitRequestsPerWindow: 1000,
		RateLimitWindow:            time.Minute,
	}
}

// testCORSOrigins returns a minimal allowlist for tests that don't
// exercise CORS behavior directly (health/ready tests only need NewMux to
// construct without panicking).
func testCORSOrigins() []string {
	return []string{"http://localhost:5173"}
}

// fakePinger lets us unit-test the readiness handler without a real MySQL
// instance.
type fakePinger struct {
	err error
}

func (f fakePinger) PingContext(_ context.Context) error {
	return f.err
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

func decodeStatus(t *testing.T, body io.Reader) statusResponse {
	t.Helper()
	var s statusResponse
	if err := json.NewDecoder(body).Decode(&s); err != nil {
		t.Fatalf("decode response body: %v", err)
	}
	return s
}

func TestHandleHealth(t *testing.T) {
	mux := NewMux(fakePinger{}, nil, discardLogger(), testAuthConfig(), t.TempDir(), testCORSOrigins())

	req := httptest.NewRequest(http.MethodGet, "/health", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	if got := decodeStatus(t, rec.Body).Status; got != "ok" {
		t.Fatalf("expected status body 'ok', got %q", got)
	}
}

func TestHandleReady_DatabaseUp(t *testing.T) {
	mux := NewMux(fakePinger{err: nil}, nil, discardLogger(), testAuthConfig(), t.TempDir(), testCORSOrigins())

	req := httptest.NewRequest(http.MethodGet, "/ready", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected status 200, got %d", rec.Code)
	}
	if got := decodeStatus(t, rec.Body).Status; got != "ready" {
		t.Fatalf("expected status body 'ready', got %q", got)
	}
}

func TestHandleReady_DatabaseDown(t *testing.T) {
	mux := NewMux(fakePinger{err: errors.New("connection refused")}, nil, discardLogger(), testAuthConfig(), t.TempDir(), testCORSOrigins())

	req := httptest.NewRequest(http.MethodGet, "/ready", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	if rec.Code != http.StatusServiceUnavailable {
		t.Fatalf("expected status 503, got %d", rec.Code)
	}
	if got := decodeStatus(t, rec.Body).Status; got != "unavailable" {
		t.Fatalf("expected status body 'unavailable', got %q", got)
	}
}

func TestHandleReady_ErrorNotLeaked(t *testing.T) {
	mux := NewMux(fakePinger{err: errors.New("secret internal detail: dsn=user:pass@tcp(...)")}, nil, discardLogger(), testAuthConfig(), t.TempDir(), testCORSOrigins())

	req := httptest.NewRequest(http.MethodGet, "/ready", nil)
	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, req)

	body := rec.Body.String()
	if want := "secret internal detail"; contains(body, want) {
		t.Fatalf("response body must not leak internal error details, got: %s", body)
	}
}

func contains(haystack, needle string) bool {
	return len(haystack) >= len(needle) && (func() bool {
		for i := 0; i+len(needle) <= len(haystack); i++ {
			if haystack[i:i+len(needle)] == needle {
				return true
			}
		}
		return false
	})()
}
