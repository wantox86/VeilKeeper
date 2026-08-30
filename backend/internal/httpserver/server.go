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
)

// Pinger is satisfied by *sql.DB. Defined here (instead of importing
// database/sql directly into the handler signature) so the readiness
// handler can be unit-tested with a fake, without a real MySQL instance.
type Pinger interface {
	PingContext(ctx context.Context) error
}

// NewMux builds the HTTP router for the API server.
func NewMux(pinger Pinger, logger *slog.Logger) *http.ServeMux {
	mux := http.NewServeMux()

	mux.HandleFunc("GET /health", handleHealth)
	mux.HandleFunc("GET /ready", handleReady(pinger, logger))

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
