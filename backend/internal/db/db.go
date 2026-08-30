// Package db manages the MySQL connection pool for the API server.
package db

import (
	"database/sql"
	"fmt"

	_ "github.com/go-sql-driver/mysql"

	"github.com/wantox86/veilkeeper/backend/internal/config"
)

// Open creates a *sql.DB connection pool configured from cfg. It does not
// block on connectivity -- database/sql connects lazily. Callers should use
// Ping (via the /ready handler) to verify actual reachability.
func Open(cfg config.DBConfig) (*sql.DB, error) {
	db, err := sql.Open("mysql", cfg.DSN())
	if err != nil {
		return nil, fmt.Errorf("open mysql connection pool: %w", err)
	}

	db.SetMaxOpenConns(cfg.MaxOpenConns)
	db.SetMaxIdleConns(cfg.MaxIdleConns)
	db.SetConnMaxLifetime(cfg.ConnMaxLifetime)

	return db, nil
}
