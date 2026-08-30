package httpserver

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// Sprint 5: attachments (SPEC-BASE.md Section 7/17/29/31).
//
// The server treats an attachment's bytes exactly like a vault item's
// encrypted_payload: opaque client-produced AES-256-GCM ciphertext it never
// decodes or inspects. The one difference from vault items is *where* the
// bytes live -- on the local filesystem (SPEC-BASE.md Section 7), not in
// MySQL -- so this file also owns the small amount of file I/O that implies.
//
// Content-block linking decision (documented here since CLAUDE.md didn't
// cover it before this sprint): a vault item's payload (VaultItemPayload,
// Android-side) can contain a content block with type "image"; that block's
// existing `value` string field (already used generically for "the block's
// content") holds the attachment's numeric ID as a decimal string. No new
// field was added to the block schema -- SPEC-BASE.md Section 56 Rule 1
// ("no premature overengineering") argues against widening a shared struct
// for one type when the existing field already fits. The server itself
// never parses vault item payloads at all (they're ciphertext to it), so
// this decision is purely an Android-side (crypto/VaultItemCrypto.kt)
// concern reflected here only as a comment for context.

const (
	// maxAttachmentDataBytes bounds the *decoded* encrypted attachment blob
	// size. Generous for a single compressed photo (SPEC-BASE.md Section 17
	// expects the Android client to compress before encrypting) while still
	// bounding worst-case disk/memory usage per upload on a homelab host.
	maxAttachmentDataBytes = 8 << 20 // 8 MiB

	// maxEncryptedFilenameBytes bounds the opaque ciphertext of a filename --
	// there is no legitimate reason for this to be large.
	maxEncryptedFilenameBytes = 1024

	// maxAttachmentBodyBytes bounds the raw JSON request body: the encrypted
	// data travels base64-encoded (~4/3 overhead) plus JSON framing and the
	// filename field, so this must be somewhat larger than
	// maxAttachmentDataBytes alone.
	maxAttachmentBodyBytes = 11 << 20 // 11 MiB
)

// --- DTOs --------------------------------------------------------------

// attachmentResponse is returned after a successful upload -- deliberately
// does NOT include encrypted_data (the caller already has the bytes it just
// uploaded; echoing them back would just waste bandwidth).
type attachmentResponse struct {
	ID                int64     `json:"id"`
	VaultItemID       int64     `json:"vault_item_id"`
	EncryptedFilename string    `json:"encrypted_filename"` // base64
	MimeType          string    `json:"mime_type"`
	Size              int64     `json:"size"`
	CreatedAt         time.Time `json:"created_at"`
}

// attachmentDataResponse is returned by GET (download) -- includes the
// encrypted bytes so the client can decrypt+preview.
type attachmentDataResponse struct {
	ID                int64     `json:"id"`
	VaultItemID       int64     `json:"vault_item_id"`
	EncryptedFilename string    `json:"encrypted_filename"` // base64
	MimeType          string    `json:"mime_type"`
	Size              int64     `json:"size"`
	EncryptedData     string    `json:"encrypted_data"` // base64
	CreatedAt         time.Time `json:"created_at"`
}

type uploadAttachmentRequest struct {
	EncryptedFilename string `json:"encrypted_filename"` // base64
	MimeType          string `json:"mime_type"`
	EncryptedData     string `json:"encrypted_data"` // base64
}

func toAttachmentResponse(a store.Attachment) attachmentResponse {
	return attachmentResponse{
		ID:                a.ID,
		VaultItemID:       a.VaultItemID,
		EncryptedFilename: base64.StdEncoding.EncodeToString(a.EncryptedFilename),
		MimeType:          a.MimeType,
		Size:              a.Size,
		CreatedAt:         a.CreatedAt,
	}
}

// --- handlers ------------------------------------------------------------

// handleUploadAttachment implements POST /api/v1/vault/items/{id}/attachments.
func (d *vaultDeps) handleUploadAttachment(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	itemID, ok := pathID(w, r)
	if !ok {
		return
	}

	// Ownership check on the parent item BEFORE touching the filesystem --
	// no bytes get written for an item the caller doesn't own.
	if _, err := d.store.GetVaultItem(r.Context(), userID, itemID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "vault item not found")
		} else {
			d.logger.Error("upload attachment: get item failed", "error", err.Error())
			writeInternalError(w)
		}
		return
	}

	var req uploadAttachmentRequest
	if !decodeAttachmentJSON(w, r, &req) {
		return
	}

	encryptedFilename, ok := decodeBase64Field(w, req.EncryptedFilename, maxEncryptedFilenameBytes, "encrypted_filename")
	if !ok {
		return
	}

	mimeType := req.MimeType
	if mimeType == "" || len(mimeType) > 255 {
		writeError(w, http.StatusBadRequest, "invalid_request", "mime_type is required and must be at most 255 characters")
		return
	}

	encryptedData, ok := decodeBase64Field(w, req.EncryptedData, maxAttachmentDataBytes, "encrypted_data")
	if !ok {
		return
	}

	storagePath, err := writeAttachmentFile(d.attachmentsDir, userID, encryptedData)
	if err != nil {
		d.logger.Error("upload attachment: write file failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	att, err := d.store.CreateAttachment(r.Context(), userID, itemID, encryptedFilename, mimeType, int64(len(encryptedData)), storagePath)
	if err != nil {
		// Roll back the file we just wrote so a failed DB insert never
		// leaves an orphaned encrypted blob on disk.
		if removeErr := removeAttachmentFile(d.attachmentsDir, storagePath); removeErr != nil {
			d.logger.Error("upload attachment: rollback file cleanup failed", "error", removeErr.Error())
		}
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "vault item not found")
			return
		}
		d.logger.Error("upload attachment: store failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	writeJSON(w, http.StatusCreated, toAttachmentResponse(att))
}

// handleGetAttachment implements
// GET /api/v1/vault/items/{id}/attachments/{attachmentId}.
func (d *vaultDeps) handleGetAttachment(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	itemID, ok := pathID(w, r)
	if !ok {
		return
	}
	attachmentID, ok := pathAttachmentID(w, r)
	if !ok {
		return
	}

	att, ok := d.getOwnedAttachment(w, r.Context(), userID, itemID, attachmentID)
	if !ok {
		return
	}

	data, err := readAttachmentFile(d.attachmentsDir, att.StoragePath)
	if err != nil {
		d.logger.Error("get attachment: read file failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	writeJSON(w, http.StatusOK, attachmentDataResponse{
		ID:                att.ID,
		VaultItemID:       att.VaultItemID,
		EncryptedFilename: base64.StdEncoding.EncodeToString(att.EncryptedFilename),
		MimeType:          att.MimeType,
		Size:              att.Size,
		EncryptedData:     base64.StdEncoding.EncodeToString(data),
		CreatedAt:         att.CreatedAt,
	})
}

// handleDeleteAttachment implements
// DELETE /api/v1/vault/items/{id}/attachments/{attachmentId}.
func (d *vaultDeps) handleDeleteAttachment(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	itemID, ok := pathID(w, r)
	if !ok {
		return
	}
	attachmentID, ok := pathAttachmentID(w, r)
	if !ok {
		return
	}

	att, ok := d.getOwnedAttachment(w, r.Context(), userID, itemID, attachmentID)
	if !ok {
		return
	}

	if err := d.store.DeleteAttachment(r.Context(), userID, attachmentID); err != nil {
		if errors.Is(err, store.ErrNotFound) {
			writeError(w, http.StatusNotFound, "not_found", "attachment not found")
			return
		}
		d.logger.Error("delete attachment: store failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	// Best-effort: the DB row is the source of truth and is already gone;
	// a failure to remove the file is logged but must not turn a
	// successful delete into an error response for the caller.
	if err := removeAttachmentFile(d.attachmentsDir, att.StoragePath); err != nil && !os.IsNotExist(err) {
		d.logger.Error("delete attachment: remove file failed", "error", err.Error())
	}

	w.WriteHeader(http.StatusNoContent)
}

// getOwnedAttachment fetches attachmentID, ownership-checked against userID
// AND confirmed to belong to itemID -- the URL carries both, and both must
// agree (SPEC-BASE.md Section 47: "User A cannot access ... User B
// attachments", and equally an attachment must not be reachable through an
// item it isn't actually attached to). Writes the appropriate error
// response and returns ok=false on any failure.
func (d *vaultDeps) getOwnedAttachment(w http.ResponseWriter, ctx context.Context, userID, itemID, attachmentID int64) (store.Attachment, bool) {
	att, err := d.store.GetAttachment(ctx, userID, attachmentID)
	switch {
	case err == nil:
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "attachment not found")
		return store.Attachment{}, false
	default:
		d.logger.Error("get attachment: store failed", "error", err.Error())
		writeInternalError(w)
		return store.Attachment{}, false
	}

	if att.VaultItemID != itemID {
		// Deliberately the same 404 as "doesn't exist" -- must not reveal
		// that an attachment with this ID exists but belongs to a different
		// item (or a different user's item).
		writeError(w, http.StatusNotFound, "not_found", "attachment not found")
		return store.Attachment{}, false
	}

	return att, true
}

// deleteAttachmentsForItem removes every attachment (DB rows + on-disk
// files) belonging to itemID before the item itself is deleted. The
// attachments FK (ON DELETE CASCADE) would clean up the DB rows on its own,
// but never the files -- called from handleDeleteVaultItem in
// vault_handlers.go so a deleted vault item never leaves orphaned encrypted
// blobs behind. Best-effort on file removal (same reasoning as
// handleDeleteAttachment): a stray file is a disk-usage nit, not a
// correctness problem worth failing the whole delete over.
func (d *vaultDeps) deleteAttachmentsForItem(ctx context.Context, userID, itemID int64) {
	atts, err := d.store.ListAttachmentsForItem(ctx, userID, itemID)
	if err != nil {
		d.logger.Error("delete vault item: list attachments for cleanup failed", "error", err.Error())
		return
	}
	for _, a := range atts {
		if err := removeAttachmentFile(d.attachmentsDir, a.StoragePath); err != nil && !os.IsNotExist(err) {
			d.logger.Error("delete vault item: remove attachment file failed", "error", err.Error())
		}
	}
}

// --- request decoding helpers ---------------------------------------------

func decodeAttachmentJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxAttachmentBodyBytes)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "malformed request body")
		return false
	}
	return true
}

func decodeBase64Field(w http.ResponseWriter, raw string, maxBytes int, fieldName string) ([]byte, bool) {
	data, err := base64.StdEncoding.DecodeString(raw)
	if err != nil || len(data) == 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", fieldName+" must be non-empty base64")
		return nil, false
	}
	if len(data) > maxBytes {
		writeError(w, http.StatusBadRequest, "invalid_request", fieldName+" too large")
		return nil, false
	}
	return data, true
}

// pathAttachmentID extracts and validates the {attachmentId} path parameter,
// mirroring pathID (category_handlers.go) for {id}.
func pathAttachmentID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	raw := r.PathValue("attachmentId")
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || id <= 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", "attachmentId must be a positive integer")
		return 0, false
	}
	return id, true
}

// --- local filesystem storage (SPEC-BASE.md Section 7) --------------------

// writeAttachmentFile writes data under attachmentsDir/<userID>/<random>.bin
// and returns the path *relative* to attachmentsDir (what gets persisted as
// store.Attachment.StoragePath). The filename is always server-generated
// from a CSPRNG, never derived from the client-supplied (and still opaque
// ciphertext, since it's a filename) encrypted_filename field -- so there is
// no path-traversal surface from client input reaching the filesystem.
func writeAttachmentFile(attachmentsDir string, userID int64, data []byte) (string, error) {
	idBytes := make([]byte, 16)
	if _, err := rand.Read(idBytes); err != nil {
		return "", fmt.Errorf("generate attachment file id: %w", err)
	}

	userDir := strconv.FormatInt(userID, 10)
	relPath := filepath.Join(userDir, hex.EncodeToString(idBytes)+".bin")

	fullDir := filepath.Join(attachmentsDir, userDir)
	if err := os.MkdirAll(fullDir, 0o700); err != nil {
		return "", fmt.Errorf("create attachment directory: %w", err)
	}

	fullPath := filepath.Join(attachmentsDir, relPath)
	if err := os.WriteFile(fullPath, data, 0o600); err != nil {
		return "", fmt.Errorf("write attachment file: %w", err)
	}

	return relPath, nil
}

func readAttachmentFile(attachmentsDir, storagePath string) ([]byte, error) {
	return os.ReadFile(filepath.Join(attachmentsDir, storagePath)) //nolint:gosec // storagePath is always server-generated, see writeAttachmentFile
}

func removeAttachmentFile(attachmentsDir, storagePath string) error {
	return os.Remove(filepath.Join(attachmentsDir, storagePath))
}
