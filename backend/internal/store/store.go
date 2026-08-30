// Package store defines the data-access contract for Sprint 1 authentication
// (users/devices/sessions) and a MySQL-backed implementation. The interface
// (AuthStore) exists so httpserver handlers can be unit-tested against an
// in-memory fake without a real MySQL instance, matching the pattern already
// used for the Sprint 0 /ready handler's Pinger interface.
package store

import (
	"context"
	"errors"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
)

// Sentinel errors returned by AuthStore implementations. Handlers must map
// these to generic client-facing errors (never leak "which one" for
// login-adjacent lookups, per SPEC-BASE.md Section 45).
var (
	ErrNotFound      = errors.New("store: not found")
	ErrAlreadyExists = errors.New("store: already exists")
)

// User is the persisted record for a registered account. AuthKeyHash and
// WrappedVDK are opaque to the server: AuthKeyHash is an encoded Argon2id
// hash (see internal/auth.HashAuthKey), WrappedVDK is client-produced
// AES-256-GCM ciphertext the server never decrypts.
type User struct {
	ID          int64
	Email       string
	Username    string // optional display name; login is always by email
	AuthKeyHash string
	KDFSalt     []byte
	KDFParams   auth.KDFParams
	KDFVersion  int
	WrappedVDK  []byte
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

// NewUser holds the fields required to create a user at registration time.
type NewUser struct {
	Email       string
	Username    string
	AuthKeyHash string
	KDFSalt     []byte
	KDFParams   auth.KDFParams
	KDFVersion  int
	WrappedVDK  []byte
}

// Session is a persisted (hashed) bearer session token.
type Session struct {
	ID        int64
	UserID    int64
	DeviceID  int64
	TokenHash string
	CreatedAt time.Time
	ExpiresAt time.Time
	RevokedAt *time.Time
}

// Valid reports whether the session is neither expired nor revoked, as of
// now.
func (s Session) Valid(now time.Time) bool {
	if s.RevokedAt != nil {
		return false
	}
	return now.Before(s.ExpiresAt)
}

// AuthStore is the persistence contract Sprint 1 auth handlers depend on.
type AuthStore interface {
	// GetUserByEmail returns ErrNotFound if no user has that (normalized)
	// email.
	GetUserByEmail(ctx context.Context, email string) (User, error)

	// CreateUser returns ErrAlreadyExists if the email is already
	// registered.
	CreateUser(ctx context.Context, u NewUser) (int64, error)

	// UpsertDevice creates or updates (last_seen_at) a device row for
	// userID identified by deviceIdentifier, returning its ID. A device is
	// scoped to a single user (SPEC-BASE.md Section 47, "User A cannot
	// access ... User B devices").
	UpsertDevice(ctx context.Context, userID int64, deviceIdentifier, deviceName string) (int64, error)

	// CreateSession persists a new session and returns its ID.
	CreateSession(ctx context.Context, userID, deviceID int64, tokenHash string, expiresAt time.Time) (int64, error)

	// GetSessionByTokenHash returns ErrNotFound if no session has that
	// hash.
	GetSessionByTokenHash(ctx context.Context, tokenHash string) (Session, error)

	// RevokeSession marks the session identified by tokenHash as revoked.
	// It is a no-op (no error) if the session doesn't exist or is already
	// revoked, so logout is idempotent.
	RevokeSession(ctx context.Context, tokenHash string) error
}
