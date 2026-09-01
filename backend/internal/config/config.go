// Package config loads runtime configuration from environment variables.
//
// VeilKeeper never hardcodes secrets (DB credentials, JWT secrets, etc). All
// configuration must come from the environment (see .env.example at the repo
// root). This file only handles non-secret defaults for local convenience.
package config

import (
	"crypto/rand"
	"fmt"
	"log/slog"
	"os"
	"strconv"
	"strings"
	"time"
)

// Config holds all runtime configuration for the API server.
type Config struct {
	// HTTPPort is the TCP port the HTTP server listens on inside the
	// container (mapped to host port 18091 in docker-compose.yml).
	HTTPPort string

	// ShutdownTimeout bounds how long graceful shutdown waits for
	// in-flight requests to finish.
	ShutdownTimeout time.Duration

	DB DBConfig

	Auth AuthConfig

	// AttachmentsDir is the root directory encrypted attachment blobs are
	// written to/read from (SPEC-BASE.md Section 7). Must match the
	// docker-compose.yml bind mount target (/data/attachments).
	AttachmentsDir string

	// CORSAllowedOrigins is an explicit allowlist of browser origins allowed
	// to make cross-origin requests (e.g. the Web client dev server, and
	// eventually its Sprint 8 internal/LAN deployment origin). Deliberately
	// never a wildcard, since this is a zero-knowledge auth backend -- see
	// CORS_ALLOWED_ORIGINS in .env.example and httpserver/cors.go. Requests
	// with no Origin header at all (e.g. the Android app's native HTTP
	// client) are unaffected regardless of this list's contents.
	CORSAllowedOrigins []string
}

// AuthConfig holds Sprint 1 authentication settings.
type AuthConfig struct {
	// ServerPepper is a server-side secret used only to derive a
	// deterministic *fake* KDF salt for /auth/prelogin anti-enumeration
	// (see CLAUDE.md Resolved Design Decision #1). It is never used to
	// derive or store any real cryptographic key material. It must NOT be
	// logged.
	ServerPepper []byte

	// SessionTTL is how long a session token remains valid after login.
	SessionTTL time.Duration

	// RateLimit settings for auth endpoints (SPEC-BASE.md Section 30).
	RateLimitRequestsPerWindow int
	RateLimitWindow            time.Duration
}

// DBConfig holds MySQL connection settings.
type DBConfig struct {
	Host     string
	Port     string
	User     string
	Password string
	Name     string

	MaxOpenConns    int
	MaxIdleConns    int
	ConnMaxLifetime time.Duration
}

// Load reads configuration from environment variables, applying sane
// defaults for local development. It does not fail on missing DB
// credentials at load time -- connectivity is verified separately by
// /ready, per SPEC-BASE.md Section 53/54.
//
// logger is used only to warn about non-fatal misconfiguration (e.g. a
// missing SERVER_PEPPER). It may be nil, in which case warnings are
// silently skipped (used by tests).
func Load(logger *slog.Logger) Config {
	pepper := loadPepper(logger)

	return Config{
		HTTPPort:        getEnv("HTTP_PORT", "8080"),
		ShutdownTimeout: 10 * time.Second,
		DB: DBConfig{
			Host:            getEnv("DB_HOST", "mysql"),
			Port:            getEnv("DB_PORT", "3306"),
			User:            getEnv("DB_USER", "veilkeeper"),
			Password:        getEnv("DB_PASSWORD", ""),
			Name:            getEnv("DB_NAME", "veilkeeper"),
			MaxOpenConns:    10,
			MaxIdleConns:    5,
			ConnMaxLifetime: 5 * time.Minute,
		},
		Auth: AuthConfig{
			ServerPepper:               pepper,
			SessionTTL:                 getEnvDuration("SESSION_TTL_HOURS", 720) * time.Hour,
			RateLimitRequestsPerWindow: getEnvInt("AUTH_RATE_LIMIT_REQUESTS", 20),
			RateLimitWindow:            time.Minute,
		},
		AttachmentsDir:     getEnv("ATTACHMENTS_DIR", "/data/attachments"),
		CORSAllowedOrigins: getEnvList("CORS_ALLOWED_ORIGINS", []string{"http://localhost:5173", "http://127.0.0.1:5173"}),
	}
}

// loadPepper reads SERVER_PEPPER (expected: base64 or any opaque string, at
// least 16 bytes) from the environment. If unset, it generates a random
// ephemeral pepper for this process only and logs a warning -- acceptable
// for a single-instance homelab dev deployment (see CLAUDE.md), but
// production deployments should set SERVER_PEPPER explicitly so the
// anti-enumeration fake-salt behavior stays stable across restarts. Never
// logs the pepper value itself.
func loadPepper(logger *slog.Logger) []byte {
	if v := os.Getenv("SERVER_PEPPER"); v != "" {
		return []byte(v)
	}

	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		// crypto/rand failing is fatal-grade, but Load() has no error
		// return; panic here is acceptable since this only happens on a
		// broken OS entropy source.
		panic("config: failed to generate ephemeral SERVER_PEPPER: " + err.Error())
	}
	if logger != nil {
		logger.Warn("SERVER_PEPPER not set; using an ephemeral random pepper for this process (set SERVER_PEPPER explicitly in production)")
	}
	return buf
}

func getEnvInt(key string, fallback int) int {
	if v := os.Getenv(key); v != "" {
		if n, err := strconv.Atoi(v); err == nil {
			return n
		}
	}
	return fallback
}

func getEnvDuration(key string, fallbackHours int) time.Duration {
	return time.Duration(getEnvInt(key, fallbackHours))
}

// DSN builds a go-sql-driver/mysql compatible DSN. Never log the return
// value -- it contains the DB password.
func (c DBConfig) DSN() string {
	return fmt.Sprintf("%s:%s@tcp(%s:%s)/%s?parseTime=true&multiStatements=false",
		c.User, c.Password, c.Host, c.Port, c.Name)
}

func getEnv(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}

// getEnvList reads a comma-separated env var into a trimmed, non-empty
// string slice. Falls back if the var is unset; an explicitly-set-but-empty
// value ("") also falls back rather than producing an allow-nothing list,
// since that's almost certainly not what an operator intended.
func getEnvList(key string, fallback []string) []string {
	v := os.Getenv(key)
	if v == "" {
		return fallback
	}

	parts := strings.Split(v, ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		if p != "" {
			out = append(out, p)
		}
	}
	if len(out) == 0 {
		return fallback
	}
	return out
}
