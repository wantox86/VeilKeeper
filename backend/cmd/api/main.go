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

	"github.com/wantox86/veilkeeper/backend/internal/config"
	"github.com/wantox86/veilkeeper/backend/internal/db"
	"github.com/wantox86/veilkeeper/backend/internal/httpserver"
	"github.com/wantox86/veilkeeper/backend/internal/store"
)

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
	mux := httpserver.NewMux(database, authStore, logger, cfg.Auth)

	srv := &http.Server{
		Addr:    ":" + cfg.HTTPPort,
		Handler: mux,
	}

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
