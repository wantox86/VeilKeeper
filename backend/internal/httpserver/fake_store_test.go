package httpserver

import (
	"context"
	"fmt"
	"sync"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// fakeAuthStore is an in-memory store.AuthStore used to unit-test auth
// handlers without a real MySQL instance, mirroring the fakePinger pattern
// already used for the Sprint 0 /ready handler test.
type fakeAuthStore struct {
	mu        sync.Mutex
	nextID    int64
	users     map[string]store.User // key: normalized email
	usersByID map[int64]store.User
	devices   map[string]int64 // key: fmt userID:identifier
	sessions  map[string]store.Session
}

func newFakeAuthStore() *fakeAuthStore {
	return &fakeAuthStore{
		users:     make(map[string]store.User),
		usersByID: make(map[int64]store.User),
		devices:   make(map[string]int64),
		sessions:  make(map[string]store.Session),
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
