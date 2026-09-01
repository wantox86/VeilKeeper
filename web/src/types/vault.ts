/**
 * Wire DTOs for `GET/POST /api/v1/categories`, `PUT/DELETE
 * /api/v1/categories/{id}`, and `GET/POST /api/v1/vault/items`,
 * `GET/PUT/DELETE /api/v1/vault/items/{id}` -- field names match the
 * backend's `categoryResponse`/`vaultItemResponse` Go structs
 * (`backend/internal/httpserver/{category,vault}_handlers.go`) exactly.
 * These endpoints already existed before Web Sprint 3 (built for Android
 * Sprint 2) -- no backend changes were needed for the Web client to consume
 * them.
 */
export interface CategoryDto {
  id: number
  name: string
  is_uncategorized: boolean
  item_count: number
  created_at: string
  updated_at: string
}

/** `encrypted_payload` is base64 of the opaque `nonce || ciphertext+tag` blob -- the server never sees plaintext. */
export interface VaultItemDto {
  id: number
  category_id: number
  encrypted_payload: string
  created_at: string
  updated_at: string
}

/**
 * The plaintext logical structure of a vault item (SPEC-BASE.md Section 13),
 * mirroring Android's `VaultItemPayload`/`ContentBlockDto`
 * (`android/app/src/main/java/id/quezacolt/veilkeeper/crypto/VaultItemCrypto.kt`)
 * field-for-field, including using explicit `null` (not `undefined`) for an
 * absent `label` -- Android's `kotlinx.serialization` with `encodeDefaults =
 * true` always serializes the field, so this Web client must too for the
 * JSON shape to match byte-for-byte across clients decrypting each other's
 * items.
 *
 * Sprint 3 scope: `type` is `"text" | "secret" | "note"` only --
 * `"image"` (attachment-linked) is Android Sprint 5 / Web Sprint 6, not
 * implemented here.
 */
export type ContentBlockType = 'text' | 'secret' | 'note'

export interface ContentBlock {
  type: ContentBlockType
  label: string | null
  value: string
}

export interface VaultItemPayload {
  title: string
  content: ContentBlock[]
}
