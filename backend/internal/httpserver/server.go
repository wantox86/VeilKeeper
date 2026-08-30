// Package httpserver wires up the API server's HTTP routes.
//
// Sprint 0 scope: only the health/readiness endpoints exist (SPEC-BASE.md
// Section 53-54). Auth/vault/attachment routes land in later sprints.
package httpserver

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
	"github.com/wantox86/veilkeeper/backend/internal/config"
	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// Pinger is satisfied by *sql.DB. Defined here (instead of importing
// database/sql directly into the handler signature) so the readiness
// handler can be unit-tested with a fake, without a real MySQL instance.
type Pinger interface {
	PingContext(ctx context.Context) error
}

// Account lockout tuning (SPEC-BASE.md Section 30/47): after 5 failed
// attempts for the same email within 15 minutes, further attempts for that
// email are rejected for 5 minutes. Applied uniformly regardless of whether
// the email corresponds to a real account, so lockout behavior itself is
// not an enumeration oracle.
const (
	accountLockoutMaxFailures = 5
	accountLockoutWindow      = 15 * time.Minute
	accountLockoutDuration    = 5 * time.Minute
)

// NewMux builds the HTTP router for the API server. st may be nil if the
// caller only intends to exercise /health and /ready (as in this package's
// own unit tests) -- the auth/vault routes will panic if hit against a nil
// store, but that's not exercised by those tests.
func NewMux(pinger Pinger, st store.Store, logger *slog.Logger, authCfg config.AuthConfig, attachmentsDir string) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", handleHealth)
	mux.HandleFunc("GET /ready", handleReady(pinger, logger))

	deps := &authDeps{
		store:   st,
		logger:  logger,
		cfg:     authCfg,
		lockout: auth.NewAccountLockout(accountLockoutMaxFailures, accountLockoutWindow, accountLockoutDuration),
	}
	ipLimiter := auth.NewIPLimiter(authCfg.RateLimitRequestsPerWindow, authCfg.RateLimitWindow)

	mux.HandleFunc("POST /api/v1/auth/prelogin", rateLimited(ipLimiter, deps.handlePrelogin))
	mux.HandleFunc("POST /api/v1/auth/register", rateLimited(ipLimiter, deps.handleRegister))
	mux.HandleFunc("POST /api/v1/auth/login", rateLimited(ipLimiter, deps.handleLogin))
	mux.HandleFunc("POST /api/v1/auth/logout", rateLimited(ipLimiter, deps.handleLogout))

	// Sprint 2: vault foundation routes. All require a valid bearer session
	// (requireSession), which injects the authenticated user ID used for
	// ownership scoping in every store call these handlers make.
	vDeps := &vaultDeps{store: st, logger: logger, attachmentsDir: attachmentsDir}
	withAuth := func(h http.HandlerFunc) http.HandlerFunc { return requireSession(st, logger, nil, h) }

	mux.HandleFunc("GET /api/v1/categories", withAuth(vDeps.handleListCategories))
	mux.HandleFunc("POST /api/v1/categories", withAuth(vDeps.handleCreateCategory))
	mux.HandleFunc("PUT /api/v1/categories/{id}", withAuth(vDeps.handleRenameCategory))
	mux.HandleFunc("DELETE /api/v1/categories/{id}", withAuth(vDeps.handleDeleteCategory))

	mux.HandleFunc("GET /api/v1/vault/items", withAuth(vDeps.handleListVaultItems))
	mux.HandleFunc("POST /api/v1/vault/items", withAuth(vDeps.handleCreateVaultItem))
	mux.HandleFunc("GET /api/v1/vault/items/{id}", withAuth(vDeps.handleGetVaultItem))
	mux.HandleFunc("PUT /api/v1/vault/items/{id}", withAuth(vDeps.handleUpdateVaultItem))
	mux.HandleFunc("DELETE /api/v1/vault/items/{id}", withAuth(vDeps.handleDeleteVaultItem))

	// Sprint 5: attachments (SPEC-BASE.md Section 29). Ownership is checked
	// twice per request: {id} must be a vault item owned by the caller, and
	// {attachmentId} must both belong to the caller AND be attached to that
	// same {id} (see attachment_handlers.go).
	mux.HandleFunc("POST /api/v1/vault/items/{id}/attachments", withAuth(vDeps.handleUploadAttachment))
	mux.HandleFunc("GET /api/v1/vault/items/{id}/attachments/{attachmentId}", withAuth(vDeps.handleGetAttachment))
	mux.HandleFunc("DELETE /api/v1/vault/items/{id}/attachments/{attachmentId}", withAuth(vDeps.handleDeleteAttachment))

	return mux
}

type statusResponse struct {
	Status string `json:"status"`
}

// handleHealth is a pure liveness check: if the process can respond at all,
// it's alive. It must never depend on the database, so a DB outage doesn't
// make the container get killed/restarted unnecessarily by an orchestrator
// health check tied to /health.
func handleHealth(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, statusResponse{Status: "ok"})
}

// handleReady is a readiness check: the API is only "ready" to serve traffic
// if it can reach required infrastructure (MySQL), per SPEC-BASE.md Section
// 54 ("API should not be considered healthy if it cannot reach required
// infrastructure").
func handleReady(pinger Pinger, logger *slog.Logger) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		ctx, cancel := context.WithTimeout(r.Context(), 3*time.Second)
		defer cancel()

		if err := pinger.PingContext(ctx); err != nil {
			logger.Warn("readiness check failed: database unreachable")
			writeJSON(w, http.StatusServiceUnavailable, statusResponse{Status: "unavailable"})
			return
		}

		writeJSON(w, http.StatusOK, statusResponse{Status: "ready"})
	}
}

func writeJSON(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}
