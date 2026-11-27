// Package auth implements Sprint 1 authentication primitives: server-side
// AuthKey hashing, KDF parameter bookkeeping, anti-enumeration fake-salt
// derivation, session token generation, and lightweight rate limiting.
//
// IMPORTANT: this package never sees the user's raw password or the
// client-derived MasterKey/WrapKey/VaultDataKey -- those never leave the
// Android device (see repo CLAUDE.md, Resolved Design Decision #1). The only
// secret this package ever hashes/verifies is the client-derived AuthKey.
package auth

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"
	"time"

	"golang.org/x/crypto/argon2"
)

// authKeyHashParams are the Argon2id parameters used to hash the
// already-high-entropy AuthKey at rest on the server.
//
// These are intentionally lighter than the client-side password KDF params
// (DefaultKDFParams in kdf.go): the AuthKey is a 256-bit value derived via
// HKDF from an already-expensive Argon2id(password) on the client, so it has
// no meaningful guessing surface the way a raw password does. Hashing it
// again server-side is defense-in-depth (so a DB leak alone never yields a
// usable credential), not the primary brute-force barrier -- that job is
// done by the client-side KDF. We use the OWASP-recommended Argon2id
// "second option" minimum (m=19MiB, t=2, p=1), which keeps per-login CPU/RAM
// cost low (matches SPEC-BASE.md Section 52 "low resource consumption") while
// still being materially more expensive than a bare SHA-256/bcrypt round.
const (
	authHashMemoryKiB  uint32 = 19 * 1024
	authHashIterations uint32 = 2
	authHashParallel   uint8  = 1
	authHashKeyLen     uint32 = 32
	authHashSaltLen           = 16

	// DefaultArgon2MaxConcurrent is the default semaphore capacity bounding
	// simultaneous Argon2id hash/verify calls.
	DefaultArgon2MaxConcurrent = 4

	// DefaultArgon2AcquireTimeout is the default maximum duration a caller
	// waits for an Argon2id concurrency slot before failing fast with
	// ErrArgon2Busy.
	DefaultArgon2AcquireTimeout = 3 * time.Second
)

const argon2idPrefix = "$argon2id$"

// ErrArgon2Busy is returned when a caller's context ends (cancelled or
// timed out) before an Argon2id concurrency slot becomes available.
var ErrArgon2Busy = errors.New("auth: argon2 concurrency limit wait aborted")

// dummyAuthKeyHash is a fixed (but validly-formatted) hash used purely to
// keep login's timing profile similar for "user not found" vs "wrong
// AuthKey", so response latency doesn't become an account-enumeration
// side-channel. It is computed once at process start; its input is not a
// real secret and is never used for actual authentication.
var dummyAuthKeyHash string

var defaultHasher *Hasher

func init() {
	var err error
	defaultHasher, err = NewHasher(DefaultArgon2MaxConcurrent, DefaultArgon2AcquireTimeout)
	if err != nil {
		panic("auth: failed to initialize default hasher: " + err.Error())
	}

	h, err := hashAuthKeyRaw([]byte("veilkeeper-timing-safety-dummy-do-not-use-as-a-real-key"), argon2.IDKey)
	if err != nil {
		panic("auth: failed to initialize dummy hash: " + err.Error())
	}
	dummyAuthKeyHash = h
}

// Hasher bounds concurrent Argon2id hash/verify operations with a semaphore
// so a burst of parallel auth requests cannot exhaust container memory
// (~19 MiB per operation).
type Hasher struct {
	sem            chan struct{}
	acquireTimeout time.Duration
	kdfFn          func(password, salt []byte, time, memory uint32, threads uint8, keyLen uint32) []byte
}

// NewHasher creates a Hasher allowing at most maxConcurrent Argon2id
// operations in flight at once, waiting at most acquireTimeout for a slot
// before returning ErrArgon2Busy. Both maxConcurrent and acquireTimeout must
// be > 0.
func NewHasher(maxConcurrent int, acquireTimeout time.Duration) (*Hasher, error) {
	return NewHasherWithKeyFunc(maxConcurrent, acquireTimeout, argon2.IDKey)
}

// NewHasherWithKeyFunc is like NewHasher but allows injecting a custom
// Argon2id key derivation function (e.g. a deterministic/blocking stub in
// tests). If kdfFn is nil, argon2.IDKey is used.
func NewHasherWithKeyFunc(maxConcurrent int, acquireTimeout time.Duration, kdfFn func(password, salt []byte, time, memory uint32, threads uint8, keyLen uint32) []byte) (*Hasher, error) {
	if maxConcurrent <= 0 {
		return nil, fmt.Errorf("auth: maxConcurrent must be > 0, got %d", maxConcurrent)
	}
	if acquireTimeout <= 0 {
		return nil, fmt.Errorf("auth: acquireTimeout must be > 0, got %s", acquireTimeout)
	}
	if kdfFn == nil {
		kdfFn = argon2.IDKey
	}
	return &Hasher{
		sem:            make(chan struct{}, maxConcurrent),
		acquireTimeout: acquireTimeout,
		kdfFn:          kdfFn,
	}, nil
}

func (h *Hasher) acquire(ctx context.Context) error {
	if err := ctx.Err(); err != nil {
		return fmt.Errorf("%w: %w", ErrArgon2Busy, err)
	}

	timeout := h.acquireTimeout
	if timeout <= 0 {
		timeout = DefaultArgon2AcquireTimeout
	}

	waitCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	select {
	case h.sem <- struct{}{}:
		return nil
	case <-waitCtx.Done():
		return fmt.Errorf("%w: %w", ErrArgon2Busy, waitCtx.Err())
	}
}

func (h *Hasher) release() {
	<-h.sem
}

func (h *Hasher) keyFn() func(password, salt []byte, time, memory uint32, threads uint8, keyLen uint32) []byte {
	if h.kdfFn != nil {
		return h.kdfFn
	}
	return argon2.IDKey
}

// HashAuthKey hashes a client-supplied AuthKey while respecting the hasher's
// concurrency limit and ctx cancellation/deadline.
func (h *Hasher) HashAuthKey(ctx context.Context, authKey []byte) (string, error) {
	if len(authKey) == 0 {
		return "", errors.New("auth: empty auth key")
	}
	if err := h.acquire(ctx); err != nil {
		return "", err
	}
	defer h.release()

	return hashAuthKeyRaw(authKey, h.keyFn())
}

// VerifyAuthKey verifies a client-supplied AuthKey against an encoded hash
// while respecting the hasher's concurrency limit and ctx cancellation/deadline.
func (h *Hasher) VerifyAuthKey(ctx context.Context, authKey []byte, encoded string) (bool, error) {
	memory, iterations, parallel, salt, hash, err := decodeArgon2idHash(encoded)
	if err != nil {
		return false, err
	}
	if err := h.acquire(ctx); err != nil {
		return false, err
	}
	defer h.release()

	candidate := h.keyFn()(authKey, salt, iterations, memory, parallel, uint32(len(hash)))
	return subtle.ConstantTimeCompare(candidate, hash) == 1, nil
}

// VerifyAgainstDummyHash runs a real Argon2id verification against a fixed
// dummy hash so callers can burn roughly the same amount of CPU time on a
// "user not found" path as on a "user found, wrong AuthKey" path. Returns
// an error (e.g. ErrArgon2Busy) if waiting for a concurrency slot fails.
func (h *Hasher) VerifyAgainstDummyHash(ctx context.Context, authKey []byte) error {
	_, err := h.VerifyAuthKey(ctx, authKey, dummyAuthKeyHash)
	return err
}

// VerifyAgainstDummyHash runs a real Argon2id verification against a fixed
// dummy hash using the default concurrency-limited hasher. The boolean result
// is meaningless and ignored.
func VerifyAgainstDummyHash(authKey []byte) {
	_ = defaultHasher.VerifyAgainstDummyHash(context.Background(), authKey)
}

// HashAuthKey hashes a client-supplied AuthKey (already decoded from
// base64) for storage in users.auth_key_hash. The output is a self
// describing encoded string (modular-crypt-like format) so parameters can be
// changed later without breaking verification of existing hashes.
func HashAuthKey(authKey []byte) (string, error) {
	return defaultHasher.HashAuthKey(context.Background(), authKey)
}

// VerifyAuthKey checks a client-supplied AuthKey against a previously stored
// encoded hash, using a constant-time comparison. It never returns
// information distinguishing "user not found" from "wrong key" -- callers
// must apply that decision uniformly upstream.
func VerifyAuthKey(authKey []byte, encoded string) (bool, error) {
	return defaultHasher.VerifyAuthKey(context.Background(), authKey, encoded)
}

func hashAuthKeyRaw(authKey []byte, kdfFn func(password, salt []byte, time, memory uint32, threads uint8, keyLen uint32) []byte) (string, error) {
	if len(authKey) == 0 {
		return "", errors.New("auth: empty auth key")
	}

	salt := make([]byte, authHashSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("auth: generate salt: %w", err)
	}

	hash := kdfFn(authKey, salt, authHashIterations, authHashMemoryKiB, authHashParallel, authHashKeyLen)

	encoded := fmt.Sprintf("%sv=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2idPrefix,
		argon2.Version,
		authHashMemoryKiB, authHashIterations, authHashParallel,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(hash),
	)
	return encoded, nil
}

func decodeArgon2idHash(encoded string) (memory, iterations uint32, parallel uint8, salt, hash []byte, err error) {
	if !strings.HasPrefix(encoded, argon2idPrefix) {
		return 0, 0, 0, nil, nil, errors.New("auth: unrecognized hash format")
	}
	parts := strings.Split(strings.TrimPrefix(encoded, argon2idPrefix), "$")
	if len(parts) != 4 {
		return 0, 0, 0, nil, nil, errors.New("auth: malformed hash")
	}

	var version int
	if _, err := fmt.Sscanf(parts[0], "v=%d", &version); err != nil {
		return 0, 0, 0, nil, nil, fmt.Errorf("auth: malformed version segment: %w", err)
	}

	if _, err := fmt.Sscanf(parts[1], "m=%d,t=%d,p=%d", &memory, &iterations, &parallel); err != nil {
		return 0, 0, 0, nil, nil, fmt.Errorf("auth: malformed params segment: %w", err)
	}

	salt, err = base64.RawStdEncoding.DecodeString(parts[2])
	if err != nil {
		return 0, 0, 0, nil, nil, fmt.Errorf("auth: malformed salt: %w", err)
	}
	hash, err = base64.RawStdEncoding.DecodeString(parts[3])
	if err != nil {
		return 0, 0, 0, nil, nil, fmt.Errorf("auth: malformed hash payload: %w", err)
	}

	return memory, iterations, parallel, salt, hash, nil
}
