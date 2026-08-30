package auth

import (
	"crypto/hmac"
	"crypto/sha256"
	"errors"
	"strings"
)

// KDFParams describes the Argon2id parameters the Android client must use to
// derive MasterKey = Argon2id(password, kdf_salt, kdf_params), per CLAUDE.md
// Resolved Design Decision #1. Memory is expressed in KiB (matches
// golang.org/x/crypto/argon2's unit and Argon2's own convention).
type KDFParams struct {
	MemoryKiB   uint32 `json:"memory"`
	Iterations  uint32 `json:"iterations"`
	Parallelism uint8  `json:"parallelism"`
}

// CurrentKDFVersion is the KDF parameter set version returned to new
// accounts and to the anti-enumeration fake-salt path. Bumping this (and
// DefaultKDFParams) upgrades parameters for new accounts without breaking
// existing ones -- existing users keep whatever kdf_version/kdf_params were
// recorded at their own registration time.
const CurrentKDFVersion = 1

// DefaultKDFParams are the Argon2id parameters assigned to newly registered
// accounts: 64 MiB memory, 3 iterations, 4-way parallelism -- the example
// figures given in CLAUDE.md Resolved Design Decision #1, chosen as a
// reasonable modern default for a mobile client (OWASP's Argon2id
// recommendation range).
var DefaultKDFParams = KDFParams{
	MemoryKiB:   64 * 1024,
	Iterations:  3,
	Parallelism: 4,
}

// Bounds enforced on any client-echoed KDFParams at registration time. These
// exist only to stop obviously-wrong values from being persisted (e.g. 0
// iterations, or a memory figure large enough to be a copy/paste mistake);
// the server never itself executes Argon2id with these params (that's the
// client's job), so this is not a resource-exhaustion control on the server.
const (
	minMemoryKiB   uint32 = 8 * 1024
	maxMemoryKiB   uint32 = 256 * 1024
	minIterations  uint32 = 1
	maxIterations  uint32 = 10
	minParallelism uint8  = 1
	maxParallelism uint8  = 16
)

// ValidateKDFParams rejects obviously-invalid parameter sets.
func ValidateKDFParams(p KDFParams) error {
	if p.MemoryKiB < minMemoryKiB || p.MemoryKiB > maxMemoryKiB {
		return errors.New("auth: kdf_params.memory out of allowed range")
	}
	if p.Iterations < minIterations || p.Iterations > maxIterations {
		return errors.New("auth: kdf_params.iterations out of allowed range")
	}
	if p.Parallelism < minParallelism || p.Parallelism > maxParallelism {
		return errors.New("auth: kdf_params.parallelism out of allowed range")
	}
	return nil
}

const fakeSaltLen = 16

// FakeSalt deterministically derives a fake-but-stable kdf_salt for an email
// address that has no account, so that /auth/prelogin responses for
// nonexistent accounts are indistinguishable from real ones (CLAUDE.md
// Resolved Design Decision #1, item 2). pepper is the server-side
// AuthConfig.ServerPepper secret; it must never be derivable from the
// response, and the salt itself is not secret (only used to avoid revealing
// account existence).
func FakeSalt(pepper []byte, email string) []byte {
	mac := hmac.New(sha256.New, pepper)
	_, _ = mac.Write([]byte(normalizeEmail(email)))
	sum := mac.Sum(nil)
	return sum[:fakeSaltLen]
}

// normalizeEmail lowercases and trims surrounding whitespace so lookups and
// the fake-salt HMAC are consistent regardless of client-side casing.
func normalizeEmail(email string) string {
	return strings.ToLower(strings.TrimSpace(email))
}

// NormalizeEmail exports normalizeEmail for use by handlers/store code that
// need the same canonicalization when looking up or persisting users.
func NormalizeEmail(email string) string {
	return normalizeEmail(email)
}
