package id.quezacolt.veilkeeper.data

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
        val vdk = currentVdk() ?: return@withContext Result.failure(VaultError.NotUnlocked("vault is locked"))
        withBody({ api.listVaultItems(bearer(), categoryId) }) { body ->
            withContext(computeDispatcher) { body.mapNotNull { decryptOrNull(it, vdk) } }
        }
    }

    suspend fun getItem(id: Long): Result<DecryptedVaultItem> = withContext(ioDispatcher) {
        val vdk = currentVdk() ?: return@withContext Result.failure(VaultError.NotUnlocked("vault is locked"))
        withBody({ api.getVaultItem(bearer(), id) }) { dto ->
            withContext(computeDispatcher) { dto.toDomain(vdk) }
        }
    }

    suspend fun createItem(categoryId: Long, title: String, content: List<ContentBlockDto>): Result<DecryptedVaultItem> =
        withContext(ioDispatcher) {
            val vdk = currentVdk() ?: return@withContext Result.failure(VaultError.NotUnlocked("vault is locked"))
            val encrypted = withContext(computeDispatcher) {
                VaultItemCrypto.encrypt(vdk, VaultItemPayload(title, content))
            }
            withBody({ api.createVaultItem(bearer(), CreateVaultItemRequest(categoryId, encrypted.b64())) }) { dto ->
                withContext(computeDispatcher) { dto.toDomain(vdk) }
            }
        }

    suspend fun updateItem(id: Long, categoryId: Long?, title: String, content: List<ContentBlockDto>): Result<DecryptedVaultItem> =
        withContext(ioDispatcher) {
            val vdk = currentVdk() ?: return@withContext Result.failure(VaultError.NotUnlocked("vault is locked"))
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

    // --- helpers -----------------------------------------------------------

    private fun bearer(): String {
        val token = AuthSessionHolder.sessionToken ?: error("VaultRepository called with no active session")
        return "Bearer $token"
    }

    private fun currentVdk(): ByteArray? = AuthSessionHolder.vaultDataKey

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
