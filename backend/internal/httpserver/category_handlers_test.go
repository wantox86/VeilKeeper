package httpserver

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"strconv"
	"testing"
)

// testVaultDeps builds a *vaultDeps backed by the same fakeAuthStore as
// testDeps, so auth and vault handlers can be exercised together (e.g.
// register a real user, log in, then hit category/vault-item routes with
// the resulting session token via requireSession).
func testVaultDeps(fs *fakeAuthStore) *vaultDeps {
	return &vaultDeps{store: fs, logger: discardLogger()}
}

// authedHandler wraps h with requireSession backed by fs, mirroring how
// NewMux wires vault routes in server.go.
func authedHandler(fs *fakeAuthStore, h http.HandlerFunc) http.HandlerFunc {
	return requireSession(fs, discardLogger(), nil, h)
}

// loginAndGetToken registers (if not already) and logs in a test user,
// returning a bearer session token usable against authedHandler-wrapped
// routes.
func loginAndGetToken(t *testing.T, deps *authDeps, email string) string {
	t.Helper()
	registerTestUser(t, deps, email)

	rec := doJSON(t, deps.handleLogin, http.MethodPost, "/api/v1/auth/login", loginRequest{
		Email:            email,
		AuthKey:          rawAuthKeyB64(),
		DeviceIdentifier: "device-" + email,
	}, nil)
	if rec.Code != http.StatusOK {
		t.Fatalf("login(%s): expected 200, got %d: %s", email, rec.Code, rec.Body.String())
	}
	var resp loginResponse
	if err := json.NewDecoder(rec.Body).Decode(&resp); err != nil {
		t.Fatalf("decode login response: %v", err)
	}
	return resp.SessionToken
}

func authHeader(token string) map[string]string {
	return map[string]string{"Authorization": "Bearer " + token}
}

// --- default categories at registration -------------------------------------

func TestRegister_CreatesDefaultCategories(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(fs)
	token := loginAndGetToken(t, deps, "defaults@example.com")

	rec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", rec.Code, rec.Body.String())
	}

	var cats []categoryResponse
	if err := json.NewDecoder(rec.Body).Decode(&cats); err != nil {
		t.Fatalf("decode categories: %v", err)
	}
	if len(cats) != 5 {
		t.Fatalf("expected 5 default categories, got %d", len(cats))
	}
	wantNames := map[string]bool{"Common": true, "Work": true, "Tools": true, "Personal": true, "Other": true}
	for _, c := range cats {
		if !wantNames[c.Name] {
			t.Errorf("unexpected default category name %q", c.Name)
		}
		if c.ItemCount != 0 {
			t.Errorf("expected fresh category %q to have 0 items, got %d", c.Name, c.ItemCount)
		}
	}
}

// --- category CRUD -----------------------------------------------------------

func TestCategory_CreateRenameDelete(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(fs)
	token := loginAndGetToken(t, deps, "catcrud@example.com")

	createRec := doJSON(t, authedHandler(fs, vDeps.handleCreateCategory), http.MethodPost, "/api/v1/categories", createCategoryRequest{Name: "Servers"}, authHeader(token))
	if createRec.Code != http.StatusCreated {
		t.Fatalf("create category: expected 201, got %d: %s", createRec.Code, createRec.Body.String())
	}
	var created categoryResponse
	_ = json.NewDecoder(createRec.Body).Decode(&created)

	renameRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleRenameCategory, created.ID)), http.MethodPut, "/api/v1/categories/x", renameCategoryRequest{Name: "Prod Servers"}, authHeader(token))
	if renameRec.Code != http.StatusOK {
		t.Fatalf("rename category: expected 200, got %d: %s", renameRec.Code, renameRec.Body.String())
	}
	var renamed categoryResponse
	_ = json.NewDecoder(renameRec.Body).Decode(&renamed)
	if renamed.Name != "Prod Servers" {
		t.Fatalf("expected renamed category, got %q", renamed.Name)
	}

	deleteRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleDeleteCategory, created.ID)), http.MethodDelete, "/api/v1/categories/x", nil, authHeader(token))
	if deleteRec.Code != http.StatusNoContent {
		t.Fatalf("delete category: expected 204, got %d: %s", deleteRec.Code, deleteRec.Body.String())
	}

	getRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleRenameCategory, created.ID)), http.MethodPut, "/api/v1/categories/x", renameCategoryRequest{Name: "gone"}, authHeader(token))
	if getRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for deleted category, got %d", getRec.Code)
	}
}

// TestCategory_DeleteReassignsItemsToUncategorized covers CLAUDE.md's Sprint
// 2 "Delete category behavior" decision: deleting a category with items and
// no explicit reassign_to must move the items into the user's Uncategorized
// category, never delete them.
func TestCategory_DeleteReassignsItemsToUncategorized(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(fs)
	token := loginAndGetToken(t, deps, "reassign@example.com")

	catRec := doJSON(t, authedHandler(fs, vDeps.handleCreateCategory), http.MethodPost, "/api/v1/categories", createCategoryRequest{Name: "Temp"}, authHeader(token))
	var cat categoryResponse
	_ = json.NewDecoder(catRec.Body).Decode(&cat)

	itemRec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID:       cat.ID,
		EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(token))
	if itemRec.Code != http.StatusCreated {
		t.Fatalf("create item: expected 201, got %d: %s", itemRec.Code, itemRec.Body.String())
	}
	var item vaultItemResponse
	_ = json.NewDecoder(itemRec.Body).Decode(&item)

	delRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleDeleteCategory, cat.ID)), http.MethodDelete, "/api/v1/categories/x", nil, authHeader(token))
	if delRec.Code != http.StatusNoContent {
		t.Fatalf("delete category: expected 204, got %d: %s", delRec.Code, delRec.Body.String())
	}

	getItemRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleGetVaultItem, item.ID)), http.MethodGet, "/api/v1/vault/items/x", nil, authHeader(token))
	if getItemRec.Code != http.StatusOK {
		t.Fatalf("expected item to survive category deletion, got %d", getItemRec.Code)
	}
	var survived vaultItemResponse
	_ = json.NewDecoder(getItemRec.Body).Decode(&survived)

	listRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	var cats []categoryResponse
	_ = json.NewDecoder(listRec.Body).Decode(&cats)

	var uncategorized *categoryResponse
	for i := range cats {
		if cats[i].IsUncategorized {
			uncategorized = &cats[i]
		}
	}
	if uncategorized == nil {
		t.Fatal("expected an Uncategorized category to have been created")
	}
	if survived.CategoryID != uncategorized.ID {
		t.Fatalf("expected item to be reassigned to Uncategorized (id=%d), got category_id=%d", uncategorized.ID, survived.CategoryID)
	}
}

func TestCategory_UncategorizedCannotBeDeleted(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(fs)
	token := loginAndGetToken(t, deps, "protectuncategorized@example.com")

	// Force-create the Uncategorized category by deleting a throwaway one.
	catRec := doJSON(t, authedHandler(fs, vDeps.handleCreateCategory), http.MethodPost, "/api/v1/categories", createCategoryRequest{Name: "Throwaway"}, authHeader(token))
	var cat categoryResponse
	_ = json.NewDecoder(catRec.Body).Decode(&cat)
	doJSON(t, authedHandler(fs, withPathID(vDeps.handleDeleteCategory, cat.ID)), http.MethodDelete, "/api/v1/categories/x", nil, authHeader(token))

	listRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	var cats []categoryResponse
	_ = json.NewDecoder(listRec.Body).Decode(&cats)
	var uncategorizedID int64
	for _, c := range cats {
		if c.IsUncategorized {
			uncategorizedID = c.ID
		}
	}
	if uncategorizedID == 0 {
		t.Fatal("expected Uncategorized category to exist by now")
	}

	delRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleDeleteCategory, uncategorizedID)), http.MethodDelete, "/api/v1/categories/x", nil, authHeader(token))
	if delRec.Code != http.StatusConflict {
		t.Fatalf("expected 409 deleting Uncategorized category, got %d: %s", delRec.Code, delRec.Body.String())
	}
}

// --- ownership isolation (SPEC-BASE.md Section 47) --------------------------

func TestCategory_UserIsolation(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(fs)
	tokenA := loginAndGetToken(t, deps, "usera-cat@example.com")
	tokenB := loginAndGetToken(t, deps, "userb-cat@example.com")

	createRec := doJSON(t, authedHandler(fs, vDeps.handleCreateCategory), http.MethodPost, "/api/v1/categories", createCategoryRequest{Name: "A's Secrets"}, authHeader(tokenA))
	var catA categoryResponse
	_ = json.NewDecoder(createRec.Body).Decode(&catA)

	// B tries to rename A's category.
	renameRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleRenameCategory, catA.ID)), http.MethodPut, "/api/v1/categories/x", renameCategoryRequest{Name: "Hijacked"}, authHeader(tokenB))
	if renameRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 when user B renames user A's category, got %d", renameRec.Code)
	}

	// B tries to delete A's category.
	deleteRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleDeleteCategory, catA.ID)), http.MethodDelete, "/api/v1/categories/x", nil, authHeader(tokenB))
	if deleteRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 when user B deletes user A's category, got %d", deleteRec.Code)
	}

	// B's own category list must not include A's category.
	listRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(tokenB))
	var catsB []categoryResponse
	_ = json.NewDecoder(listRec.Body).Decode(&catsB)
	for _, c := range catsB {
		if c.ID == catA.ID {
			t.Fatal("user B's category list must not contain user A's category")
		}
	}
}

func TestVaultItem_UserIsolation(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(fs)
	tokenA := loginAndGetToken(t, deps, "usera-item@example.com")
	tokenB := loginAndGetToken(t, deps, "userb-item@example.com")

	catsRecA := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(tokenA))
	var catsA []categoryResponse
	_ = json.NewDecoder(catsRecA.Body).Decode(&catsA)
	if len(catsA) == 0 {
		t.Fatal("expected user A to have default categories")
	}

	itemRec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID:       catsA[0].ID,
		EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(tokenA))
	if itemRec.Code != http.StatusCreated {
		t.Fatalf("create item for A: expected 201, got %d: %s", itemRec.Code, itemRec.Body.String())
	}
	var itemA vaultItemResponse
	_ = json.NewDecoder(itemRec.Body).Decode(&itemA)

	// B tries to read A's item directly by ID.
	getRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleGetVaultItem, itemA.ID)), http.MethodGet, "/api/v1/vault/items/x", nil, authHeader(tokenB))
	if getRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 when user B reads user A's item, got %d", getRec.Code)
	}

	// B tries to update A's item.
	updRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUpdateVaultItem, itemA.ID)), http.MethodPut, "/api/v1/vault/items/x", updateVaultItemRequest{
		EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(tokenB))
	if updRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 when user B updates user A's item, got %d", updRec.Code)
	}

	// B tries to delete A's item.
	delRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleDeleteVaultItem, itemA.ID)), http.MethodDelete, "/api/v1/vault/items/x", nil, authHeader(tokenB))
	if delRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 when user B deletes user A's item, got %d", delRec.Code)
	}

	// B's own item list must not include A's item.
	listRec := doJSON(t, authedHandler(fs, vDeps.handleListVaultItems), http.MethodGet, "/api/v1/vault/items", nil, authHeader(tokenB))
	var itemsB []vaultItemResponse
	_ = json.NewDecoder(listRec.Body).Decode(&itemsB)
	for _, it := range itemsB {
		if it.ID == itemA.ID {
			t.Fatal("user B's item list must not contain user A's item")
		}
	}

	// A can still create an item in B's category ID? -- must be rejected too.
	crossCategoryRec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID:       catsA[0].ID, // reuse A's own category id but authenticate as B
		EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(tokenB))
	if crossCategoryRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 when user B creates an item in user A's category, got %d", crossCategoryRec.Code)
	}
}

// --- session auth on vault routes -------------------------------------------

func TestVaultRoutes_RequireValidSession(t *testing.T) {
	_, fs := testDeps()
	vDeps := testVaultDeps(fs)

	rec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, nil)
	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 without a session token, got %d", rec.Code)
	}

	recBad := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader("not-a-real-session-token"))
	if recBad.Code != http.StatusUnauthorized {
		t.Fatalf("expected 401 for an invalid session token, got %d", recBad.Code)
	}
}

// --- test helpers -------------------------------------------------------

// withPathID injects id as the {id} path value the way http.ServeMux would
// when matching a "/api/v1/.../{id}" pattern, so handlers under test can be
// invoked directly (bypassing NewMux's routing) while still exercising
// r.PathValue("id").
func withPathID(h http.HandlerFunc, id int64) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		r.SetPathValue("id", strconv.FormatInt(id, 10))
		h(w, r)
	}
}

func rawAuthKeyB64() string {
	return base64.StdEncoding.EncodeToString(rawAuthKey())
}

// fakeCiphertextB64 stands in for an AES-256-GCM nonce||ciphertext blob a
// real Android client would produce; these tests never touch real crypto
// (that's covered on the Android side), only that the server treats it as
// opaque bytes.
func fakeCiphertextB64() string {
	return base64.StdEncoding.EncodeToString([]byte("fake-nonce-and-ciphertext-not-real-crypto"))
}
