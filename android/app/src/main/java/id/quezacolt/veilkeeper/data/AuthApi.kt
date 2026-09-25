package id.quezacolt.veilkeeper.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/** Retrofit interface for the Sprint 1 authentication endpoints. */
interface AuthApi {
    @POST("api/v1/auth/prelogin")
    suspend fun prelogin(@Body request: PreloginRequest): PreloginResponse

    @POST("api/v1/auth/register")
    suspend fun register(@Body request: RegisterRequest): Response<RegisterResponse>

    @POST("api/v1/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/v1/auth/logout")
    suspend fun logout(@Header("Authorization") bearerToken: String): Response<Unit>

    // --- Phase 4: devices & sessions (backend httpserver/device_handlers.go) ---

    @GET("api/v1/devices")
    suspend fun listDevices(@Header("Authorization") bearerToken: String): Response<List<DeviceDto>>

    @DELETE("api/v1/devices/{id}")
    suspend fun revokeDevice(
        @Header("Authorization") bearerToken: String,
        @Path("id") id: Long,
    ): Response<Unit>
}
