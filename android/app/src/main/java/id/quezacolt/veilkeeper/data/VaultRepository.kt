package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.AttachmentCrypto
import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.crypto.VaultItemCrypto
import id.quezacolt.veilkeeper.crypto.VaultItemPayload
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import retrofit2.Response
import java.util.Base64

/** Domain model for a category, decoupled from the wire DTO. */
data class Category(
    val id: Long,
    val name: String,
    val isUncategorized: Boolean,
    val itemCount: Int,
)

/**
 * A decrypted vault item. [preview] is a short, non-sensitive summary
 * derived from [content] for list screens (SPEC-BASE.md Section 19,
 * "Never show plaintext secrets in list previews") -- see [buildPreview].
 */
data class DecryptedVaultItem(
    val id: Long,
    val categoryId: Long,
    val title: String,
    val content: List<ContentBlockDto>,
    val updatedAt: String,
) {
    val preview: String get() = buildPreview(content)
}

/** Metadata about a just-uploaded attachment, enough to build an "image" [ContentBlockDto]. */
data class AttachmentRef(
    val id: Long,
    val mimeType: String,
    val size: Long,
)

/** A fully downloaded-and-decrypted attachment, ready for local preview. */
data class DecryptedAttachment(
    val id: Long,
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/**
 * Orchestrates the Sprint 2 vault foundation flows: client-side encryption
 * (via [VaultItemCrypto], using the VDK unwrapped at login and held in
 * [AuthSessionHolder]) + the [VaultApi] network calls.
 *
 * Every read/write of vault item content goes through here so there is
 * exactly one place that (a) knows the wire DTO <-> domain model mapping
 * and (b) touches the VDK -- screens/ViewModels never see ciphertext or
 * call [VaultItemCrypto] directly.
 */
class VaultRepository(
    private val api: VaultApi,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    sealed class VaultError : Exception() {
        data class Unauthorized(override val message: String) : VaultError()
        data class NotFound(override val message: String) : VaultError()
        data class Conflict(override val message: String) : VaultError()
        data class ServerError(override val message: String) : VaultError()
        data class NetworkError(override val message: String) : VaultError()
        data class NotUnlocked(override val message: String) : VaultError()
    }

    // --- categories ----------------------------------------------------

    suspend fun listCategories(): Result<List<Category>> = withContext(ioDispatcher) {
        withBody({ api.listCategories(bearer()) }) { body -> body.map { it.toDomain() } }
    }

    suspend fun createCategory(name: String): Result<Category> = withContext(ioDispatcher) {
        withBody({ api.createCategory(bearer(), CreateCategoryRequest(name)) }) { it.toDomain() }
    }

    suspend fun renameCategory(id: Long, name: String): Result<Category> = withContext(ioDispatcher) {
        withBody({ api.renameCategory(bearer(), id, RenameCategoryRequest(name)) }) { it.toDomain() }
    }

    /**
     * Deletes a category. Per CLAUDE.md's Sprint 2 "Delete category
     * behavior" decision, its items are never silently deleted: pass
     * [reassignTo] to move them to a specific category, or leave it null to
     * let the server move them into the user's Uncategorized category.
     */
    suspend fun deleteCategory(id: Long, reassignTo: Long? = null): Result<Unit> = withContext(ioDispatcher) {
        withNoBody { api.deleteCategory(bearer(), id, reassignTo) }
    }

    // --- vault items -----------------------------------------------------

    suspend fun listItems(categoryId: Long? = null): Result<List<DecryptedVaultItem>> = withContext(ioDispatcher) {
        val vdk = currentVdk() ?: return@withContext notUnlockedFailure()
        withBody({ api.listVaultItems(bearer(), categoryId) }) { body ->
            withContext(computeDispatcher) { body.mapNotNull { decryptOrNull(it, vdk) } }
        }
    }

    suspend fun getItem(id: Long): Result<DecryptedVaultItem> = withContext(ioDispatcher) {
        val vdk = currentVdk() ?: return@withContext notUnlockedFailure()
        withBody({ api.getVaultItem(bearer(), id) }) { dto ->
            withContext(computeDispatcher) { dto.toDomain(vdk) }
        }
    }

    suspend fun createItem(categoryId: Long, title: String, content: List<ContentBlockDto>): Result<DecryptedVaultItem> =
        withContext(ioDispatcher) {
            val vdk = currentVdk() ?: return@withContext notUnlockedFailure()
            val encrypted = withContext(computeDispatcher) {
                VaultItemCrypto.encrypt(vdk, VaultItemPayload(title, content))
            }
            withBody({ api.createVaultItem(bearer(), CreateVaultItemRequest(categoryId, encrypted.b64())) }) { dto ->
                withContext(computeDispatcher) { dto.toDomain(vdk) }
            }
        }

    suspend fun updateItem(id: Long, categoryId: Long?, title: String, content: List<ContentBlockDto>): Result<DecryptedVaultItem> =
        withContext(ioDispatcher) {
            val vdk = currentVdk() ?: return@withContext notUnlockedFailure()
            val encrypted = withContext(computeDispatcher) {
                VaultItemCrypto.encrypt(vdk, VaultItemPayload(title, content))
            }
            withBody({ api.updateVaultItem(bearer(), id, UpdateVaultItemRequest(categoryId, encrypted.b64())) }) { dto ->
                withContext(computeDispatcher) { dto.toDomain(vdk) }
            }
        }

    suspend fun deleteItem(id: Long): Result<Unit> = withContext(ioDispatcher) {
        withNoBody { api.deleteVaultItem(bearer(), id) }
    }

    // --- attachments (Sprint 5) --------------------------------------------

    /**
     * Encrypts [filename] and [fileBytes] with the VDK and uploads them as
     * an attachment of vault item [itemId] (which must already exist
     * server-side -- the endpoint is scoped under
     * `/vault/items/{id}/attachments`, see CLAUDE.md's Sprint 5 report for
     * why Add Item's flow creates the item first for image blocks).
     * [fileBytes] should already be compressed by the caller (SPEC-BASE.md
     * Section 17's "Compress if appropriate" step,
     * see [id.quezacolt.veilkeeper.data.ImageCompressor]) -- this method
     * only handles encryption + upload.
     */
    suspend fun uploadAttachment(itemId: Long, filename: String, mimeType: String, fileBytes: ByteArray): Result<AttachmentRef> =
        withContext(ioDispatcher) {
            val vdk = currentVdk() ?: return@withContext notUnlockedFailure()
            val (encryptedFilename, encryptedData) = withContext(computeDispatcher) {
                AttachmentCrypto.encryptFilename(vdk, filename) to AttachmentCrypto.encryptFile(vdk, fileBytes)
            }
            withBody({
                api.uploadAttachment(
                    bearer(),
                    itemId,
                    UploadAttachmentRequest(encryptedFilename.b64(), mimeType, encryptedData.b64()),
                )
            }) { dto -> AttachmentRef(dto.id, dto.mimeType, dto.size) }
        }

    /** Downloads attachment [attachmentId] of item [itemId] and decrypts filename + bytes with the VDK. */
    suspend fun downloadAttachment(itemId: Long, attachmentId: Long): Result<DecryptedAttachment> =
        withContext(ioDispatcher) {
            val vdk = currentVdk() ?: return@withContext notUnlockedFailure()
            withBody({ api.getAttachment(bearer(), itemId, attachmentId) }) { dto ->
                withContext(computeDispatcher) {
                    val filename = AttachmentCrypto.decryptFilename(vdk, dto.encryptedFilename.fromB64())
                    val bytes = AttachmentCrypto.decryptFile(vdk, dto.encryptedData.fromB64())
                    DecryptedAttachment(dto.id, filename, dto.mimeType, bytes)
                }
            }
        }

    /** Deletes attachment [attachmentId] of item [itemId], both server-side row and its encrypted file. */
    suspend fun deleteAttachment(itemId: Long, attachmentId: Long): Result<Unit> = withContext(ioDispatcher) {
        withNoBody { api.deleteAttachment(bearer(), itemId, attachmentId) }
    }

    // --- helpers -----------------------------------------------------------

    private fun bearer(): String {
        val token = AuthSessionHolder.sessionToken ?: error("VaultRepository called with no active session")
        return "Bearer $token"
    }

    private fun currentVdk(): ByteArray? = AuthSessionHolder.vaultDataKey

    /**
     * Post-launch fixes batch 3 -- root cause of the "vault is locked /
     * Retry -> infinite loop" bug: every method above already returned
     * [VaultError.NotUnlocked] when [currentVdk] was null (auto-lock fired
     * while a Home/Category/Vault-Detail screen was still open), but nothing
     * at *this* layer ever told [AuthSessionHolder] about it -- callers
     * (`HomeViewModel`/`CategoryViewModel`/etc.) just rendered the failure
     * as a generic [VeilKeeperErrorState]-with-Retry, and Retry re-ran the
     * exact same call, which failed the exact same way forever. The only
     * thing that was ever supposed to flip [AuthSessionHolder.lockState] to
     * `LOCKED` (which is what drives `MainActivity`'s global redirect to the
     * Unlock screen) was [AutoLockManager] -- a *separate* observer, on a
     * *different* timing, from a *different* trigger. Discovering
     * "the VDK is gone" here, at the single choke point every vault
     * operation already funnels through, is a strictly stronger signal than
     * relying on a background/foreground lifecycle callback to have already
     * done it: it makes the transition to `LOCKED` synchronous with, and
     * caused directly by, the exact failure the UI is about to react to,
     * instead of two independently-timed paths that a real device's
     * lifecycle/Compose recomposition scheduling could (and evidently did)
     * momentarily desync. [AuthSessionHolder.lock] is idempotent and a
     * no-op when already `LOCKED`/`LOGGED_OUT` (see its own doc comment), so
     * calling it here is always safe and never fights a fresher state.
     * Pairs with the `errorMessage`/`NotUnlocked` special-casing added to
     * every `VaultRepository`-consuming ViewModel in the same batch (see
     * [isVaultLocked]) -- that's what actually stops a Retry button from
     * ever being shown for this specific error, this call is what guarantees
     * the screen behind it actually gets replaced by Unlock instead of
     * staying on whatever (now error-free) state was last rendered.
     */
    private fun notUnlockedFailure(): Result<Nothing> {
        AuthSessionHolder.lock()
        return Result.failure(VaultError.NotUnlocked("vault is locked"))
    }

    /**
     * Decrypts a single item for list screens, tolerating a decrypt failure
     * for one item (e.g. corrupted row) by skipping it rather than failing
     * the whole list -- a single bad item must not make the entire vault
     * unreadable.
     */
    private fun decryptOrNull(dto: VaultItemDto, vdk: ByteArray): DecryptedVaultItem? =
        runCatching { dto.toDomain(vdk) }.getOrNull()

    private fun CategoryDto.toDomain() = Category(id, name, isUncategorized, itemCount)

    private fun VaultItemDto.toDomain(vdk: ByteArray): DecryptedVaultItem {
        val payload = VaultItemCrypto.decrypt(vdk, encryptedPayload.fromB64())
        return DecryptedVaultItem(id, categoryId, payload.title, payload.content, updatedAt)
    }

    /**
     * Runs [apiCall], mapping Retrofit's HTTP-error/network-error split into
     * [VaultError], and [transform]s a successful, non-null body. Mirrors
     * AuthRepository's error-mapping pattern. For endpoints with no response
     * body (deletes), use [withNoBody] instead.
     */
    private suspend fun <T, R> withBody(apiCall: suspend () -> Response<T>, transform: suspend (T) -> R): Result<R> =
        try {
            val response = apiCall()
            if (response.isSuccessful) {
                val body = response.body()
                if (body == null) {
                    Result.failure(VaultError.ServerError("empty response body"))
                } else {
                    Result.success(transform(body))
                }
            } else {
                Result.failure(mapErrorResponse(response.code()))
            }
        } catch (e: HttpException) {
            Result.failure(VaultError.NetworkError(e.message ?: "network error"))
        } catch (e: java.io.IOException) {
            Result.failure(VaultError.NetworkError(e.message ?: "network error"))
        }

    /** Like [withBody], but for endpoints (deletes) whose response body is empty/irrelevant. */
    private suspend fun <T> withNoBody(apiCall: suspend () -> Response<T>): Result<Unit> =
        try {
            val response = apiCall()
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(mapErrorResponse(response.code()))
            }
        } catch (e: HttpException) {
            Result.failure(VaultError.NetworkError(e.message ?: "network error"))
        } catch (e: java.io.IOException) {
            Result.failure(VaultError.NetworkError(e.message ?: "network error"))
        }

    private fun mapErrorResponse(code: Int): VaultError = when (code) {
        401 -> VaultError.Unauthorized("session expired, please log in again")
        404 -> VaultError.NotFound("not found")
        409 -> VaultError.Conflict("conflict")
        else -> VaultError.ServerError("request failed ($code)")
    }
}

/**
 * Post-launch fixes batch 3: true for [VaultRepository.VaultError.NotUnlocked]
 * specifically -- the vault got locked (auto-lock/screen-off/idle-timeout)
 * partway through an active session, not a "this operation genuinely
 * failed and retrying it might work" error like a network timeout or a
 * 500. Every `VaultRepository`-consuming ViewModel checks this before
 * setting an `errorMessage`: this case must **never** render via
 * [id.quezacolt.veilkeeper.ui.components.VeilKeeperErrorState]'s Retry
 * button, because tapping Retry would just re-run the same call against a
 * still-missing VDK and fail the exact same way again (this was the root
 * cause of the "vault is locked -> Retry -> loading -> vault is locked"
 * loop bug). [VaultRepository.notUnlockedFailure] already forces
 * [AuthSessionHolder.lockState] to `LOCKED` the moment this happens, which
 * is what actually drives `MainActivity`'s existing global redirect to the
 * Unlock screen -- ViewModels just need to stay out of its way by not
 * painting a competing "error" over the screen it's about to navigate off
 * of.
 */
fun Throwable.isVaultLocked(): Boolean = this is VaultRepository.VaultError.NotUnlocked

/**
 * Builds a short, non-sensitive preview string for list screens: prefers
 * the first non-secret block's value/label, falls back to a generic
 * placeholder if the item only has secret blocks -- actual secret values
 * are never included in a preview (SPEC-BASE.md Section 19).
 */
private fun buildPreview(content: List<ContentBlockDto>): String {
    val firstSafeBlock = content.firstOrNull { it.type != "secret" }
    return when {
        firstSafeBlock != null -> firstSafeBlock.value.take(60)
        content.isNotEmpty() -> "${content.size} item(s) • contains secrets"
        else -> ""
    }
}

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)
