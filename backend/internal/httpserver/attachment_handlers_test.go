package httpserver

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/auth"
)

// fakeAttachmentCiphertextB64 stands in for the nonce||ciphertext blob a
// real Android client would produce encrypting a compressed image with the
// VDK -- same spirit as fakeCiphertextB64, just a distinct payload so tests
// can tell filename-ciphertext and file-ciphertext apart.
func fakeAttachmentCiphertextB64() string {
	return base64.StdEncoding.EncodeToString([]byte("fake-encrypted-image-bytes-not-a-real-jpeg-or-png"))
}

func fakeAttachmentFilenameB64() string {
	return base64.StdEncoding.EncodeToString([]byte("fake-encrypted-filename-ciphertext"))
}

// setupItemForAttachment registers+logs in a user, then creates one
// category + vault item for them, returning everything a test needs to
// drive the attachment endpoints.
func setupItemForAttachment(t *testing.T, email string) (vDeps *vaultDeps, fs *fakeAuthStore, token string, itemID int64) {
	t.Helper()
	deps, fs := testDeps()
	vDeps = testVaultDeps(t, fs)
	token = loginAndGetToken(t, deps, email)

	catsRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	var cats []categoryResponse
	_ = json.NewDecoder(catsRec.Body).Decode(&cats)

	itemRec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID: cats[0].ID, EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(token))
	var item vaultItemResponse
	_ = json.NewDecoder(itemRec.Body).Decode(&item)

	return vDeps, fs, token, item.ID
}

// --- upload / get / delete round trip ---------------------------------

func TestAttachment_UploadGetDelete(t *testing.T) {
	vDeps, fs, token, itemID := setupItemForAttachment(t, "attach-crud@example.com")

	uploadReq := uploadAttachmentRequest{
		EncryptedFilename: fakeAttachmentFilenameB64(),
		MimeType:          "image/jpeg",
		EncryptedData:     fakeAttachmentCiphertextB64(),
	}
	uploadRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemID)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadReq, authHeader(token))
	if uploadRec.Code != http.StatusCreated {
		t.Fatalf("upload: expected 201, got %d: %s", uploadRec.Code, uploadRec.Body.String())
	}
	var created attachmentResponse
	_ = json.NewDecoder(uploadRec.Body).Decode(&created)
	if created.VaultItemID != itemID {
		t.Fatalf("expected vault_item_id %d, got %d", itemID, created.VaultItemID)
	}
	if created.EncryptedFilename != uploadReq.EncryptedFilename {
		t.Fatal("expected encrypted_filename to round-trip byte-for-byte")
	}
	if created.MimeType != "image/jpeg" {
		t.Fatalf("expected mime_type to round-trip, got %q", created.MimeType)
	}

	getRec := doJSON(t, authedHandler(fs, withPathIDs(vDeps.handleGetAttachment, itemID, created.ID)),
		http.MethodGet, "/api/v1/vault/items/x/attachments/y", nil, authHeader(token))
	if getRec.Code != http.StatusOK {
		t.Fatalf("get: expected 200, got %d: %s", getRec.Code, getRec.Body.String())
	}
	var fetched attachmentDataResponse
	_ = json.NewDecoder(getRec.Body).Decode(&fetched)
	if fetched.EncryptedData != uploadReq.EncryptedData {
		t.Fatal("expected downloaded encrypted_data to match uploaded bytes exactly")
	}
	if fetched.EncryptedFilename != uploadReq.EncryptedFilename {
		t.Fatal("expected downloaded encrypted_filename to match uploaded bytes exactly")
	}

	deleteRec := doJSON(t, authedHandler(fs, withPathIDs(vDeps.handleDeleteAttachment, itemID, created.ID)),
		http.MethodDelete, "/api/v1/vault/items/x/attachments/y", nil, authHeader(token))
	if deleteRec.Code != http.StatusNoContent {
		t.Fatalf("delete: expected 204, got %d: %s", deleteRec.Code, deleteRec.Body.String())
	}

	getAfterDeleteRec := doJSON(t, authedHandler(fs, withPathIDs(vDeps.handleGetAttachment, itemID, created.ID)),
		http.MethodGet, "/api/v1/vault/items/x/attachments/y", nil, authHeader(token))
	if getAfterDeleteRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 after delete, got %d", getAfterDeleteRec.Code)
	}
}

// TestAttachment_FileStoredAsOpaqueBytesNotAtClientPath is the closest
// unit-test-level proxy to the Docker acceptance check ("stored image files
// ... cannot be opened directly as normal image files"): it confirms the
// server never derives the on-disk filename/path from client input (which
// would be the actual security bug -- path traversal via encrypted_filename
// -- rather than image-format detection, which is a Docker-level manual
// check, see CLAUDE.md/report). The file that IS written must contain
// exactly the uploaded ciphertext bytes.
func TestAttachment_FileStoredAsOpaqueBytesNotAtClientPath(t *testing.T) {
	vDeps, fs, token, itemID := setupItemForAttachment(t, "attach-fileformat@example.com")

	maliciousFilename := base64.StdEncoding.EncodeToString([]byte("../../../etc/passwd"))
	uploadReq := uploadAttachmentRequest{
		EncryptedFilename: maliciousFilename,
		MimeType:          "image/png",
		EncryptedData:     fakeAttachmentCiphertextB64(),
	}
	uploadRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemID)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadReq, authHeader(token))
	if uploadRec.Code != http.StatusCreated {
		t.Fatalf("upload: expected 201, got %d: %s", uploadRec.Code, uploadRec.Body.String())
	}

	entries, err := os.ReadDir(vDeps.attachmentsDir)
	if err != nil {
		t.Fatalf("read attachmentsDir: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("expected exactly one per-user subdirectory under attachmentsDir, got %d", len(entries))
	}
	userDir := filepath.Join(vDeps.attachmentsDir, entries[0].Name())
	files, err := os.ReadDir(userDir)
	if err != nil {
		t.Fatalf("read user attachment dir: %v", err)
	}
	if len(files) != 1 {
		t.Fatalf("expected exactly one attachment file, got %d", len(files))
	}
	// The server-generated filename must be a random hex ID, never anything
	// derived from the (still-encrypted, but attacker-controlled-in-shape)
	// encrypted_filename field.
	if bytes.Contains([]byte(files[0].Name()), []byte("etc")) || bytes.Contains([]byte(files[0].Name()), []byte("passwd")) {
		t.Fatalf("attachment filename must never be derived from client input, got %q", files[0].Name())
	}

	onDisk, err := os.ReadFile(filepath.Join(userDir, files[0].Name()))
	if err != nil {
		t.Fatalf("read attachment file: %v", err)
	}
	wantBytes, _ := base64.StdEncoding.DecodeString(uploadReq.EncryptedData)
	if !bytes.Equal(onDisk, wantBytes) {
		t.Fatal("on-disk file content must match the uploaded ciphertext bytes exactly")
	}
}

// --- ownership isolation (SPEC-BASE.md Section 47) -------------------------

func TestAttachment_UserIsolation(t *testing.T) {
	vDeps, fs, tokenA, itemA := setupItemForAttachment(t, "attach-userA@example.com")
	// A second authDeps sharing the SAME fake store, so "user B" is a real,
	// distinct account registered against the same backing data user A's
	// item/attachment already live in.
	depsB := &authDeps{
		store:   fs,
		logger:  discardLogger(),
		cfg:     testAuthConfig(),
		lockout: auth.NewAccountLockout(5, 15*time.Minute, 5*time.Minute),
	}
	tokenB := loginAndGetToken(t, depsB, "attach-userB@example.com")

	uploadRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemA)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadAttachmentRequest{
			EncryptedFilename: fakeAttachmentFilenameB64(),
			MimeType:          "image/jpeg",
			EncryptedData:     fakeAttachmentCiphertextB64(),
		}, authHeader(tokenA))
	if uploadRec.Code != http.StatusCreated {
		t.Fatalf("setup upload: expected 201, got %d: %s", uploadRec.Code, uploadRec.Body.String())
	}
	var attA attachmentResponse
	_ = json.NewDecoder(uploadRec.Body).Decode(&attA)

	// User B cannot upload to user A's item.
	uploadAsBRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemA)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadAttachmentRequest{
			EncryptedFilename: fakeAttachmentFilenameB64(),
			MimeType:          "image/jpeg",
			EncryptedData:     fakeAttachmentCiphertextB64(),
		}, authHeader(tokenB))
	if uploadAsBRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 uploading to another user's item, got %d", uploadAsBRec.Code)
	}

	// User B cannot GET user A's attachment.
	getAsBRec := doJSON(t, authedHandler(fs, withPathIDs(vDeps.handleGetAttachment, itemA, attA.ID)),
		http.MethodGet, "/api/v1/vault/items/x/attachments/y", nil, authHeader(tokenB))
	if getAsBRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 reading another user's attachment, got %d", getAsBRec.Code)
	}

	// User B cannot DELETE user A's attachment.
	deleteAsBRec := doJSON(t, authedHandler(fs, withPathIDs(vDeps.handleDeleteAttachment, itemA, attA.ID)),
		http.MethodDelete, "/api/v1/vault/items/x/attachments/y", nil, authHeader(tokenB))
	if deleteAsBRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 deleting another user's attachment, got %d", deleteAsBRec.Code)
	}

	// User A's own access still works (isolation didn't break the happy path).
	getAsARec := doJSON(t, authedHandler(fs, withPathIDs(vDeps.handleGetAttachment, itemA, attA.ID)),
		http.MethodGet, "/api/v1/vault/items/x/attachments/y", nil, authHeader(tokenA))
	if getAsARec.Code != http.StatusOK {
		t.Fatalf("expected owner to still read their own attachment, got %d", getAsARec.Code)
	}
}

// TestAttachment_ItemMismatchRejected verifies an attachment can't be
// reached through a *different* item ID than the one it's actually attached
// to, even for the attachment's rightful owner -- the URL carries both IDs
// and both must agree.
func TestAttachment_ItemMismatchRejected(t *testing.T) {
	vDeps, fs, token, itemA := setupItemForAttachment(t, "attach-mismatch@example.com")

	catsRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	var cats []categoryResponse
	_ = json.NewDecoder(catsRec.Body).Decode(&cats)
	otherItemRec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID: cats[1].ID, EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(token))
	var otherItem vaultItemResponse
	_ = json.NewDecoder(otherItemRec.Body).Decode(&otherItem)

	uploadRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemA)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadAttachmentRequest{
			EncryptedFilename: fakeAttachmentFilenameB64(),
			MimeType:          "image/jpeg",
			EncryptedData:     fakeAttachmentCiphertextB64(),
		}, authHeader(token))
	var att attachmentResponse
	_ = json.NewDecoder(uploadRec.Body).Decode(&att)

	// Same user, same attachment ID, but the *wrong* item ID in the path.
	mismatchRec := doJSON(t, authedHandler(fs, withPathIDs(vDeps.handleGetAttachment, otherItem.ID, att.ID)),
		http.MethodGet, "/api/v1/vault/items/x/attachments/y", nil, authHeader(token))
	if mismatchRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for item/attachment mismatch, got %d", mismatchRec.Code)
	}
}

// --- input validation -----------------------------------------------------

func TestAttachment_UploadToNonexistentItemRejected(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(t, fs)
	token := loginAndGetToken(t, deps, "attach-noitem@example.com")

	rec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, 999999)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadAttachmentRequest{
			EncryptedFilename: fakeAttachmentFilenameB64(),
			MimeType:          "image/jpeg",
			EncryptedData:     fakeAttachmentCiphertextB64(),
		}, authHeader(token))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for nonexistent item, got %d", rec.Code)
	}
}

func TestAttachment_MissingMimeTypeRejected(t *testing.T) {
	vDeps, fs, token, itemID := setupItemForAttachment(t, "attach-nomime@example.com")

	rec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemID)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadAttachmentRequest{
			EncryptedFilename: fakeAttachmentFilenameB64(),
			MimeType:          "",
			EncryptedData:     fakeAttachmentCiphertextB64(),
		}, authHeader(token))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for missing mime_type, got %d", rec.Code)
	}
}

func TestAttachment_EmptyEncryptedDataRejected(t *testing.T) {
	vDeps, fs, token, itemID := setupItemForAttachment(t, "attach-noempty@example.com")

	rec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemID)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadAttachmentRequest{
			EncryptedFilename: fakeAttachmentFilenameB64(),
			MimeType:          "image/jpeg",
			EncryptedData:     "",
		}, authHeader(token))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for empty encrypted_data, got %d", rec.Code)
	}
}

func TestAttachment_OversizedDataRejected(t *testing.T) {
	vDeps, fs, token, itemID := setupItemForAttachment(t, "attach-oversized@example.com")

	tooBig := base64.StdEncoding.EncodeToString(bytes.Repeat([]byte("x"), maxAttachmentDataBytes+1))
	rec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemID)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadAttachmentRequest{
			EncryptedFilename: fakeAttachmentFilenameB64(),
			MimeType:          "image/jpeg",
			EncryptedData:     tooBig,
		}, authHeader(token))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for oversized encrypted_data, got %d", rec.Code)
	}
}

// --- cascade cleanup on vault item delete -----------------------------------

// TestAttachment_DeletedWithParentVaultItem verifies that deleting a vault
// item removes both the attachment's DB row (already guaranteed by the FK's
// ON DELETE CASCADE against the fake store's behavior mirrored here) AND its
// on-disk encrypted file -- the part that has no DB-level safety net and
// must be handled explicitly (see deleteAttachmentsForItem).
func TestAttachment_DeletedWithParentVaultItem(t *testing.T) {
	vDeps, fs, token, itemID := setupItemForAttachment(t, "attach-cascade@example.com")

	uploadRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUploadAttachment, itemID)),
		http.MethodPost, "/api/v1/vault/items/x/attachments", uploadAttachmentRequest{
			EncryptedFilename: fakeAttachmentFilenameB64(),
			MimeType:          "image/jpeg",
			EncryptedData:     fakeAttachmentCiphertextB64(),
		}, authHeader(token))
	var att attachmentResponse
	_ = json.NewDecoder(uploadRec.Body).Decode(&att)

	userID := userIDFromToken(t, fs, token)
	storedAtt, err := fs.GetAttachment(t.Context(), userID, att.ID)
	if err != nil {
		t.Fatalf("fetch attachment from fake store: %v", err)
	}
	fullPath := filepath.Join(vDeps.attachmentsDir, storedAtt.StoragePath)
	if _, err := os.Stat(fullPath); err != nil {
		t.Fatalf("expected attachment file to exist before delete: %v", err)
	}

	delRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleDeleteVaultItem, itemID)),
		http.MethodDelete, "/api/v1/vault/items/x", nil, authHeader(token))
	if delRec.Code != http.StatusNoContent {
		t.Fatalf("delete item: expected 204, got %d: %s", delRec.Code, delRec.Body.String())
	}

	if _, err := os.Stat(fullPath); !os.IsNotExist(err) {
		t.Fatalf("expected attachment file to be removed after parent item delete, stat err: %v", err)
	}

	// The attachment metadata is gone too (fakeAuthStore.DeleteVaultItem
	// mirrors the real schema's ON DELETE CASCADE FK).
	getAfterRec := doJSON(t, authedHandler(fs, withPathIDs(vDeps.handleGetAttachment, itemID, att.ID)),
		http.MethodGet, "/api/v1/vault/items/x/attachments/y", nil, authHeader(token))
	if getAfterRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for attachment after parent item delete, got %d", getAfterRec.Code)
	}
}

// userIDFromToken is a small test-only helper to resolve a session token
// back to a user ID via the fake store, needed because store.GetAttachment
// requires a userID to do its ownership check.
func userIDFromToken(t *testing.T, fs *fakeAuthStore, token string) int64 {
	t.Helper()
	sess, err := fs.GetSessionByTokenHash(t.Context(), auth.HashSessionToken(token))
	if err != nil {
		t.Fatalf("resolve session: %v", err)
	}
	return sess.UserID
}
