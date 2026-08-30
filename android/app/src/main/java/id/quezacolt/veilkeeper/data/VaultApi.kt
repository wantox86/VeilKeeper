package id.quezacolt.veilkeeper.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit interface for the Sprint 2 vault foundation endpoints
 * (SPEC-BASE.md Section 29). Every call takes the bearer token explicitly
 * (matching [AuthApi.logout]'s existing pattern) rather than an OkHttp
 * interceptor -- this is a single-developer app with a handful of
 * authenticated endpoints, an interceptor would be an extra abstraction
 * layer this size doesn't need yet (SPEC-BASE.md Section 56, "no premature
 * overengineering").
 */
interface VaultApi {
    @GET("api/v1/categories")
    suspend fun listCategories(@Header("Authorization") bearerToken: String): Response<List<CategoryDto>>

    @POST("api/v1/categories")
    suspend fun createCategory(
        @Header("Authorization") bearerToken: String,
        @Body request: CreateCategoryRequest,
    ): Response<CategoryDto>

    @PUT("api/v1/categories/{id}")
    suspend fun renameCategory(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: Long,
        @Body request: RenameCategoryRequest,
    ): Response<CategoryDto>

    @DELETE("api/v1/categories/{id}")
    suspend fun deleteCategory(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: Long,
        @Query("reassign_to") reassignTo: Long? = null,
    ): Response<Unit>

    @GET("api/v1/vault/items")
    suspend fun listVaultItems(
        @Header("Authorization") bearerToken: String,
        @Query("category_id") categoryId: Long? = null,
    ): Response<List<VaultItemDto>>

    @POST("api/v1/vault/items")
    suspend fun createVaultItem(
        @Header("Authorization") bearerToken: String,
        @Body request: CreateVaultItemRequest,
    ): Response<VaultItemDto>

    @GET("api/v1/vault/items/{id}")
    suspend fun getVaultItem(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: Long,
    ): Response<VaultItemDto>

    @PUT("api/v1/vault/items/{id}")
    suspend fun updateVaultItem(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: Long,
        @Body request: UpdateVaultItemRequest,
    ): Response<VaultItemDto>

    @DELETE("api/v1/vault/items/{id}")
    suspend fun deleteVaultItem(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: Long,
    ): Response<Unit>
}
