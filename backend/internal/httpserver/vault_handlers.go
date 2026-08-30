package httpserver

import (
	"encoding/base64"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// --- DTOs --------------------------------------------------------------

// vaultItemResponse's EncryptedPayload is base64 of the opaque
// nonce||ciphertext blob the client produced (AesGcm.encrypt on Android) --
// the server never decodes or inspects it (SPEC-BASE.md Section 13/32).
type vaultItemResponse struct {
	ID               int64     `json:"id"`
	CategoryID       int64     `json:"category_id"`
	EncryptedPayload string    `json:"encrypted_payload"` // base64
	CreatedAt        time.Time `json:"created_at"`
	UpdatedAt        time.Time `json:"updated_at"`
}

type createVaultItemRequest struct {
	CategoryID       int64  `json:"category_id"`
	EncryptedPayload string `json:"encrypted_payload"` // base64
}

type updateVaultItemRequest struct {
	CategoryID       *int64 `json:"category_id"`       // nil = leave unchanged
	EncryptedPayload string `json:"encrypted_payload"` // base64
}

func toVaultItemResponse(v store.VaultItem) vaultItemResponse {
	return vaultItemResponse{
		ID:               v.ID,
		CategoryID:       v.CategoryID,
		EncryptedPayload: base64.StdEncoding.EncodeToString(v.EncryptedPayload),
		CreatedAt:        v.CreatedAt,
		UpdatedAt:        v.UpdatedAt,
	}
}

const maxEncryptedPayloadBytes = 512 * 1024 // 512 KiB decoded -- generous for text/secret/note content blocks (attachments are out of scope, Sprint 5)

// --- handlers ------------------------------------------------------------

// handleListVaultItems implements GET /api/v1/vault/items (optionally
// filtered via ?category_id=<id>).
func (d *vaultDeps) handleListVaultItems(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	var categoryID *int64
	if raw := strings.TrimSpace(r.URL.Query().Get("category_id")); raw != "" {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || id <= 0 {
			writeError(w, http.StatusBadRequest, "invalid_request", "category_id must be a positive integer")
			return
		}
		categoryID = &id
	}

	items, err := d.store.ListVaultItems(r.Context(), userID, categoryID)
	if err != nil {
		d.logger.Error("list vault items: store failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	out := make([]vaultItemResponse, 0, len(items))
	for _, it := range items {
		out = append(out, toVaultItemResponse(it))
	}
	writeJSON(w, http.StatusOK, out)
}

// handleCreateVaultItem implements POST /api/v1/vault/items.
func (d *vaultDeps) handleCreateVaultItem(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	var req createVaultItemRequest
	if !decodeVaultJSON(w, r, &req) {
		return
	}

	if req.CategoryID <= 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", "category_id is required")
		return
	}

	payload, ok := decodeEncryptedPayload(w, req.EncryptedPayload)
	if !ok {
		return
	}

	item, err := d.store.CreateVaultItem(r.Context(), userID, req.CategoryID, payload)
	switch {
	case err == nil:
		writeJSON(w, http.StatusCreated, toVaultItemResponse(item))
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "category not found")
	default:
		d.logger.Error("create vault item: store failed", "error", err.Error())
		writeInternalError(w)
	}
}

// handleGetVaultItem implements GET /api/v1/vault/items/{id}.
func (d *vaultDeps) handleGetVaultItem(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	itemID, ok := pathID(w, r)
	if !ok {
		return
	}

	item, err := d.store.GetVaultItem(r.Context(), userID, itemID)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, toVaultItemResponse(item))
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "vault item not found")
	default:
		d.logger.Error("get vault item: store failed", "error", err.Error())
		writeInternalError(w)
	}
}

// handleUpdateVaultItem implements PUT /api/v1/vault/items/{id}.
func (d *vaultDeps) handleUpdateVaultItem(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	itemID, ok := pathID(w, r)
	if !ok {
		return
	}

	var req updateVaultItemRequest
	if !decodeVaultJSON(w, r, &req) {
		return
	}

	if req.CategoryID != nil && *req.CategoryID <= 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", "category_id must be a positive integer")
		return
	}

	payload, ok := decodeEncryptedPayload(w, req.EncryptedPayload)
	if !ok {
		return
	}

	item, err := d.store.UpdateVaultItem(r.Context(), userID, itemID, req.CategoryID, payload)
	switch {
	case err == nil:
		writeJSON(w, http.StatusOK, toVaultItemResponse(item))
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "vault item or category not found")
	default:
		d.logger.Error("update vault item: store failed", "error", err.Error())
		writeInternalError(w)
	}
}

// handleDeleteVaultItem implements DELETE /api/v1/vault/items/{id}.
func (d *vaultDeps) handleDeleteVaultItem(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	itemID, ok := pathID(w, r)
	if !ok {
		return
	}

	// Sprint 5: the attachments FK is ON DELETE CASCADE (cleans up DB rows
	// automatically), but never the encrypted files on disk -- clean those
	// up first so deleting an item never leaves orphaned blobs behind. This
	// is best-effort (see deleteAttachmentsForItem's doc comment) and runs
	// even if the item turns out not to exist / not belong to the caller --
	// ListAttachmentsForItem in that case just returns an empty list, so
	// there is no meaningful behavior difference, only avoided extra
	// branching.
	d.deleteAttachmentsForItem(r.Context(), userID, itemID)

	err := d.store.DeleteVaultItem(r.Context(), userID, itemID)
	switch {
	case err == nil:
		w.WriteHeader(http.StatusNoContent)
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "vault item not found")
	default:
		d.logger.Error("delete vault item: store failed", "error", err.Error())
		writeInternalError(w)
	}
}

// decodeEncryptedPayload base64-decodes and size-validates an
// encrypted_payload field. Never logs or echoes the decoded bytes -- doing
// so could leak ciphertext (still not plaintext, but SPEC-BASE.md Section
// 30's "do not log decrypted vault payloads" spirit extends to being
// careful with vault data generally).
func decodeEncryptedPayload(w http.ResponseWriter, raw string) ([]byte, bool) {
	payload, err := base64.StdEncoding.DecodeString(raw)
	if err != nil || len(payload) == 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", "encrypted_payload must be non-empty base64")
		return nil, false
	}
	if len(payload) > maxEncryptedPayloadBytes {
		writeError(w, http.StatusBadRequest, "invalid_request", "encrypted_payload too large")
		return nil, false
	}
	return payload, true
}
