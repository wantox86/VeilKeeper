package httpserver

import (
	"encoding/base64"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
	"github.com/wantox86/veilkeeper/backend/internal/config"
	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// authDeps bundles what the Sprint 1 auth handlers need. Kept as a struct
// (rather than package-level globals) so NewMux can construct fresh,
// per-process rate limiters/lockout trackers.
type authDeps struct {
	store   store.AuthStore
	logger  *slog.Logger
	cfg     config.AuthConfig
	lockout *auth.AccountLockout
	nowFunc func() time.Time
}

const maxAuthBodyBytes = 1 << 16 // 64 KiB -- generous for these small JSON payloads

// --- request/response DTOs -------------------------------------------------

type preloginRequest struct {
	Email string `json:"email"`
}

type preloginResponse struct {
	KDFSalt    string         `json:"kdf_salt"` // base64
	KDFParams  auth.KDFParams `json:"kdf_params"`
	KDFVersion int            `json:"kdf_version"`
}

type registerRequest struct {
	Email      string         `json:"email"`
	Username   string         `json:"username"`
	AuthKey    string         `json:"auth_key"` // base64
	KDFSalt    string         `json:"kdf_salt"` // base64, client-generated (see CLAUDE.md update note)
	KDFParams  auth.KDFParams `json:"kdf_params"`
	KDFVersion int            `json:"kdf_version"`
	WrappedVDK string         `json:"wrapped_vdk"` // base64
}

type registerResponse struct {
	UserID int64  `json:"user_id"`
	Email  string `json:"email"`
}

type loginRequest struct {
	Email            string `json:"email"`
	AuthKey          string `json:"auth_key"` // base64
	DeviceIdentifier string `json:"device_identifier"`
	DeviceName       string `json:"device_name"`
}

type loginResponse struct {
	SessionToken string         `json:"session_token"`
	ExpiresAt    time.Time      `json:"expires_at"`
	WrappedVDK   string         `json:"wrapped_vdk"` // base64
	KDFSalt      string         `json:"kdf_salt"`    // base64
	KDFParams    auth.KDFParams `json:"kdf_params"`
	KDFVersion   int            `json:"kdf_version"`
}

type errorResponse struct {
	Error   string `json:"error"`
	Message string `json:"message"`
}

// --- handlers ---------------------------------------------------------------

// handlePrelogin implements POST /api/v1/auth/prelogin. Per CLAUDE.md
// Resolved Design Decision #1, it must be indistinguishable in shape/timing
// between real and nonexistent accounts (anti-enumeration).
func (d *authDeps) handlePrelogin(w http.ResponseWriter, r *http.Request) {
	var req preloginRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	email := strings.TrimSpace(req.Email)
	if email == "" {
		writeError(w, http.StatusBadRequest, "invalid_request", "email is required")
		return
	}

	user, err := d.store.GetUserByEmail(r.Context(), email)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, preloginResponse{
			KDFSalt:    base64.StdEncoding.EncodeToString(user.KDFSalt),
			KDFParams:  user.KDFParams,
			KDFVersion: user.KDFVersion,
		})
	case errors.Is(err, store.ErrNotFound):
		writeJSON(w, http.StatusOK, preloginResponse{
			KDFSalt:    base64.StdEncoding.EncodeToString(auth.FakeSalt(d.cfg.ServerPepper, email)),
			KDFParams:  auth.DefaultKDFParams,
			KDFVersion: auth.CurrentKDFVersion,
		})
	default:
		d.logger.Error("prelogin: store lookup failed", "error", err.Error())
		writeInternalError(w)
	}
}

// handleRegister implements POST /api/v1/auth/register.
//
// Implementation note / deliberate deviation: CLAUDE.md describes the server
// as generating kdf_salt/kdf_params at "account creation," which for a
// single-round-trip register call (this repo's Sprint 1 scope: register,
// login, logout, prelogin -- no separate "register/init" step) would be a
// chicken-and-egg problem, since the client must already have the salt to
// derive AuthKey *before* sending it. kdf_salt is not secret (only
// wrapped_vdk and the password itself are), so this implementation has the
// client generate kdf_salt locally (CSPRNG) and echo kdf_params/kdf_version
// back to the server for storage alongside it, same as it would for any
// other client-supplied-but-non-secret value. The server validates params
// are within a sane range (ValidateKDFParams) but never executes Argon2id
// with them itself. This keeps registration a single round trip, matching
// SPEC-BASE.md Section 56 ("no premature overengineering"), without
// weakening the zero-knowledge property.
func (d *authDeps) handleRegister(w http.ResponseWriter, r *http.Request) {
	var req registerRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	email := strings.TrimSpace(req.Email)
	if email == "" || !looksLikeEmail(email) {
		writeError(w, http.StatusBadRequest, "invalid_request", "a valid email is required")
		return
	}

	authKey, err := base64.StdEncoding.DecodeString(req.AuthKey)
	if err != nil || len(authKey) == 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", "auth_key must be non-empty base64")
		return
	}

	kdfSalt, err := base64.StdEncoding.DecodeString(req.KDFSalt)
	if err != nil || len(kdfSalt) < 16 {
		writeError(w, http.StatusBadRequest, "invalid_request", "kdf_salt must be base64-encoded and at least 16 bytes")
		return
	}

	if req.KDFVersion < 1 {
		writeError(w, http.StatusBadRequest, "invalid_request", "kdf_version must be >= 1")
		return
	}

	if err := auth.ValidateKDFParams(req.KDFParams); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", err.Error())
		return
	}

	wrappedVDK, err := base64.StdEncoding.DecodeString(req.WrappedVDK)
	if err != nil || len(wrappedVDK) == 0 || len(wrappedVDK) > 512 {
		writeError(w, http.StatusBadRequest, "invalid_request", "wrapped_vdk must be non-empty base64 (max 512 bytes decoded)")
		return
	}

	authKeyHash, err := auth.HashAuthKey(authKey)
	if err != nil {
		d.logger.Error("register: failed to hash auth key", "error", err.Error())
		writeInternalError(w)
		return
	}

	userID, err := d.store.CreateUser(r.Context(), store.NewUser{
		Email:       email,
		Username:    strings.TrimSpace(req.Username),
		AuthKeyHash: authKeyHash,
		KDFSalt:     kdfSalt,
		KDFParams:   req.KDFParams,
		KDFVersion:  req.KDFVersion,
		WrappedVDK:  wrappedVDK,
	})
	switch {
	case err == nil:
		writeJSON(w, http.StatusCreated, registerResponse{UserID: userID, Email: auth.NormalizeEmail(email)})
	case errors.Is(err, store.ErrAlreadyExists):
		writeError(w, http.StatusConflict, "email_taken", "an account with this email already exists")
	default:
		d.logger.Error("register: store create failed", "error", err.Error())
		writeInternalError(w)
	}
}

// handleLogin implements POST /api/v1/auth/login.
func (d *authDeps) handleLogin(w http.ResponseWriter, r *http.Request) {
	var req loginRequest
	if !decodeJSON(w, r, &req) {
		return
	}

	email := strings.TrimSpace(req.Email)
	if email == "" {
		writeError(w, http.StatusBadRequest, "invalid_request", "email is required")
		return
	}

	if d.lockout.Locked(email) {
		writeError(w, http.StatusTooManyRequests, "too_many_attempts", "too many failed attempts, try again later")
		return
	}

	authKey, err := base64.StdEncoding.DecodeString(req.AuthKey)
	if err != nil || len(authKey) == 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", "auth_key must be non-empty base64")
		return
	}

	user, err := d.store.GetUserByEmail(r.Context(), email)
	if errors.Is(err, store.ErrNotFound) {
		auth.VerifyAgainstDummyHash(authKey) // keep timing consistent, see doc comment there
		d.lockout.RecordFailure(email)
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid email or auth key")
		return
	}
	if err != nil {
		d.logger.Error("login: store lookup failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	ok, err := auth.VerifyAuthKey(authKey, user.AuthKeyHash)
	if err != nil {
		d.logger.Error("login: verify failed", "error", err.Error())
		writeInternalError(w)
		return
	}
	if !ok {
		d.lockout.RecordFailure(email)
		writeError(w, http.StatusUnauthorized, "invalid_credentials", "invalid email or auth key")
		return
	}

	d.lockout.Reset(email)

	deviceID, err := d.store.UpsertDevice(r.Context(), user.ID, strings.TrimSpace(req.DeviceIdentifier), strings.TrimSpace(req.DeviceName))
	if err != nil {
		d.logger.Error("login: upsert device failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	token, tokenHash, err := auth.NewSessionToken()
	if err != nil {
		d.logger.Error("login: generate session token failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	expiresAt := d.now().Add(d.cfg.SessionTTL)
	if _, err := d.store.CreateSession(r.Context(), user.ID, deviceID, tokenHash, expiresAt); err != nil {
		d.logger.Error("login: create session failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	writeJSON(w, http.StatusOK, loginResponse{
		SessionToken: token,
		ExpiresAt:    expiresAt,
		WrappedVDK:   base64.StdEncoding.EncodeToString(user.WrappedVDK),
		KDFSalt:      base64.StdEncoding.EncodeToString(user.KDFSalt),
		KDFParams:    user.KDFParams,
		KDFVersion:   user.KDFVersion,
	})
}

// handleLogout implements POST /api/v1/auth/logout. Idempotent: revoking an
// already-revoked or nonexistent session still returns 204, since the
// client's desired end state ("this token no longer works") already holds.
func (d *authDeps) handleLogout(w http.ResponseWriter, r *http.Request) {
	token, ok := bearerToken(r)
	if !ok {
		writeError(w, http.StatusUnauthorized, "unauthorized", "missing or malformed Authorization header")
		return
	}

	if err := d.store.RevokeSession(r.Context(), auth.HashSessionToken(token)); err != nil {
		d.logger.Error("logout: revoke session failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	w.WriteHeader(http.StatusNoContent)
}

func (d *authDeps) now() time.Time {
	if d.nowFunc != nil {
		return d.nowFunc()
	}
	return time.Now()
}

// --- helpers ----------------------------------------------------------------

func bearerToken(r *http.Request) (string, bool) {
	h := r.Header.Get("Authorization")
	const prefix = "Bearer "
	if !strings.HasPrefix(h, prefix) {
		return "", false
	}
	token := strings.TrimSpace(strings.TrimPrefix(h, prefix))
	if token == "" {
		return "", false
	}
	return token, true
}

func looksLikeEmail(s string) bool {
	at := strings.IndexByte(s, '@')
	return at > 0 && at < len(s)-1 && !strings.ContainsAny(s, " \t\n")
}

// decodeJSON reads and decodes a JSON request body into dst, writing a 400
// response and returning false on any failure (empty body, malformed JSON,
// oversized body, unknown fields). Never echoes raw parse errors that might
// contain fragments of secret request fields.
func decodeJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxAuthBodyBytes)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "malformed request body")
		return false
	}
	return true
}

func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, errorResponse{Error: code, Message: message})
}

func writeInternalError(w http.ResponseWriter) {
	writeJSON(w, http.StatusInternalServerError, errorResponse{Error: "internal_error", Message: "Something went wrong."})
}
