package httpserver

import (
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/wantox86/veilkeeper/backend/internal/store"
)

// vaultDeps bundles what the Sprint 2 category/vault-item handlers (and, from
// Sprint 5, attachment_handlers.go) need.
type vaultDeps struct {
	store  store.Store
	logger *slog.Logger

	// attachmentsDir is the root directory encrypted attachment blobs are
	// written to/read from (SPEC-BASE.md Section 7). Unused by
	// category/vault-item handlers themselves, only by attachment_handlers.go.
	attachmentsDir string
}

const maxVaultBodyBytes = 1 << 20 // 1 MiB -- generous for an encrypted vault item payload

// --- DTOs --------------------------------------------------------------

type categoryResponse struct {
	ID              int64     `json:"id"`
	Name            string    `json:"name"`
	IsUncategorized bool      `json:"is_uncategorized"`
	ItemCount       int       `json:"item_count"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
}

type createCategoryRequest struct {
	Name string `json:"name"`
}

type renameCategoryRequest struct {
	Name string `json:"name"`
}

func toCategoryResponse(c store.Category) categoryResponse {
	return categoryResponse{
		ID:              c.ID,
		Name:            c.Name,
		IsUncategorized: c.IsUncategorized,
		ItemCount:       c.ItemCount,
		CreatedAt:       c.CreatedAt,
		UpdatedAt:       c.UpdatedAt,
	}
}

// --- handlers ------------------------------------------------------------

// handleListCategories implements GET /api/v1/categories.
func (d *vaultDeps) handleListCategories(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	cats, err := d.store.ListCategories(r.Context(), userID)
	if err != nil {
		d.logger.Error("list categories: store failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	out := make([]categoryResponse, 0, len(cats))
	for _, c := range cats {
		out = append(out, toCategoryResponse(c))
	}
	writeJSON(w, http.StatusOK, out)
}

// handleCreateCategory implements POST /api/v1/categories.
func (d *vaultDeps) handleCreateCategory(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	var req createCategoryRequest
	if !decodeVaultJSON(w, r, &req) {
		return
	}

	name := strings.TrimSpace(req.Name)
	if err := validateCategoryName(name); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", err.Error())
		return
	}

	id, err := d.store.CreateCategory(r.Context(), userID, name)
	if err != nil {
		d.logger.Error("create category: store failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	cat, err := d.store.GetCategory(r.Context(), userID, id)
	if err != nil {
		d.logger.Error("create category: read back failed", "error", err.Error())
		writeInternalError(w)
		return
	}

	writeJSON(w, http.StatusCreated, toCategoryResponse(cat))
}

// handleRenameCategory implements PUT /api/v1/categories/{id}.
func (d *vaultDeps) handleRenameCategory(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	categoryID, ok := pathID(w, r)
	if !ok {
		return
	}

	var req renameCategoryRequest
	if !decodeVaultJSON(w, r, &req) {
		return
	}

	name := strings.TrimSpace(req.Name)
	if err := validateCategoryName(name); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", err.Error())
		return
	}

	err := d.store.RenameCategory(r.Context(), userID, categoryID, name)
	switch {
	case err == nil:
		cat, err := d.store.GetCategory(r.Context(), userID, categoryID)
		if err != nil {
			d.logger.Error("rename category: read back failed", "error", err.Error())
			writeInternalError(w)
			return
		}
		writeJSON(w, http.StatusOK, toCategoryResponse(cat))
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "category not found")
	case errors.Is(err, store.ErrForbiddenSystemCategory):
		writeError(w, http.StatusConflict, "system_category", "the Uncategorized category cannot be renamed")
	default:
		d.logger.Error("rename category: store failed", "error", err.Error())
		writeInternalError(w)
	}
}

// handleDeleteCategory implements DELETE /api/v1/categories/{id}.
//
// Per CLAUDE.md's Sprint 2 "Delete category behavior" decision: deleting a
// category never silently deletes its vault items. An optional
// ?reassign_to=<category_id> query parameter lets the client choose a
// destination category explicitly; if omitted, items are moved into the
// user's "Uncategorized" category (lazily created on first use). The
// Uncategorized category itself cannot be deleted (409).
func (d *vaultDeps) handleDeleteCategory(w http.ResponseWriter, r *http.Request) {
	userID := userIDFromContext(r.Context())

	categoryID, ok := pathID(w, r)
	if !ok {
		return
	}

	var reassignTo *int64
	if raw := strings.TrimSpace(r.URL.Query().Get("reassign_to")); raw != "" {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || id <= 0 {
			writeError(w, http.StatusBadRequest, "invalid_request", "reassign_to must be a positive integer category id")
			return
		}
		reassignTo = &id
	}

	err := d.store.DeleteCategoryAndReassign(r.Context(), userID, categoryID, reassignTo)
	switch {
	case err == nil:
		w.WriteHeader(http.StatusNoContent)
	case errors.Is(err, store.ErrNotFound):
		writeError(w, http.StatusNotFound, "not_found", "category not found")
	case errors.Is(err, store.ErrForbiddenSystemCategory):
		writeError(w, http.StatusConflict, "system_category", "the Uncategorized category cannot be deleted")
	default:
		d.logger.Error("delete category: store failed", "error", err.Error())
		writeInternalError(w)
	}
}

// --- shared helpers --------------------------------------------------------

func validateCategoryName(name string) error {
	if name == "" {
		return errors.New("name is required")
	}
	if len(name) > 100 {
		return errors.New("name must be at most 100 characters")
	}
	return nil
}

// pathID extracts and validates the {id} path parameter as a positive
// int64, writing a 400 response and returning false on failure.
func pathID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	raw := r.PathValue("id")
	id, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || id <= 0 {
		writeError(w, http.StatusBadRequest, "invalid_request", "id must be a positive integer")
		return 0, false
	}
	return id, true
}

// decodeVaultJSON mirrors decodeJSON but with a larger body limit
// appropriate for vault item payloads (encrypted content can be larger than
// the small auth DTOs).
func decodeVaultJSON(w http.ResponseWriter, r *http.Request, dst any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxVaultBodyBytes)
	dec := json.NewDecoder(r.Body)
	dec.DisallowUnknownFields()
	if err := dec.Decode(dst); err != nil {
		writeError(w, http.StatusBadRequest, "invalid_request", "malformed request body")
		return false
	}
	return true
}
