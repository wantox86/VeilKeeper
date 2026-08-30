// Package config loads runtime configuration from environment variables.
//
// VeilKeeper never hardcodes secrets (DB credentials, JWT secrets, etc). All
// configuration must come from the environment (see .env.example at the repo
// root). This file only handles non-secret defaults for local convenience.
package config

import (
	"fmt"
	"os"
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
func Load() Config {
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
	}
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
