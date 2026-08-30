package httpserver

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"testing"
)

// aesGCMEncryptForTest / aesGCMDecryptForTest are a minimal stand-in for the
// Android AesGcm object (nonce || ciphertext+tag wire format), used only to
// prove the backend round-trips arbitrary opaque ciphertext byte-for-byte.
// The real client-side crypto is exercised in the Android test suite
// (AesGcmTest, VaultCryptoTest).
func aesGCMEncryptForTest(t *testing.T, key, plaintext []byte) []byte {
	t.Helper()
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatalf("aes.NewCipher: %v", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatalf("cipher.NewGCM: %v", err)
	}
	nonce := make([]byte, gcm.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		t.Fatalf("rand.Read nonce: %v", err)
	}
	return append(nonce, gcm.Seal(nil, nonce, plaintext, nil)...)
}

func aesGCMDecryptForTest(t *testing.T, key, nonceAndCiphertext []byte) []byte {
	t.Helper()
	block, err := aes.NewCipher(key)
	if err != nil {
		t.Fatalf("aes.NewCipher: %v", err)
	}
	gcm, err := cipher.NewGCM(block)
	if err != nil {
		t.Fatalf("cipher.NewGCM: %v", err)
	}
	nonceSize := gcm.NonceSize()
	nonce, ciphertext := nonceAndCiphertext[:nonceSize], nonceAndCiphertext[nonceSize:]
	plaintext, err := gcm.Open(nil, nonce, ciphertext, nil)
	if err != nil {
		t.Fatalf("gcm.Open: %v", err)
	}
	return plaintext
}

func TestVaultItem_CreateListGetUpdateDelete(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(t, fs)
	token := loginAndGetToken(t, deps, "itemcrud@example.com")

	catsRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	var cats []categoryResponse
	_ = json.NewDecoder(catsRec.Body).Decode(&cats)
	categoryID := cats[0].ID

	createRec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID:       categoryID,
		EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(token))
	if createRec.Code != http.StatusCreated {
		t.Fatalf("create: expected 201, got %d: %s", createRec.Code, createRec.Body.String())
	}
	var created vaultItemResponse
	_ = json.NewDecoder(createRec.Body).Decode(&created)
	if created.EncryptedPayload != fakeCiphertextB64() {
		t.Fatal("expected stored payload to round-trip byte-for-byte (server never touches ciphertext contents)")
	}

	listRec := doJSON(t, authedHandler(fs, vDeps.handleListVaultItems), http.MethodGet, "/api/v1/vault/items", nil, authHeader(token))
	var list []vaultItemResponse
	_ = json.NewDecoder(listRec.Body).Decode(&list)
	if len(list) != 1 {
		t.Fatalf("expected 1 item in list, got %d", len(list))
	}

	getRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleGetVaultItem, created.ID)), http.MethodGet, "/api/v1/vault/items/x", nil, authHeader(token))
	if getRec.Code != http.StatusOK {
		t.Fatalf("get: expected 200, got %d", getRec.Code)
	}

	newPayload := base64.StdEncoding.EncodeToString([]byte("updated-fake-ciphertext"))
	updRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleUpdateVaultItem, created.ID)), http.MethodPut, "/api/v1/vault/items/x", updateVaultItemRequest{
		EncryptedPayload: newPayload,
	}, authHeader(token))
	if updRec.Code != http.StatusOK {
		t.Fatalf("update: expected 200, got %d: %s", updRec.Code, updRec.Body.String())
	}
	var updated vaultItemResponse
	_ = json.NewDecoder(updRec.Body).Decode(&updated)
	if updated.EncryptedPayload != newPayload {
		t.Fatal("expected updated payload to be persisted")
	}
	if updated.CategoryID != categoryID {
		t.Fatal("expected category to remain unchanged when category_id omitted from update")
	}

	delRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleDeleteVaultItem, created.ID)), http.MethodDelete, "/api/v1/vault/items/x", nil, authHeader(token))
	if delRec.Code != http.StatusNoContent {
		t.Fatalf("delete: expected 204, got %d", delRec.Code)
	}

	getAfterDeleteRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleGetVaultItem, created.ID)), http.MethodGet, "/api/v1/vault/items/x", nil, authHeader(token))
	if getAfterDeleteRec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 after delete, got %d", getAfterDeleteRec.Code)
	}
}

func TestVaultItem_CreateInNonexistentCategoryRejected(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(t, fs)
	token := loginAndGetToken(t, deps, "badcat@example.com")

	rec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID:       999999,
		EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(token))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("expected 404 for nonexistent category, got %d", rec.Code)
	}
}

func TestVaultItem_EmptyPayloadRejected(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(t, fs)
	token := loginAndGetToken(t, deps, "emptypayload@example.com")

	catsRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	var cats []categoryResponse
	_ = json.NewDecoder(catsRec.Body).Decode(&cats)

	rec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID:       cats[0].ID,
		EncryptedPayload: "",
	}, authHeader(token))
	if rec.Code != http.StatusBadRequest {
		t.Fatalf("expected 400 for empty encrypted_payload, got %d", rec.Code)
	}
}

func TestVaultItem_ListFilterByCategory(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(t, fs)
	token := loginAndGetToken(t, deps, "filterbycat@example.com")

	catsRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	var cats []categoryResponse
	_ = json.NewDecoder(catsRec.Body).Decode(&cats)
	if len(cats) < 2 {
		t.Fatal("expected at least 2 default categories")
	}

	doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID: cats[0].ID, EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(token))
	doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID: cats[1].ID, EncryptedPayload: fakeCiphertextB64(),
	}, authHeader(token))

	listRec := doJSON(t, authedHandler(fs, vDeps.handleListVaultItems), http.MethodGet, "/api/v1/vault/items?category_id="+itoaTest(cats[0].ID), nil, authHeader(token))
	var filtered []vaultItemResponse
	_ = json.NewDecoder(listRec.Body).Decode(&filtered)
	if len(filtered) != 1 {
		t.Fatalf("expected exactly 1 item filtered to category %d, got %d", cats[0].ID, len(filtered))
	}
	if filtered[0].CategoryID != cats[0].ID {
		t.Fatalf("expected filtered item's category_id to be %d, got %d", cats[0].ID, filtered[0].CategoryID)
	}
}

func itoaTest(id int64) string {
	b, _ := json.Marshal(id)
	return string(b)
}

// TestVaultItem_EndToEndEncryptionRoundTrip simulates the full acceptance
// flow from CLAUDE.md's Sprint 2 task: create category -> encrypt
// client-side -> upload -> retrieve -> decrypt -> same plaintext. The
// backend itself never decrypts anything (that's the point), so this test
// exercises a stand-in AES-256-GCM round trip identical in shape to what
// the real Android VaultCrypto/AesGcm code does, verifying the server is a
// transparent, byte-exact ciphertext store.
func TestVaultItem_EndToEndEncryptionRoundTrip(t *testing.T) {
	deps, fs := testDeps()
	vDeps := testVaultDeps(t, fs)
	token := loginAndGetToken(t, deps, "e2e@example.com")

	catsRec := doJSON(t, authedHandler(fs, vDeps.handleListCategories), http.MethodGet, "/api/v1/categories", nil, authHeader(token))
	var cats []categoryResponse
	_ = json.NewDecoder(catsRec.Body).Decode(&cats)

	plaintext := []byte(`{"title":"GitLab Production","content":[{"type":"secret","label":"Token","value":"glpat-xxxxx"}]}`)
	key := make([]byte, 32)
	for i := range key {
		key[i] = byte(i)
	}
	ciphertext := aesGCMEncryptForTest(t, key, plaintext)

	createRec := doJSON(t, authedHandler(fs, vDeps.handleCreateVaultItem), http.MethodPost, "/api/v1/vault/items", createVaultItemRequest{
		CategoryID:       cats[0].ID,
		EncryptedPayload: base64.StdEncoding.EncodeToString(ciphertext),
	}, authHeader(token))
	if createRec.Code != http.StatusCreated {
		t.Fatalf("create: expected 201, got %d: %s", createRec.Code, createRec.Body.String())
	}
	var created vaultItemResponse
	_ = json.NewDecoder(createRec.Body).Decode(&created)

	getRec := doJSON(t, authedHandler(fs, withPathID(vDeps.handleGetVaultItem, created.ID)), http.MethodGet, "/api/v1/vault/items/x", nil, authHeader(token))
	var fetched vaultItemResponse
	_ = json.NewDecoder(getRec.Body).Decode(&fetched)

	fetchedCiphertext, err := base64.StdEncoding.DecodeString(fetched.EncryptedPayload)
	if err != nil {
		t.Fatalf("decode fetched payload: %v", err)
	}
	decrypted := aesGCMDecryptForTest(t, key, fetchedCiphertext)
	if string(decrypted) != string(plaintext) {
		t.Fatalf("round-trip mismatch: got %q, want %q", decrypted, plaintext)
	}

	// Verify the underlying store literally holds ciphertext, not the
	// plaintext title/content -- mirrors the MySQL manual-query check done
	// during Docker verification (see CLAUDE.md Sprint 2 report).
	for _, it := range fs.items {
		if it.ID == created.ID {
			if string(it.EncryptedPayload) == string(plaintext) {
				t.Fatal("store must never contain plaintext vault content")
			}
		}
	}
}
