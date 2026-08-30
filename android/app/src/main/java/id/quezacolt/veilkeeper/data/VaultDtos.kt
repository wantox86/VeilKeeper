package id.quezacolt.veilkeeper.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Sprint 2 vault foundation API
 * (backend/internal/httpserver/category_handlers.go, vault_handlers.go).
 * Field names/JSON keys are matched 1:1 against the Go structs there.
 */
@Serializable
data class CategoryDto(
    val id: Long,
    val name: String,
    @SerialName("is_uncategorized") val isUncategorized: Boolean,
    @SerialName("item_count") val itemCount: Int,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreateCategoryRequest(val name: String)

@Serializable
data class RenameCategoryRequest(val name: String)

/**
 * encryptedPayload is base64 of the opaque `nonce || ciphertext+tag` blob
 * produced by [id.quezacolt.veilkeeper.crypto.VaultItemCrypto] -- the server
 * never sees plaintext title/content (CLAUDE.md Sprint 2 scope,
 * SPEC-BASE.md Section 13/32).
 */
@Serializable
data class VaultItemDto(
    val id: Long,
    @SerialName("category_id") val categoryId: Long,
    @SerialName("encrypted_payload") val encryptedPayload: String, // base64
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class CreateVaultItemRequest(
    @SerialName("category_id") val categoryId: Long,
    @SerialName("encrypted_payload") val encryptedPayload: String, // base64
)

@Serializable
data class UpdateVaultItemRequest(
    @SerialName("category_id") val categoryId: Long? = null, // null = leave unchanged
    @SerialName("encrypted_payload") val encryptedPayload: String, // base64
)

/**
 * Wire DTOs for the Sprint 5 attachment API
 * (backend/internal/httpserver/attachment_handlers.go). Both
 * [encryptedFilename] and (where present) [encryptedData] are base64 of
 * opaque AES-256-GCM `nonce || ciphertext+tag` blobs produced by
 * [id.quezacolt.veilkeeper.crypto.AttachmentCrypto] -- the server never
 * decodes either.
 */
@Serializable
data class UploadAttachmentRequest(
    @SerialName("encrypted_filename") val encryptedFilename: String, // base64
    @SerialName("mime_type") val mimeType: String,
    @SerialName("encrypted_data") val encryptedData: String, // base64
)

/** Response after a successful upload -- does not echo the bytes back (see backend doc comment). */
@Serializable
data class AttachmentDto(
    val id: Long,
    @SerialName("vault_item_id") val vaultItemId: Long,
    @SerialName("encrypted_filename") val encryptedFilename: String, // base64
    @SerialName("mime_type") val mimeType: String,
    val size: Long,
    @SerialName("created_at") val createdAt: String,
)

/** Response from downloading an attachment -- includes the encrypted bytes. */
@Serializable
data class AttachmentDataDto(
    val id: Long,
    @SerialName("vault_item_id") val vaultItemId: Long,
    @SerialName("encrypted_filename") val encryptedFilename: String, // base64
    @SerialName("mime_type") val mimeType: String,
    val size: Long,
    @SerialName("encrypted_data") val encryptedData: String, // base64
    @SerialName("created_at") val createdAt: String,
)
