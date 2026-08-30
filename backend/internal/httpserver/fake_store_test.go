package httpserver

import (
	"context"
	"fmt"
	"sort"
	"sync"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// fakeAuthStore is an in-memory store.Store used to unit-test auth and
// vault handlers without a real MySQL instance, mirroring the fakePinger
// pattern already used for the Sprint 0 /ready handler test.
type fakeAuthStore struct {
	mu          sync.Mutex
	nextID      int64
	users       map[string]store.User // key: normalized email
	usersByID   map[int64]store.User
	devices     map[string]int64 // key: fmt userID:identifier
	sessions    map[string]store.Session
	categories  map[int64]store.Category
	items       map[int64]store.VaultItem
	attachments map[int64]store.Attachment
}

func newFakeAuthStore() *fakeAuthStore {
	return &fakeAuthStore{
		users:       make(map[string]store.User),
		usersByID:   make(map[int64]store.User),
		devices:     make(map[string]int64),
		sessions:    make(map[string]store.Session),
		categories:  make(map[int64]store.Category),
		items:       make(map[int64]store.VaultItem),
		attachments: make(map[int64]store.Attachment),
	}
}

func (f *fakeAuthStore) GetUserByEmail(_ context.Context, email string) (store.User, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	u, ok := f.users[normalizeTestEmail(email)]
	if !ok {
		return store.User{}, store.ErrNotFound
	}
	return u, nil
}

func (f *fakeAuthStore) CreateUser(_ context.Context, nu store.NewUser) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	email := normalizeTestEmail(nu.Email)
	if _, exists := f.users[email]; exists {
		return 0, store.ErrAlreadyExists
	}

	f.nextID++
	id := f.nextID
	u := store.User{
		ID:          id,
		Email:       email,
		Username:    nu.Username,
		AuthKeyHash: nu.AuthKeyHash,
		KDFSalt:     nu.KDFSalt,
		KDFParams:   nu.KDFParams,
		KDFVersion:  nu.KDFVersion,
		WrappedVDK:  nu.WrappedVDK,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
	}
	f.users[email] = u
	f.usersByID[id] = u
	return id, nil
}

func (f *fakeAuthStore) UpsertDevice(_ context.Context, userID int64, deviceIdentifier, _ string) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	key := deviceKey(userID, deviceIdentifier)
	if id, ok := f.devices[key]; ok {
		return id, nil
	}
	f.nextID++
	id := f.nextID
	f.devices[key] = id
	return id, nil
}

func (f *fakeAuthStore) CreateSession(_ context.Context, userID, deviceID int64, tokenHash string, expiresAt time.Time) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.nextID++
	id := f.nextID
	f.sessions[tokenHash] = store.Session{
		ID:        id,
		UserID:    userID,
		DeviceID:  deviceID,
		TokenHash: tokenHash,
		CreatedAt: time.Now(),
		ExpiresAt: expiresAt,
	}
	return id, nil
}

func (f *fakeAuthStore) GetSessionByTokenHash(_ context.Context, tokenHash string) (store.Session, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	s, ok := f.sessions[tokenHash]
	if !ok {
		return store.Session{}, store.ErrNotFound
	}
	return s, nil
}

func (f *fakeAuthStore) RevokeSession(_ context.Context, tokenHash string) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	s, ok := f.sessions[tokenHash]
	if !ok {
		return nil // idempotent
	}
	now := time.Now()
	s.RevokedAt = &now
	f.sessions[tokenHash] = s
	return nil
}

// --- Sprint 2: categories -------------------------------------------------

func (f *fakeAuthStore) CreateDefaultCategories(_ context.Context, userID int64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	for _, name := range store.DefaultCategoryNames {
		f.nextID++
		id := f.nextID
		now := time.Now()
		f.categories[id] = store.Category{ID: id, UserID: userID, Name: name, CreatedAt: now, UpdatedAt: now}
	}
	return nil
}

func (f *fakeAuthStore) ListCategories(_ context.Context, userID int64) ([]store.Category, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	var out []store.Category
	for _, c := range f.categories {
		if c.UserID != userID {
			continue
		}
		c.ItemCount = f.countItemsInCategoryLocked(userID, c.ID)
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

func (f *fakeAuthStore) countItemsInCategoryLocked(userID, categoryID int64) int {
	n := 0
	for _, it := range f.items {
		if it.UserID == userID && it.CategoryID == categoryID {
			n++
		}
	}
	return n
}

func (f *fakeAuthStore) GetCategory(_ context.Context, userID, categoryID int64) (store.Category, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.getCategoryLocked(userID, categoryID)
}

func (f *fakeAuthStore) getCategoryLocked(userID, categoryID int64) (store.Category, error) {
	c, ok := f.categories[categoryID]
	if !ok || c.UserID != userID {
		return store.Category{}, store.ErrNotFound
	}
	return c, nil
}

func (f *fakeAuthStore) CreateCategory(_ context.Context, userID int64, name string) (int64, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.nextID++
	id := f.nextID
	now := time.Now()
	f.categories[id] = store.Category{ID: id, UserID: userID, Name: name, CreatedAt: now, UpdatedAt: now}
	return id, nil
}

func (f *fakeAuthStore) RenameCategory(_ context.Context, userID, categoryID int64, name string) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	c, err := f.getCategoryLocked(userID, categoryID)
	if err != nil {
		return err
	}
	if c.IsUncategorized {
		return store.ErrForbiddenSystemCategory
	}
	c.Name = name
	c.UpdatedAt = time.Now()
	f.categories[categoryID] = c
	return nil
}

func (f *fakeAuthStore) DeleteCategoryAndReassign(_ context.Context, userID, categoryID int64, reassignTo *int64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	target, err := f.getCategoryLocked(userID, categoryID)
	if err != nil {
		return err
	}
	if target.IsUncategorized {
		return store.ErrForbiddenSystemCategory
	}

	var destID int64
	if reassignTo != nil {
		dest, err := f.getCategoryLocked(userID, *reassignTo)
		if err != nil {
			return err
		}
		if dest.ID == target.ID {
			return fmt.Errorf("reassign target must differ from category being deleted")
		}
		destID = dest.ID
	} else {
		destID = f.findOrCreateUncategorizedLocked(userID)
	}

	for id, it := range f.items {
		if it.UserID == userID && it.CategoryID == target.ID {
			it.CategoryID = destID
			f.items[id] = it
		}
	}

	delete(f.categories, target.ID)
	return nil
}

func (f *fakeAuthStore) findOrCreateUncategorizedLocked(userID int64) int64 {
	for _, c := range f.categories {
		if c.UserID == userID && c.IsUncategorized {
			return c.ID
		}
	}
	f.nextID++
	id := f.nextID
	now := time.Now()
	f.categories[id] = store.Category{ID: id, UserID: userID, Name: store.UncategorizedCategoryName, IsUncategorized: true, CreatedAt: now, UpdatedAt: now}
	return id
}

// --- Sprint 2: vault items -------------------------------------------------

func (f *fakeAuthStore) CreateVaultItem(_ context.Context, userID, categoryID int64, encryptedPayload []byte) (store.VaultItem, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	if _, err := f.getCategoryLocked(userID, categoryID); err != nil {
		return store.VaultItem{}, err
	}

	f.nextID++
	id := f.nextID
	now := time.Now()
	item := store.VaultItem{ID: id, UserID: userID, CategoryID: categoryID, EncryptedPayload: encryptedPayload, CreatedAt: now, UpdatedAt: now}
	f.items[id] = item
	return item, nil
}

func (f *fakeAuthStore) ListVaultItems(_ context.Context, userID int64, categoryID *int64) ([]store.VaultItem, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	var out []store.VaultItem
	for _, it := range f.items {
		if it.UserID != userID {
			continue
		}
		if categoryID != nil && it.CategoryID != *categoryID {
			continue
		}
		out = append(out, it)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].UpdatedAt.Equal(out[j].UpdatedAt) {
			return out[i].ID > out[j].ID
		}
		return out[i].UpdatedAt.After(out[j].UpdatedAt)
	})
	return out, nil
}

func (f *fakeAuthStore) GetVaultItem(_ context.Context, userID, itemID int64) (store.VaultItem, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.getItemLocked(userID, itemID)
}

func (f *fakeAuthStore) getItemLocked(userID, itemID int64) (store.VaultItem, error) {
	it, ok := f.items[itemID]
	if !ok || it.UserID != userID {
		return store.VaultItem{}, store.ErrNotFound
	}
	return it, nil
}

func (f *fakeAuthStore) UpdateVaultItem(_ context.Context, userID, itemID int64, newCategoryID *int64, encryptedPayload []byte) (store.VaultItem, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	it, err := f.getItemLocked(userID, itemID)
	if err != nil {
		return store.VaultItem{}, err
	}
	if newCategoryID != nil {
		if _, err := f.getCategoryLocked(userID, *newCategoryID); err != nil {
			return store.VaultItem{}, err
		}
		it.CategoryID = *newCategoryID
	}
	it.EncryptedPayload = encryptedPayload
	it.UpdatedAt = time.Now()
	f.items[itemID] = it
	return it, nil
}

func (f *fakeAuthStore) DeleteVaultItem(_ context.Context, userID, itemID int64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	it, ok := f.items[itemID]
	if !ok || it.UserID != userID {
		return store.ErrNotFound
	}
	delete(f.items, itemID)

	// Mirrors the real schema's `fk_attachments_vault_item ... ON DELETE
	// CASCADE` (infra/mysql/init/004-attachments-schema.sql): deleting a
	// vault item's row must also remove its attachments' metadata rows.
	// Cleaning up the corresponding on-disk files is NOT this store's job
	// (it only knows about the DB) -- that's handleDeleteVaultItem's
	// explicit responsibility via deleteAttachmentsForItem, called before
	// this method.
	for id, a := range f.attachments {
		if a.UserID == userID && a.VaultItemID == itemID {
			delete(f.attachments, id)
		}
	}
	return nil
}

// --- Sprint 5: attachments -------------------------------------------------

func (f *fakeAuthStore) CreateAttachment(_ context.Context, userID, vaultItemID int64, encryptedFilename []byte, mimeType string, size int64, storagePath string) (store.Attachment, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	if _, err := f.getItemLocked(userID, vaultItemID); err != nil {
		return store.Attachment{}, err
	}

	f.nextID++
	id := f.nextID
	a := store.Attachment{
		ID:                id,
		UserID:            userID,
		VaultItemID:       vaultItemID,
		EncryptedFilename: encryptedFilename,
		MimeType:          mimeType,
		Size:              size,
		StoragePath:       storagePath,
		CreatedAt:         time.Now(),
	}
	f.attachments[id] = a
	return a, nil
}

func (f *fakeAuthStore) GetAttachment(_ context.Context, userID, attachmentID int64) (store.Attachment, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	a, ok := f.attachments[attachmentID]
	if !ok || a.UserID != userID {
		return store.Attachment{}, store.ErrNotFound
	}
	return a, nil
}

func (f *fakeAuthStore) ListAttachmentsForItem(_ context.Context, userID, vaultItemID int64) ([]store.Attachment, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	var out []store.Attachment
	for _, a := range f.attachments {
		if a.UserID == userID && a.VaultItemID == vaultItemID {
			out = append(out, a)
		}
	}
	sort.Slice(out, func(i, j int) bool { return out[i].ID < out[j].ID })
	return out, nil
}

func (f *fakeAuthStore) DeleteAttachment(_ context.Context, userID, attachmentID int64) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	a, ok := f.attachments[attachmentID]
	if !ok || a.UserID != userID {
		return store.ErrNotFound
	}
	delete(f.attachments, attachmentID)
	return nil
}

func deviceKey(userID int64, identifier string) string {
	return fmt.Sprintf("%d|%s", userID, identifier)
}

func normalizeTestEmail(email string) string {
	// Mirrors auth.NormalizeEmail without importing the auth package here,
	// to keep this fake dependency-light; handlers themselves are the ones
	// responsible for actually normalizing before calling the store.
	out := make([]byte, 0, len(email))
	for _, r := range email {
		if r == ' ' || r == '\t' || r == '\n' {
			continue
		}
		if r >= 'A' && r <= 'Z' {
			r += 'a' - 'A'
		}
		out = append(out, byte(r))
	}
	return string(out)
}
