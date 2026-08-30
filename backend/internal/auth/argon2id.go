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
	"crypto/rand"
	"crypto/subtle"
	"encoding/base64"
	"errors"
	"fmt"
	"strings"

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
)

const argon2idPrefix = "$argon2id$"

// dummyAuthKeyHash is a fixed (but validly-formatted) hash used purely to
// keep login's timing profile similar for "user not found" vs "wrong
// AuthKey", so response latency doesn't become an account-enumeration
// side-channel. It is computed once at process start; its input is not a
// real secret and is never used for actual authentication.
var dummyAuthKeyHash string

func init() {
	h, err := HashAuthKey([]byte("veilkeeper-timing-safety-dummy-do-not-use-as-a-real-key"))
	if err != nil {
		panic("auth: failed to initialize dummy hash: " + err.Error())
	}
	dummyAuthKeyHash = h
}

// VerifyAgainstDummyHash runs a real Argon2id verification against a fixed
// dummy hash so callers can burn roughly the same amount of CPU time on a
// "user not found" path as on a "user found, wrong AuthKey" path. The
// boolean result is meaningless and must be ignored.
func VerifyAgainstDummyHash(authKey []byte) {
	_, _ = VerifyAuthKey(authKey, dummyAuthKeyHash)
}

// HashAuthKey hashes a client-supplied AuthKey (already decoded from
// base64) for storage in users.auth_key_hash. The output is a self
// describing encoded string (modular-crypt-like format) so parameters can be
// changed later without breaking verification of existing hashes.
func HashAuthKey(authKey []byte) (string, error) {
	if len(authKey) == 0 {
		return "", errors.New("auth: empty auth key")
	}

	salt := make([]byte, authHashSaltLen)
	if _, err := rand.Read(salt); err != nil {
		return "", fmt.Errorf("auth: generate salt: %w", err)
	}

	hash := argon2.IDKey(authKey, salt, authHashIterations, authHashMemoryKiB, authHashParallel, authHashKeyLen)

	encoded := fmt.Sprintf("%sv=%d$m=%d,t=%d,p=%d$%s$%s",
		argon2idPrefix,
		argon2.Version,
		authHashMemoryKiB, authHashIterations, authHashParallel,
		base64.RawStdEncoding.EncodeToString(salt),
		base64.RawStdEncoding.EncodeToString(hash),
	)
	return encoded, nil
}

// VerifyAuthKey checks a client-supplied AuthKey against a previously stored
// encoded hash, using a constant-time comparison. It never returns
// information distinguishing "user not found" from "wrong key" -- callers
// must apply that decision uniformly upstream.
func VerifyAuthKey(authKey []byte, encoded string) (bool, error) {
	memory, iterations, parallel, salt, hash, err := decodeArgon2idHash(encoded)
	if err != nil {
		return false, err
	}

	candidate := argon2.IDKey(authKey, salt, iterations, memory, parallel, uint32(len(hash)))
	return subtle.ConstantTimeCompare(candidate, hash) == 1, nil
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
