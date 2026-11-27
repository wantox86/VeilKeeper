// Command api is the VeilKeeper backend entrypoint.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/config"
	"github.com/wantox86/veilkeeper/backend/internal/db"
	"github.com/wantox86/veilkeeper/backend/internal/httpserver"
	"github.com/wantox86/veilkeeper/backend/internal/store"
)

const (
	// httpReadHeaderTimeout bounds how long the server waits to receive request
	// headers, mitigating Slowloris / slow-header DoS attacks.
	httpReadHeaderTimeout = 10 * time.Second

	// httpReadTimeout bounds the total time to read an entire request (headers +
	// body). Kept generous at 120s so slow mobile clients can still upload
	// encrypted attachments up to 10 MiB.
	httpReadTimeout = 120 * time.Second

	// httpWriteTimeout bounds the time from the end of request header read to
	// the end of response write. Kept generous at 120s so slow mobile clients
	// can still download encrypted attachments up to 10 MiB.
	httpWriteTimeout = 120 * time.Second

	// httpIdleTimeout caps how long idle keep-alive connections remain open so
	// they cannot hold server resources indefinitely.
	httpIdleTimeout = 120 * time.Second

	// httpMaxHeaderBytes explicitly caps request header size at 1 MiB.
	httpMaxHeaderBytes = 1 << 20
)

func newHTTPServer(addr string, handler http.Handler) *http.Server {
	return &http.Server{
		Addr:              addr,
		Handler:           handler,
		ReadHeaderTimeout: httpReadHeaderTimeout,
		ReadTimeout:       httpReadTimeout,
		WriteTimeout:      httpWriteTimeout,
		IdleTimeout:       httpIdleTimeout,
		MaxHeaderBytes:    httpMaxHeaderBytes,
	}
}

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, nil))

	cfg := config.Load(logger)

	database, err := db.Open(cfg.DB)
	if err != nil {
		logger.Error("failed to initialize database pool", "error", err.Error())
		os.Exit(1)
	}
	defer database.Close()

	authStore := store.NewMySQLStore(database)
	mux := httpserver.NewMux(database, authStore, logger, cfg.Auth, cfg.AttachmentsDir, cfg.CORSAllowedOrigins)

	srv := newHTTPServer(":"+cfg.HTTPPort, mux)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	go func() {
		logger.Info("starting server", "port", cfg.HTTPPort)
		if err := srv.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("server error", "error", err.Error())
			os.Exit(1)
		}
	}()

	<-ctx.Done()
	logger.Info("shutting down")

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.ShutdownTimeout)
	defer cancel()

	if err := srv.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", "error", err.Error())
		os.Exit(1)
	}

	logger.Info("shutdown complete")
}
