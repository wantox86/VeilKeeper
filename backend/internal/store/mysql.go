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

// --- Sprint 2: categories -----------------------------------------------

func (s *MySQLStore) CreateDefaultCategories(ctx context.Context, userID int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("store: create default categories: begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	stmt, err := tx.PrepareContext(ctx, `INSERT INTO categories (user_id, name) VALUES (?, ?)`)
	if err != nil {
		return fmt.Errorf("store: create default categories: prepare: %w", err)
	}
	defer stmt.Close()

	for _, name := range DefaultCategoryNames {
		if _, err := stmt.ExecContext(ctx, userID, name); err != nil {
			return fmt.Errorf("store: create default categories: insert %q: %w", name, err)
		}
	}

	return tx.Commit()
}

func (s *MySQLStore) ListCategories(ctx context.Context, userID int64) ([]Category, error) {
	rows, err := s.db.QueryContext(ctx, `
		SELECT c.id, c.user_id, c.name, c.is_uncategorized, c.created_at, c.updated_at,
		       COUNT(v.id) AS item_count
		FROM categories c
		LEFT JOIN vault_items v ON v.category_id = c.id AND v.user_id = c.user_id
		WHERE c.user_id = ?
		GROUP BY c.id, c.user_id, c.name, c.is_uncategorized, c.created_at, c.updated_at
		ORDER BY c.id ASC`, userID)
	if err != nil {
		return nil, fmt.Errorf("store: list categories: %w", err)
	}
	defer rows.Close()

	var out []Category
	for rows.Next() {
		var c Category
		if err := rows.Scan(&c.ID, &c.UserID, &c.Name, &c.IsUncategorized, &c.CreatedAt, &c.UpdatedAt, &c.ItemCount); err != nil {
			return nil, fmt.Errorf("store: list categories: scan: %w", err)
		}
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("store: list categories: rows: %w", err)
	}
	return out, nil
}

func (s *MySQLStore) GetCategory(ctx context.Context, userID, categoryID int64) (Category, error) {
	return getCategoryTx(ctx, s.db, userID, categoryID)
}

// querier is satisfied by both *sql.DB and *sql.Tx.
type querier interface {
	QueryRowContext(ctx context.Context, query string, args ...any) *sql.Row
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
}

func getCategoryTx(ctx context.Context, q querier, userID, categoryID int64) (Category, error) {
	row := q.QueryRowContext(ctx, `
		SELECT id, user_id, name, is_uncategorized, created_at, updated_at
		FROM categories WHERE id = ? AND user_id = ?`, categoryID, userID)

	var c Category
	if err := row.Scan(&c.ID, &c.UserID, &c.Name, &c.IsUncategorized, &c.CreatedAt, &c.UpdatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return Category{}, ErrNotFound
		}
		return Category{}, fmt.Errorf("store: get category: %w", err)
	}
	return c, nil
}

func (s *MySQLStore) CreateCategory(ctx context.Context, userID int64, name string) (int64, error) {
	res, err := s.db.ExecContext(ctx, `INSERT INTO categories (user_id, name) VALUES (?, ?)`, userID, name)
	if err != nil {
		return 0, fmt.Errorf("store: create category: %w", err)
	}
	id, err := res.LastInsertId()
	if err != nil {
		return 0, fmt.Errorf("store: create category: read insert id: %w", err)
	}
	return id, nil
}

func (s *MySQLStore) RenameCategory(ctx context.Context, userID, categoryID int64, name string) error {
	cat, err := s.GetCategory(ctx, userID, categoryID)
	if err != nil {
		return err
	}
	if cat.IsUncategorized {
		return ErrForbiddenSystemCategory
	}

	_, err = s.db.ExecContext(ctx, `UPDATE categories SET name = ? WHERE id = ? AND user_id = ?`, name, categoryID, userID)
	if err != nil {
		return fmt.Errorf("store: rename category: %w", err)
	}
	return nil
}

func (s *MySQLStore) DeleteCategoryAndReassign(ctx context.Context, userID, categoryID int64, reassignTo *int64) error {
	tx, err := s.db.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("store: delete category: begin tx: %w", err)
	}
	defer func() { _ = tx.Rollback() }()

	target, err := getCategoryTx(ctx, tx, userID, categoryID)
	if err != nil {
		return err
	}
	if target.IsUncategorized {
		return ErrForbiddenSystemCategory
	}

	var destID int64
	if reassignTo != nil {
		dest, err := getCategoryTx(ctx, tx, userID, *reassignTo)
		if err != nil {
			return err
		}
		if dest.ID == target.ID {
			return fmt.Errorf("store: delete category: reassign target must differ from the category being deleted")
		}
		destID = dest.ID
	} else {
		destID, err = findOrCreateUncategorizedTx(ctx, tx, userID)
		if err != nil {
			return err
		}
	}

	if _, err := tx.ExecContext(ctx, `UPDATE vault_items SET category_id = ? WHERE user_id = ? AND category_id = ?`, destID, userID, target.ID); err != nil {
		return fmt.Errorf("store: delete category: reassign items: %w", err)
	}

	if _, err := tx.ExecContext(ctx, `DELETE FROM categories WHERE id = ? AND user_id = ?`, target.ID, userID); err != nil {
		return fmt.Errorf("store: delete category: %w", err)
	}

	return tx.Commit()
}

// findOrCreateUncategorizedTx returns the ID of userID's system
// Uncategorized category, creating it on first use. Must run inside tx so
// concurrent deletes for the same user serialize on InnoDB row locks rather
// than racing to create two Uncategorized rows (a known, documented
// simplification -- see 003-vault-schema.sql's comment on is_uncategorized
// for why this isn't DB-constrained).
func findOrCreateUncategorizedTx(ctx context.Context, tx *sql.Tx, userID int64) (int64, error) {
	row := tx.QueryRowContext(ctx, `
		SELECT id FROM categories WHERE user_id = ? AND is_uncategorized = TRUE LIMIT 1`, userID)
	var id int64
	err := row.Scan(&id)
	switch {
	case err == nil:
		return id, nil
	case errors.Is(err, sql.ErrNoRows):
		res, err := tx.ExecContext(ctx, `
			INSERT INTO categories (user_id, name, is_uncategorized) VALUES (?, ?, TRUE)`, userID, UncategorizedCategoryName)
		if err != nil {
			return 0, fmt.Errorf("store: create uncategorized category: %w", err)
		}
		return res.LastInsertId()
	default:
		return 0, fmt.Errorf("store: find uncategorized category: %w", err)
	}
}

// --- Sprint 2: vault items -------------------------------------------------

func (s *MySQLStore) CreateVaultItem(ctx context.Context, userID, categoryID int64, encryptedPayload []byte) (VaultItem, error) {
	if _, err := s.GetCategory(ctx, userID, categoryID); err != nil {
		return VaultItem{}, err
	}

	res, err := s.db.ExecContext(ctx, `
		INSERT INTO vault_items (user_id, category_id, encrypted_payload) VALUES (?, ?, ?)`,
		userID, categoryID, encryptedPayload)
	if err != nil {
		return VaultItem{}, fmt.Errorf("store: create vault item: %w", err)
	}
	id, err := res.LastInsertId()
	if err != nil {
		return VaultItem{}, fmt.Errorf("store: create vault item: read insert id: %w", err)
	}
	return s.GetVaultItem(ctx, userID, id)
}

func (s *MySQLStore) ListVaultItems(ctx context.Context, userID int64, categoryID *int64) ([]VaultItem, error) {
	query := `
		SELECT id, user_id, category_id, encrypted_payload, created_at, updated_at
		FROM vault_items WHERE user_id = ?`
	args := []any{userID}
	if categoryID != nil {
		query += " AND category_id = ?"
		args = append(args, *categoryID)
	}
	query += " ORDER BY updated_at DESC, id DESC"

	rows, err := s.db.QueryContext(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("store: list vault items: %w", err)
	}
	defer rows.Close()

	var out []VaultItem
	for rows.Next() {
		var v VaultItem
		if err := rows.Scan(&v.ID, &v.UserID, &v.CategoryID, &v.EncryptedPayload, &v.CreatedAt, &v.UpdatedAt); err != nil {
			return nil, fmt.Errorf("store: list vault items: scan: %w", err)
		}
		out = append(out, v)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("store: list vault items: rows: %w", err)
	}
	return out, nil
}

func (s *MySQLStore) GetVaultItem(ctx context.Context, userID, itemID int64) (VaultItem, error) {
	row := s.db.QueryRowContext(ctx, `
		SELECT id, user_id, category_id, encrypted_payload, created_at, updated_at
		FROM vault_items WHERE id = ? AND user_id = ?`, itemID, userID)

	var v VaultItem
	if err := row.Scan(&v.ID, &v.UserID, &v.CategoryID, &v.EncryptedPayload, &v.CreatedAt, &v.UpdatedAt); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return VaultItem{}, ErrNotFound
		}
		return VaultItem{}, fmt.Errorf("store: get vault item: %w", err)
	}
	return v, nil
}

func (s *MySQLStore) UpdateVaultItem(ctx context.Context, userID, itemID int64, newCategoryID *int64, encryptedPayload []byte) (VaultItem, error) {
	if _, err := s.GetVaultItem(ctx, userID, itemID); err != nil {
		return VaultItem{}, err
	}

	if newCategoryID != nil {
		if _, err := s.GetCategory(ctx, userID, *newCategoryID); err != nil {
			return VaultItem{}, err
		}
		_, err := s.db.ExecContext(ctx, `
			UPDATE vault_items SET category_id = ?, encrypted_payload = ? WHERE id = ? AND user_id = ?`,
			*newCategoryID, encryptedPayload, itemID, userID)
		if err != nil {
			return VaultItem{}, fmt.Errorf("store: update vault item: %w", err)
		}
	} else {
		_, err := s.db.ExecContext(ctx, `
			UPDATE vault_items SET encrypted_payload = ? WHERE id = ? AND user_id = ?`,
			encryptedPayload, itemID, userID)
		if err != nil {
			return VaultItem{}, fmt.Errorf("store: update vault item: %w", err)
		}
	}

	return s.GetVaultItem(ctx, userID, itemID)
}

func (s *MySQLStore) DeleteVaultItem(ctx context.Context, userID, itemID int64) error {
	res, err := s.db.ExecContext(ctx, `DELETE FROM vault_items WHERE id = ? AND user_id = ?`, itemID, userID)
	if err != nil {
		return fmt.Errorf("store: delete vault item: %w", err)
	}
	n, err := res.RowsAffected()
	if err != nil {
		return fmt.Errorf("store: delete vault item: rows affected: %w", err)
	}
	if n == 0 {
		return ErrNotFound
	}
	return nil
}
