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

// --- Sprint 2: vault foundation (categories + vault items) -----------------

// ErrForbiddenSystemCategory is returned by DeleteCategoryAndReassign when
// the caller tries to delete the user's system "Uncategorized" category
// (see CLAUDE.md Sprint 2 "Delete category behavior").
var ErrForbiddenSystemCategory = errors.New("store: cannot delete the system Uncategorized category")

// DefaultCategoryNames are created automatically for every new user at
// registration (SPEC-BASE.md Section 14).
var DefaultCategoryNames = []string{"Common", "Work", "Tools", "Personal", "Other"}

// UncategorizedCategoryName is the reserved, lazily-created "safety net"
// category vault items are moved into when their category is deleted
// without an explicit reassignment target.
const UncategorizedCategoryName = "Uncategorized"

// Category is a user-owned grouping for vault items. Name is plaintext
// (categories are not considered sensitive vault content per SPEC-BASE.md
// Section 13/32 -- only vault_items.encrypted_payload is).
type Category struct {
	ID              int64
	UserID          int64
	Name            string
	IsUncategorized bool
	ItemCount       int // populated by ListCategories only
	CreatedAt       time.Time
	UpdatedAt       time.Time
}

// VaultItem is a user-owned vault entry. EncryptedPayload is opaque
// client-produced AES-256-GCM ciphertext -- the server never decrypts it.
type VaultItem struct {
	ID               int64
	UserID           int64
	CategoryID       int64
	EncryptedPayload []byte
	CreatedAt        time.Time
	UpdatedAt        time.Time
}

// CategoryStore is the persistence contract for Sprint 2 category
// operations. All methods are ownership-scoped by userID and return
// ErrNotFound if the category doesn't exist or doesn't belong to that user
// (SPEC-BASE.md Section 47, "User A cannot access ... User B categories").
type CategoryStore interface {
	// CreateDefaultCategories creates the standard starter categories for a
	// newly-registered user. Called once, right after CreateUser succeeds.
	CreateDefaultCategories(ctx context.Context, userID int64) error

	// ListCategories returns all of userID's categories with their vault
	// item counts, ordered by creation order.
	ListCategories(ctx context.Context, userID int64) ([]Category, error)

	// GetCategory returns a single category, ownership-checked.
	GetCategory(ctx context.Context, userID, categoryID int64) (Category, error)

	// CreateCategory creates a new (non-system) category and returns its ID.
	CreateCategory(ctx context.Context, userID int64, name string) (int64, error)

	// RenameCategory renames an existing category. Returns
	// ErrForbiddenSystemCategory if categoryID is the Uncategorized
	// category.
	RenameCategory(ctx context.Context, userID, categoryID int64, name string) error

	// DeleteCategoryAndReassign deletes categoryID after moving all of its
	// vault items to reassignTo. If reassignTo is nil, items are moved to
	// the user's Uncategorized category (created on demand). Returns
	// ErrForbiddenSystemCategory if categoryID is itself the Uncategorized
	// category, or ErrNotFound if categoryID/reassignTo don't belong to
	// userID. Runs as a single transaction.
	DeleteCategoryAndReassign(ctx context.Context, userID, categoryID int64, reassignTo *int64) error
}

// VaultItemStore is the persistence contract for Sprint 2 vault item CRUD.
// All methods are ownership-scoped by userID, same rules as CategoryStore.
type VaultItemStore interface {
	// CreateVaultItem creates a new item. Returns ErrNotFound if categoryID
	// doesn't belong to userID.
	CreateVaultItem(ctx context.Context, userID, categoryID int64, encryptedPayload []byte) (VaultItem, error)

	// ListVaultItems returns userID's items, optionally filtered to a single
	// category (categoryID == nil means "all categories"), newest-updated
	// first.
	ListVaultItems(ctx context.Context, userID int64, categoryID *int64) ([]VaultItem, error)

	// GetVaultItem returns a single item, ownership-checked.
	GetVaultItem(ctx context.Context, userID, itemID int64) (VaultItem, error)

	// UpdateVaultItem updates an item's category and/or ciphertext. Pass nil
	// for newCategoryID to leave the category unchanged. Returns ErrNotFound
	// if itemID doesn't belong to userID, or if newCategoryID doesn't belong
	// to userID.
	UpdateVaultItem(ctx context.Context, userID, itemID int64, newCategoryID *int64, encryptedPayload []byte) (VaultItem, error)

	// DeleteVaultItem deletes an item, ownership-checked.
	DeleteVaultItem(ctx context.Context, userID, itemID int64) error
}

// --- Sprint 5: attachments -------------------------------------------------

// Attachment is a user-owned file attached to a vault item. EncryptedFilename
// is opaque client-produced AES-256-GCM ciphertext of the original filename
// (filenames can leak metadata, so like vault content the server never sees
// them in plaintext -- SPEC-BASE.md Section 17/32). MimeType/Size describe
// the *encrypted* blob as uploaded, purely as non-sensitive metadata (the
// server never inspects file contents). StoragePath is a path *relative* to
// the server's attachments root (see config.Config.AttachmentsDir) -- never
// an absolute host path, and never derived from client-controlled input (see
// httpserver/attachment_handlers.go for how it's generated).
type Attachment struct {
	ID                int64
	UserID            int64
	VaultItemID       int64
	EncryptedFilename []byte
	MimeType          string
	Size              int64
	StoragePath       string
	CreatedAt         time.Time
}

// AttachmentStore is the persistence contract for Sprint 5 attachment
// metadata. It only ever stores metadata + a storage-path pointer; the
// actual encrypted file bytes live on the local filesystem (SPEC-BASE.md
// Section 7), written/read/deleted by the httpserver layer. All methods are
// ownership-scoped by userID, same rules as CategoryStore/VaultItemStore.
type AttachmentStore interface {
	// CreateAttachment inserts a new attachment row. Returns ErrNotFound if
	// vaultItemID doesn't belong to userID.
	CreateAttachment(ctx context.Context, userID, vaultItemID int64, encryptedFilename []byte, mimeType string, size int64, storagePath string) (Attachment, error)

	// GetAttachment returns a single attachment, ownership-checked (must
	// belong to userID). Callers additionally verify it belongs to the
	// expected vault item, since the URL also carries an item ID
	// (SPEC-BASE.md Section 47: "User A cannot access ... User B
	// attachments").
	GetAttachment(ctx context.Context, userID, attachmentID int64) (Attachment, error)

	// ListAttachmentsForItem returns every attachment on vaultItemID, owned
	// by userID. Used to clean up files from disk when a vault item is
	// deleted (the DB row itself cascades via FK, but files on disk do not).
	ListAttachmentsForItem(ctx context.Context, userID, vaultItemID int64) ([]Attachment, error)

	// DeleteAttachment deletes an attachment's metadata row, ownership
	// checked. Callers are responsible for also deleting the underlying
	// file from disk (the store layer only knows about the DB).
	DeleteAttachment(ctx context.Context, userID, attachmentID int64) error
}

// Store is the full persistence contract for the API server (Sprint 1 auth
// + Sprint 2 vault foundation + Sprint 5 attachments). MySQLStore implements
// all four; handlers depend on the narrower interfaces they actually need so
// tests can supply minimal fakes.
type Store interface {
	AuthStore
	CategoryStore
	VaultItemStore
	AttachmentStore
}
