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
 * Web Sprint 6 adds `"image"` -- same attachment-linking decision Android
 * Sprint 5 / the backend's `attachment_handlers.go` package doc already
 * made and this repo treats as a cross-client contract: an "image" block's
 * existing generic `value` field holds the attachment's server-assigned
 * numeric ID as a decimal string (e.g. `"42"`), never the image bytes. No
 * new field added -- `value` already fits, matching CLAUDE.md's existing
 * decision here field-for-field.
 */
export type ContentBlockType = 'text' | 'secret' | 'note' | 'image'

export interface ContentBlock {
  type: ContentBlockType
  label: string | null
  value: string
}

export interface VaultItemPayload {
  title: string
  content: ContentBlock[]
}

/**
 * Wire DTOs for `POST/GET/DELETE
 * /api/v1/vault/items/{id}/attachments[/{attachmentId}]` -- field names
 * match the backend's `attachmentResponse`/`attachmentDataResponse` Go
 * structs (`backend/internal/httpserver/attachment_handlers.go`) exactly.
 * `encrypted_filename` and `encrypted_data` are base64 of opaque
 * AES-256-GCM ciphertext (`nonce || ciphertext+tag`) -- the server never
 * decodes either, only moves bytes; this client is the only place either
 * gets decrypted.
 */
export interface AttachmentDto {
  id: number
  vault_item_id: number
  encrypted_filename: string
  mime_type: string
  size: number
  created_at: string
}

/** Returned only by GET (download) -- includes the encrypted bytes. */
export interface AttachmentDataDto extends AttachmentDto {
  encrypted_data: string
}
