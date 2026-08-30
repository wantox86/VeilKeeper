package auth

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"fmt"
)

const sessionTokenLen = 32 // 256-bit opaque bearer token

// NewSessionToken generates a new random opaque session token (returned to
// the client) and its SHA-256 hex hash (what's actually stored in
// sessions.token_hash). The server never stores the raw token -- only its
// hash -- so a DB leak alone does not yield usable bearer tokens, the same
// principle used for password/AuthKey storage.
func NewSessionToken() (token string, tokenHash string, err error) {
	buf := make([]byte, sessionTokenLen)
	if _, err := rand.Read(buf); err != nil {
		return "", "", fmt.Errorf("auth: generate session token: %w", err)
	}
	token = base64.RawURLEncoding.EncodeToString(buf)
	return token, HashSessionToken(token), nil
}

// HashSessionToken hashes a bearer token as presented by a client (e.g. in
// the Authorization header) so it can be looked up against
// sessions.token_hash without ever storing/comparing the raw token.
func HashSessionToken(token string) string {
	sum := sha256.Sum256([]byte(token))
	return hex.EncodeToString(sum[:])
}
