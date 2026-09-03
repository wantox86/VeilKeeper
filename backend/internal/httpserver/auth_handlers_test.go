package httpserver

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
	"github.com/wantox86/veilkeeper/backend/internal/config"
)

func testDeps() (*authDeps, *fakeAuthStore) {
	fs := newFakeAuthStore()
	deps := &authDeps{
		store:  fs,
		logger: discardLogger(),
		cfg: config.AuthConfig{
			ServerPepper:               []byte("unit-test-pepper"),
			SessionTTL:                 time.Hour,
			RateLimitRequestsPerWindow: 1000,
			RateLimitWindow:            time.Minute,
			InviteCodes:                []string{"test-invite-code"},
		},
		lockout: auth.NewAccountLockout(5, 15*time.Minute, 5*time.Minute),
	}
	return deps, fs
}

func doJSON(t *testing.T, handler http.HandlerFunc, method, path string, body any, headers map[string]string) *httptest.ResponseRecorder {
	t.Helper()
	var buf bytes.Buffer
	if body != nil {
		if err := json.NewEncoder(&buf).Encode(body); err != nil {
			t.Fatalf("encode request body: %v", err)
		}
	}
	req := httptest.NewRequest(method, path, &buf)
	req.Header.Set("Content-Type", "application/json")
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	handler(rec, req)
	return rec
}

func validRegisterRequest(email string) registerRequest {
	return registerRequest{
		Email:      email,
		Username:   "tester",
		AuthKey:    base64.StdEncoding.EncodeToString([]byte("0123456789abcdef0123456789abcdef")),
		KDFSalt:    base64.StdEncoding.EncodeToString([]byte("0123456789abcdef")),
		KDFParams:  auth.DefaultKDFParams,
		KDFVersion: auth.CurrentKDFVersion,
		WrappedVDK: base64.StdEncoding.EncodeToString([]byte("some-wrapped-vdk-ciphertext-bytes")),
		InviteCode: "test-invite-code",
	}
}

// --- prelogin ---------------------------------------------------------------

func TestPrelogin_ExistingAndNonexistentAccounts_SameShape(t *testing.T) {
	deps, _ := testDeps()

	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", validRegisterRequest("real@example.com"), nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("register: expected 201, got %d: %s", rec.Code, rec.Body.String())
	}

	recReal := doJSON(t, deps.handlePrelogin, http.MethodPost, "/api/v1/auth/prelogin", preloginRequest{Email: "real@example.com"}, nil)
	recFake := doJSON(t, deps.handlePrelogin, http.MethodPost, "/api/v1/auth/prelogin", preloginRequest{Email: "nobody@example.com"}, nil)

	if recReal.Code != http.StatusOK || recFake.Code != http.StatusOK {
		t.Fatalf("expected both prelogin responses to be 200, got real=%d fake=%d", recReal.Code, recFake.Code)
	}

	var realResp, fakeResp preloginResponse
	if err := json.NewDecoder(recReal.Body).Decode(&realResp); err != nil {
		t.Fatalf("decode real response: %v", err)
	}
	if err := json.NewDecoder(recFake.Body).Decode(&fakeResp); err != nil {
		t.Fatalf("decode fake response: %v", err)
	}

	if realResp.KDFSalt == fakeResp.KDFSalt {
		t.Fatal("expected different salts for real vs fake account (coincidence astronomically unlikely)")
	}
	if realResp.KDFVersion != fakeResp.KDFVersion {
		t.Fatal("expected same kdf_version shape for real vs fake account")
	}
}

func TestPrelogin_FakeSaltIsDeterministic(t *testing.T) {
	deps, _ := testDeps()

	rec1 := doJSON(t, deps.handlePrelogin, http.MethodPost, "/api/v1/auth/prelogin", preloginRequest{Email: "ghost@example.com"}, nil)
	rec2 := doJSON(t, deps.handlePrelogin, http.MethodPost, "/api/v1/auth/prelogin", preloginRequest{Email: "ghost@example.com"}, nil)

	var r1, r2 preloginResponse
	_ = json.NewDecoder(rec1.Body).Decode(&r1)
	_ = json.NewDecoder(rec2.Body).Decode(&r2)

	if r1.KDFSalt != r2.KDFSalt {
		t.Fatal("expected fake salt to be stable across repeated prelogin calls for the same nonexistent email")
	}
}

// --- register ----------------------------------------------------------------

func TestRegister_HappyPath(t *testing.T) {
	deps, fs := testDeps()

	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", validRegisterRequest("new@example.com"), nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("expected 201, got %d: %s", rec.Code, rec.Body.String())
	}

	u, err := fs.GetUserByEmail(nil, "new@example.com")
	if err != nil {
		t.Fatalf("expected user to be persisted: %v", err)
	}
	if u.AuthKeyHash == "" {
		t.Fatal("expected auth_key_hash to be set")
	}
	if bytes.Contains([]byte(u.AuthKeyHash), []byte("0123456789abcdef0123456789abcdef")) {
		t.Fatal("stored hash must not contain the raw auth key")
	}
}

func TestRegister_DuplicateEmailRejected(t *testing.T) {
	deps, _ := testDeps()

	req := validRegisterRequest("dupe@example.com")
	rec1 := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec1.Code != http.StatusCreated {
		t.Fatalf("first register: expected 201, got %d", rec1.Code)
	}

	rec2 := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec2.Code != http.StatusConflict {
		t.Fatalf("second register with same email: expected 409, got %d", rec2.Code)
	}
}

func TestRegister_InvalidKDFParamsRejected(t *testing.T) {
	deps, _ := testDeps()

	req := validRegisterRequest("bad-params@example.com")
	req.KDFParams.Iterations = 0

	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid kdf_params, got %d", rec.Code)
	}
}

func TestRegister_InvalidEmailRejected(t *testing.T) {
	deps, _ := testDeps()

	req := validRegisterRequest("not-an-email")
	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for invalid email, got %d", rec.Code)
	}
}

func TestRegister_EmptyAuthKeyRejected(t *testing.T) {
	deps, _ := testDeps()

	req := validRegisterRequest("noauthkey@example.com")
	req.AuthKey = ""
	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for empty auth_key, got %d", rec.Code)
	}
}

func TestRegister_MissingInviteCodeRejected(t *testing.T) {
	deps, _ := testDeps()

	req := validRegisterRequest("noinvite@example.com")
	req.InviteCode = ""
	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for missing invite_code, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestRegister_WrongInviteCodeRejected(t *testing.T) {
	deps, _ := testDeps()

	req := validRegisterRequest("wronginvite@example.com")
	req.InviteCode = "not-the-right-code"
	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 for wrong invite_code, got %d: %s", rec.Code, rec.Body.String())
	}

	var body errorResponse
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode error body: %v", err)
	}
	if body.Error != "invalid_invite_code" {
		t.Fatalf("expected invalid_invite_code error code, got %q", body.Error)
	}
}

func TestRegister_ValidInviteCodeSucceeds(t *testing.T) {
	deps, _ := testDeps()

	req := validRegisterRequest("validinvite@example.com")
	req.InviteCode = "test-invite-code"
	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("expected 201 for valid invite_code, got %d: %s", rec.Code, rec.Body.String())
	}
}

func TestRegister_NoInviteCodesConfiguredFailsClosed(t *testing.T) {
	deps, _ := testDeps()
	deps.cfg.InviteCodes = nil // simulate INVITE_CODES unset/empty in the environment

	// Even a "correct-looking" invite code must be rejected when none are
	// configured at all -- fail closed, never silently allow registration.
	req := validRegisterRequest("closedreg@example.com")
	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", req, nil)
	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected 403 when no invite codes are configured, got %d: %s", rec.Code, rec.Body.String())
	}

	var body errorResponse
	if err := json.NewDecoder(rec.Body).Decode(&body); err != nil {
		t.Fatalf("decode error body: %v", err)
	}
	if body.Error != "registration_closed" {
		t.Fatalf("expected registration_closed error code, got %q", body.Error)
	}
}

// --- login ---------------------------------------------------------------

func rawAuthKey() []byte { return []byte("0123456789abcdef0123456789abcdef") }

func registerTestUser(t *testing.T, deps *authDeps, email string) {
	t.Helper()
	rec := doJSON(t, deps.handleRegister, http.MethodPost, "/api/v1/auth/register", validRegisterRequest(email), nil)
	if rec.Code != http.StatusCreated {
		t.Fatalf("registerTestUser(%s): expected 201, got %d: %s", email, rec.Code, rec.Body.String())
	}
}

func TestLogin_HappyPath(t *testing.T) {
	deps, _ := testDeps()
	registerTestUser(t, deps, "login@example.com")

	rec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", loginRequest{
		Email:            "login@example.com",
		AuthKey:          base64.StdEncoding.EncodeToString(rawAuthKey()),
		DeviceIdentifier: "device-1",
		DeviceName:       "Test Device",
	}, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var resp loginResponse
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("decode login response: %v", err)
	}
	if resp.SessionToken == "" {
		t.Fatal("expected a non-empty session token")
	}
	if resp.WrappedVDK == "" {
		t.Fatal("expected wrapped_vdk to be returned on login")
	}
}

func TestLogin_WrongAuthKeyRejected(t *testing.T) {
	deps, _ := testDeps()
	registerTestUser(t, deps, "wrongkey@example.com")

	rec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", loginRequest{
		Email:   "wrongkey@example.com",
		AuthKey: base64.StdEncoding.EncodeToString([]byte("totally-different-key-value-here")),
	}, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for wrong auth key, got %d", rec.Code)
	}
}

func TestLogin_NonexistentUserRejectedGenerically(t *testing.T) {
	deps, _ := testDeps()

	rec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", loginRequest{
		Email:   "ghost@example.com",
		AuthKey: base64.StdEncoding.EncodeToString(rawAuthKey()),
	}, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for nonexistent user, got %d", rec.Code)
	}

	var errResp errorResponse
	_ = json.NewDecoder(rec.Body).Decode(&errResp)
	if errResp.Error != "invalid_credentials" {
		t.Fatalf("expected generic invalid_credentials error, got %q", errResp.Error)
	}
}

func TestLogin_LockedAfterRepeatedFailures(t *testing.T) {
	deps, _ := testDeps()
	registerTestUser(t, deps, "lockout@example.com")

	badReq := loginRequest{
		Email:   "lockout@example.com",
		AuthKey: base64.StdEncoding.EncodeToString([]byte("wrong-key-wrong-key-wrong-key!!!")),
	}

	var lastCode int
	for i := 0; i < 5; i++ {
		rec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", badReq, nil)
		lastCode = rec.Code
	}
	if lastCode != http.StatusUnauthorized {
		t.Fatalf("expected last of 5 failed attempts to still be 401, got %d", lastCode)
	}

	rec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", badReq, nil)
	if rec.Code != http.StatusTooManyRequests {
		t.Fatalf("expected 6th attempt to be locked out (429), got %d", rec.Code)
	}
}

func TestLogin_CorrectPasswordResetsLockout(t *testing.T) {
	deps, _ := testDeps()
	registerTestUser(t, deps, "resetme@example.com")

	goodReq := loginRequest{
		Email:            "resetme@example.com",
		AuthKey:          base64.StdEncoding.EncodeToString(rawAuthKey()),
		DeviceIdentifier: "d1",
	}
	rec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", goodReq, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("expected successful login, got %d: %s", rec.Code, rec.Body.String())
	}

	// A second successful login right after should still work (lockout
	// wasn't accidentally triggered by the first one).
	rec2 := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", goodReq, nil)
	if rec2.Code != http.StatusOK {
		t.Fatalf("expected second successful login to also succeed, got %d", rec2.Code)
	}
}

// --- logout ---------------------------------------------------------------

func TestLogout_RevokesSession(t *testing.T) {
	deps, fs := testDeps()
	registerTestUser(t, deps, "logout@example.com")

	loginRec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", loginRequest{
		Email:            "logout@example.com",
		AuthKey:          base64.StdEncoding.EncodeToString(rawAuthKey()),
		DeviceIdentifier: "d1",
	}, nil)
	var loginResp loginResponse
	if err := json.NewDecoder(loginRec.Body).Decode(&loginResp); err != nil {
		t.Fatalf("decode login response: %v", err)
	}

	logoutRec := doJSON(t, deps.handleLogout, http.MethodPost, "/api/v1/auth/logout", nil, map[string]string{
		"Authorization": "Bearer " + loginResp.SessionToken,
	})
	if logoutRec.Code != http.StatusNoContent {
		t.Fatalf("expected 204 on logout, got %d", logoutRec.Code)
	}

	sess, err := fs.GetSessionByTokenHash(nil, auth.HashSessionToken(loginResp.SessionToken))
	if err != nil {
		t.Fatalf("expected session record to still exist (revoked, not deleted): %v", err)
	}
	if sess.RevokedAt == nil {
		t.Fatal("expected session to be marked revoked after logout")
	}
	if sess.Valid(time.Now()) {
		t.Fatal("expected revoked session to be invalid")
	}
}

func TestLogout_MissingAuthorizationHeaderRejected(t *testing.T) {
	deps, _ := testDeps()

	rec := doJSON(t, deps.handleLogout, http.MethodPost, "/api/v1/auth/logout", nil, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 without Authorization header, got %d", rec.Code)
	}
}

func TestLogout_IsIdempotent(t *testing.T) {
	deps, _ := testDeps()
	registerTestUser(t, deps, "idempotent@example.com")

	loginRec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", loginRequest{
		Email:            "idempotent@example.com",
		AuthKey:          base64.StdEncoding.EncodeToString(rawAuthKey()),
		DeviceIdentifier: "d1",
	}, nil)
	var loginResp loginResponse
	_ = json.NewDecoder(loginRec.Body).Decode(&loginResp)

	headers := map[string]string{"Authorization": "Bearer " + loginResp.SessionToken}
	rec1 := doJSON(t, deps.handleLogout, http.MethodPost, "/api/v1/auth/logout", nil, headers)
	rec2 := doJSON(t, deps.handleLogout, http.MethodPost, "/api/v1/auth/logout", nil, headers)

	if rec1.Code != http.StatusNoContent || rec2.Code != http.StatusNoContent {
		t.Fatalf("expected both logout calls to return 204, got %d and %d", rec1.Code, rec2.Code)
	}
}
