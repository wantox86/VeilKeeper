package httpserver

import (
	"context"
	"errors"
	"net/http"
	"testing"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// Phase 0 of plan.md's "Devices & sessions" feature adds
// store.ListDevices/RevokeDeviceAndSessions plus deviceID request-context
// propagation, ahead of Phase 1's HTTP handlers/routes. There is no handler
// to exercise these through yet, so these tests drive fakeAuthStore (the
// same fake Phase 1's handler tests will later reuse) and requireSession
// directly.

func TestFakeStore_ListDevices_ScopedByUser(t *testing.T) {
	fs := newFakeAuthStore()
	ctx := context.Background()

	userA := mustCreateUser(t, fs, "devices-a@example.com")
	userB := mustCreateUser(t, fs, "devices-b@example.com")

	devA1, err := fs.UpsertDevice(ctx, userA, "a-phone", "Pixel")
	if err != nil {
		t.Fatalf("UpsertDevice(A, phone): %v", err)
	}
	devA2, err := fs.UpsertDevice(ctx, userA, "a-laptop", "Laptop")
	if err != nil {
		t.Fatalf("UpsertDevice(A, laptop): %v", err)
	}
	if _, err := fs.UpsertDevice(ctx, userB, "b-phone", "iPhone"); err != nil {
		t.Fatalf("UpsertDevice(B, phone): %v", err)
	}

	devices, err := fs.ListDevices(ctx, userA)
	if err != nil {
		t.Fatalf("ListDevices(A): %v", err)
	}
	if len(devices) != 2 {
		t.Fatalf("expected 2 devices for user A, got %d", len(devices))
	}
	gotIDs := map[int64]bool{devices[0].ID: true, devices[1].ID: true}
	if !gotIDs[devA1] || !gotIDs[devA2] {
		t.Fatalf("ListDevices(A) missing expected device IDs: got %v, want %d and %d", gotIDs, devA1, devA2)
	}
	for _, d := range devices {
		if d.UserID != userA {
			t.Fatalf("ListDevices(A) leaked a device owned by another user: %+v", d)
		}
	}

	devicesB, err := fs.ListDevices(ctx, userB)
	if err != nil {
		t.Fatalf("ListDevices(B): %v", err)
	}
	if len(devicesB) != 1 {
		t.Fatalf("expected 1 device for user B, got %d", len(devicesB))
	}
}

func TestFakeStore_UpsertDevice_ThenListDevices_ReflectsLatestName(t *testing.T) {
	fs := newFakeAuthStore()
	ctx := context.Background()
	userID := mustCreateUser(t, fs, "devices-rename@example.com")

	if _, err := fs.UpsertDevice(ctx, userID, "same-device", "Old Name"); err != nil {
		t.Fatalf("UpsertDevice (first login): %v", err)
	}
	if _, err := fs.UpsertDevice(ctx, userID, "same-device", "New Name"); err != nil {
		t.Fatalf("UpsertDevice (second login): %v", err)
	}

	devices, err := fs.ListDevices(ctx, userID)
	if err != nil {
		t.Fatalf("ListDevices: %v", err)
	}
	if len(devices) != 1 {
		t.Fatalf("expected UpsertDevice to update, not duplicate, the same identifier; got %d devices", len(devices))
	}
	if devices[0].DeviceName != "New Name" {
		t.Fatalf("expected device name to be refreshed to %q, got %q", "New Name", devices[0].DeviceName)
	}
}

func TestFakeStore_RevokeDeviceAndSessions_RevokesDeviceAndItsSessions(t *testing.T) {
	fs := newFakeAuthStore()
	ctx := context.Background()
	userID := mustCreateUser(t, fs, "revoke@example.com")

	deviceID, err := fs.UpsertDevice(ctx, userID, "device-1", "Phone")
	if err != nil {
		t.Fatalf("UpsertDevice: %v", err)
	}
	otherDeviceID, err := fs.UpsertDevice(ctx, userID, "device-2", "Laptop")
	if err != nil {
		t.Fatalf("UpsertDevice (other): %v", err)
	}

	const tokenHash = "hash-device-1"
	const otherTokenHash = "hash-device-2"
	expiresAt := time.Now().Add(time.Hour)
	if _, err := fs.CreateSession(ctx, userID, deviceID, tokenHash, expiresAt); err != nil {
		t.Fatalf("CreateSession: %v", err)
	}
	if _, err := fs.CreateSession(ctx, userID, otherDeviceID, otherTokenHash, expiresAt); err != nil {
		t.Fatalf("CreateSession (other device): %v", err)
	}

	if err := fs.RevokeDeviceAndSessions(ctx, userID, deviceID); err != nil {
		t.Fatalf("RevokeDeviceAndSessions: %v", err)
	}

	devices, err := fs.ListDevices(ctx, userID)
	if err != nil {
		t.Fatalf("ListDevices: %v", err)
	}
	var revoked, untouched *store.Device
	for i := range devices {
		switch devices[i].ID {
		case deviceID:
			revoked = &devices[i]
		case otherDeviceID:
			untouched = &devices[i]
		}
	}
	if revoked == nil || revoked.RevokedAt == nil {
		t.Fatalf("expected device %d to be revoked, got %+v", deviceID, revoked)
	}
	if untouched == nil || untouched.RevokedAt != nil {
		t.Fatalf("expected device %d to remain un-revoked, got %+v", otherDeviceID, untouched)
	}

	sess, err := fs.GetSessionByTokenHash(ctx, tokenHash)
	if err != nil {
		t.Fatalf("GetSessionByTokenHash: %v", err)
	}
	if sess.Valid(time.Now()) {
		t.Fatalf("expected session on the revoked device to be invalid, got %+v", sess)
	}

	otherSess, err := fs.GetSessionByTokenHash(ctx, otherTokenHash)
	if err != nil {
		t.Fatalf("GetSessionByTokenHash (other): %v", err)
	}
	if !otherSess.Valid(time.Now()) {
		t.Fatalf("expected session on the other (non-revoked) device to remain valid, got %+v", otherSess)
	}
}

func TestFakeStore_RevokeDeviceAndSessions_WrongUser_ReturnsNotFound(t *testing.T) {
	fs := newFakeAuthStore()
	ctx := context.Background()

	userA := mustCreateUser(t, fs, "owner@example.com")
	userB := mustCreateUser(t, fs, "attacker@example.com")

	deviceID, err := fs.UpsertDevice(ctx, userA, "victim-device", "Phone")
	if err != nil {
		t.Fatalf("UpsertDevice: %v", err)
	}
	const tokenHash = "victim-token"
	if _, err := fs.CreateSession(ctx, userA, deviceID, tokenHash, time.Now().Add(time.Hour)); err != nil {
		t.Fatalf("CreateSession: %v", err)
	}

	err = fs.RevokeDeviceAndSessions(ctx, userB, deviceID)
	if !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("expected ErrNotFound when user B tries to revoke user A's device, got %v", err)
	}

	sess, err := fs.GetSessionByTokenHash(ctx, tokenHash)
	if err != nil {
		t.Fatalf("GetSessionByTokenHash: %v", err)
	}
	if sess.RevokedAt != nil {
		t.Fatalf("user A's session must not be revoked by user B's rejected attempt, got %+v", sess)
	}
}

func TestDeviceIDFromContext_SetByRequireSession(t *testing.T) {
	deps, fs := testDeps()
	token := loginAndGetToken(t, deps, "context-device@example.com")

	var gotUserID, gotDeviceID int64
	var gotOK bool
	handler := authedHandler(fs, func(w http.ResponseWriter, r *http.Request) {
		gotUserID = userIDFromContext(r.Context())
		gotDeviceID, gotOK = deviceIDFromContext(r.Context())
		w.WriteHeader(http.StatusOK)
	})

	rec := doJSON(t, handler, http.MethodGet, "/api/v1/devices", nil, authHeader(token))
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}
	if gotUserID == 0 {
		t.Fatalf("expected userIDFromContext to return a non-zero user ID")
	}
	if !gotOK {
		t.Fatalf("expected deviceIDFromContext to report ok=true once requireSession has run")
	}
	if gotDeviceID == 0 {
		t.Fatalf("expected deviceIDFromContext to return a non-zero device ID")
	}
}

func TestDeviceIDFromContext_MissingWhenNotSet(t *testing.T) {
	if _, ok := deviceIDFromContext(context.Background()); ok {
		t.Fatalf("expected ok=false for a context requireSession never touched")
	}
}

func mustCreateUser(t *testing.T, fs *fakeAuthStore, email string) int64 {
	t.Helper()
	id, err := fs.CreateUser(context.Background(), store.NewUser{
		Email:       email,
		AuthKeyHash: "fake-hash",
		KDFSalt:     []byte("salt"),
		WrappedVDK:  []byte("wrapped"),
	})
	if err != nil {
		t.Fatalf("CreateUser(%s): %v", email, err)
	}
	return id
}
