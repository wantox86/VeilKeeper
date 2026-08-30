package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/go-sql-driver/mysql"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
)

// MySQLStore implements AuthStore against the schema in
// infra/mysql/init/002-auth-schema.sql.
type MySQLStore struct {
	db *sql.DB
}

// NewMySQLStore wraps an existing *sql.DB connection pool.
func NewMySQLStore(db *sql.DB) *MySQLStore {
	return &MySQLStore{db: db}
}

const mysqlDuplicateEntry = 1062

func (s *MySQLStore) GetUserByEmail(ctx context.Context, email string) (User, error) {
	email = auth.NormalizeEmail(email)

	row := s.db.QueryRowContext(ctx, `
		SELECT id, email, username, auth_key_hash, kdf_salt, kdf_params, kdf_version, wrapped_vdk, created_at, updated_at
		FROM users WHERE email = ?`, email)

	var u User
	var username sql.NullString
	var kdfParamsRaw []byte
	if err := row.Scan(&u.ID, &u.Email, &username, &u.AuthKeyHash, &u.KDFSalt, &kdfParamsRaw, &u.KDFVersion, &u.WrappedVDK, &u.CreatedAt, &u.UpdatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return User{}, ErrNotFound
		}
		return User{}, fmt.Errorf("store: get user by email: %w", err)
	}
	u.Username = username.String

	if err := json.Unmarshal(kdfParamsRaw, &u.KDFParams); err != nil {
		return User{}, fmt.Errorf("store: decode kdf_params: %w", err)
	}
	return u, nil
}

func (s *MySQLStore) CreateUser(ctx context.Context, u NewUser) (int64, error) {
	email := auth.NormalizeEmail(u.Email)

	kdfParamsRaw, err := json.Marshal(u.KDFParams)
	if err != nil {
		return 0, fmt.Errorf("store: encode kdf_params: %w", err)
	}

	res, err := s.db.ExecContext(ctx, `
		INSERT INTO users (email, username, auth_key_hash, kdf_salt, kdf_params, kdf_version, wrapped_vdk)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		email, nullableString(u.Username), u.AuthKeyHash, u.KDFSalt, kdfParamsRaw, u.KDFVersion, u.WrappedVDK)
	if err != nil {
		var mysqlErr *mysql.MySQLError
		if errors.As(err, &mysqlErr) && mysqlErr.Number == mysqlDuplicateEntry {
			return 0, ErrAlreadyExists
		}
		return 0, fmt.Errorf("store: create user: %w", err)
	}

	id, err := res.LastInsertId()
	if err != nil {
		return 0, fmt.Errorf("store: create user: read insert id: %w", err)
	}
	return id, nil
}

func (s *MySQLStore) UpsertDevice(ctx context.Context, userID int64, deviceIdentifier, deviceName string) (int64, error) {
	if deviceIdentifier == "" {
		deviceIdentifier = "unknown"
	}

	_, err := s.db.ExecContext(ctx, `
		INSERT INTO devices (user_id, device_identifier, device_name, last_seen_at)
		VALUES (?, ?, ?, NOW())
		ON DUPLICATE KEY UPDATE device_name = VALUES(device_name), last_seen_at = NOW()`,
		userID, deviceIdentifier, nullableString(deviceName))
	if err != nil {
		return 0, fmt.Errorf("store: upsert device: %w", err)
	}

	var id int64
	row := s.db.QueryRowContext(ctx, `
		SELECT id FROM devices WHERE user_id = ? AND device_identifier = ?`, userID, deviceIdentifier)
	if err := row.Scan(&id); err != nil {
		return 0, fmt.Errorf("store: read device id: %w", err)
	}
	return id, nil
}

func (s *MySQLStore) CreateSession(ctx context.Context, userID, deviceID int64, tokenHash string, expiresAt time.Time) (int64, error) {
	res, err := s.db.ExecContext(ctx, `
		INSERT INTO sessions (user_id, device_id, token_hash, expires_at)
		VALUES (?, ?, ?, ?)`, userID, deviceID, tokenHash, expiresAt)
	if err != nil {
		return 0, fmt.Errorf("store: create session: %w", err)
	}
	id, err := res.LastInsertId()
	if err != nil {
		return 0, fmt.Errorf("store: create session: read insert id: %w", err)
	}
	return id, nil
}

func (s *MySQLStore) GetSessionByTokenHash(ctx context.Context, tokenHash string) (Session, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, user_id, device_id, token_hash, created_at, expires_at, revoked_at
		FROM sessions WHERE token_hash = ?`, tokenHash)

	var sess Session
	var revokedAt sql.NullTime
	if err := row.Scan(&sess.ID, &sess.UserID, &sess.DeviceID, &sess.TokenHash, &sess.CreatedAt, &sess.ExpiresAt, &revokedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Session{}, ErrNotFound
		}
		return Session{}, fmt.Errorf("store: get session: %w", err)
	}
	if revokedAt.Valid {
		sess.RevokedAt = &revokedAt.Time
	}
	return sess, nil
}

func (s *MySQLStore) RevokeSession(ctx context.Context, tokenHash string) error {
	_, err := s.db.ExecContext(ctx, `
		UPDATE sessions SET revoked_at = NOW() WHERE token_hash = ? AND revoked_at IS NULL`, tokenHash)
	if err != nil {
		return fmt.Errorf("store: revoke session: %w", err)
	}
	return nil
}

func nullableString(s string) sql.NullString {
	if s == "" {
		return sql.NullString{}
	}
	return sql.NullString{String: s, Valid: true}
}
