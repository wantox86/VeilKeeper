package id.quezacolt.veilkeeper.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * In-memory fake of [VaultApi] for host-JVM unit tests of [VaultRepository]
 * and the vault ViewModels, avoiding any real network call. Mirrors
 * [FakeAuthApi]'s pattern: simple in-memory maps keyed by ID, mimicking
 * enough of the backend's behavior (ownership isn't modeled here -- that's
 * covered by the backend's own Go tests) to exercise repository/ViewModel
 * logic realistically.
 */
class FakeVaultApi : VaultApi {
    private var nextId = 1L
    val categories = mutableMapOf<Long, CategoryDto>()
    val items = mutableMapOf<Long, VaultItemDto>()

    var forcedErrorCode: Int? = null // when set, every call fails with this code

    override suspend fun listCategories(bearerToken: String): Response<List<CategoryDto>> {
        forcedErrorCode?.let { return errorResponse(it) }
        return Response.success(categories.values.sortedBy { it.id })
    }

    override suspend fun createCategory(bearerToken: String, request: CreateCategoryRequest): Response<CategoryDto> {
        forcedErrorCode?.let { return errorResponse(it) }
        val id = nextId++
        val dto = CategoryDto(id, request.name, false, 0, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        categories[id] = dto
        return Response.success(dto)
    }

    override suspend fun renameCategory(bearerToken: String, id: Long, request: RenameCategoryRequest): Response<CategoryDto> {
        forcedErrorCode?.let { return errorResponse(it) }
        val existing = categories[id] ?: return errorResponse(404)
        val updated = existing.copy(name = request.name)
        categories[id] = updated
        return Response.success(updated)
    }

    override suspend fun deleteCategory(bearerToken: String, id: Long, reassignTo: Long?): Response<Unit> {
        forcedErrorCode?.let { return errorResponse(it) }
        if (!categories.containsKey(id)) return errorResponse(404)
        val dest = reassignTo ?: run {
            val uncategorized = categories.values.firstOrNull { it.isUncategorized }
            if (uncategorized != null) {
                uncategorized.id
            } else {
                val newId = nextId++
                categories[newId] = CategoryDto(newId, "Uncategorized", true, 0, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
                newId
            }
        }
        items.replaceAll { _, item -> if (item.categoryId == id) item.copy(categoryId = dest) else item }
        categories.remove(id)
        recomputeItemCounts()
        return Response.success(Unit)
    }

    override suspend fun listVaultItems(bearerToken: String, categoryId: Long?): Response<List<VaultItemDto>> {
        forcedErrorCode?.let { return errorResponse(it) }
        val filtered = items.values.filter { categoryId == null || it.categoryId == categoryId }
        return Response.success(filtered.sortedByDescending { it.updatedAt })
    }

    override suspend fun createVaultItem(bearerToken: String, request: CreateVaultItemRequest): Response<VaultItemDto> {
        forcedErrorCode?.let { return errorResponse(it) }
        if (!categories.containsKey(request.categoryId)) return errorResponse(404)
        val id = nextId++
        val dto = VaultItemDto(id, request.categoryId, request.encryptedPayload, "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z")
        items[id] = dto
        recomputeItemCounts()
        return Response.success(dto)
    }

    override suspend fun getVaultItem(bearerToken: String, id: Long): Response<VaultItemDto> {
        forcedErrorCode?.let { return errorResponse(it) }
        return items[id]?.let { Response.success(it) } ?: errorResponse(404)
    }

    override suspend fun updateVaultItem(bearerToken: String, id: Long, request: UpdateVaultItemRequest): Response<VaultItemDto> {
        forcedErrorCode?.let { return errorResponse(it) }
        val existing = items[id] ?: return errorResponse(404)
        val updated = existing.copy(
            categoryId = request.categoryId ?: existing.categoryId,
            encryptedPayload = request.encryptedPayload,
            updatedAt = "2026-01-02T00:00:00Z",
        )
        items[id] = updated
        return Response.success(updated)
    }

    override suspend fun deleteVaultItem(bearerToken: String, id: Long): Response<Unit> {
        forcedErrorCode?.let { return errorResponse(it) }
        if (!items.containsKey(id)) return errorResponse(404)
        items.remove(id)
        recomputeItemCounts()
        return Response.success(Unit)
    }

    private fun recomputeItemCounts() {
        categories.replaceAll { id, cat -> cat.copy(itemCount = items.values.count { it.categoryId == id }) }
    }

    private fun <T> errorResponse(code: Int): Response<T> {
        val body = """{"error":"error","message":"fake error $code"}"""
            .toResponseBody("application/json".toMediaType())
        return Response.error(code, body)
    }
}
