package id.quezacolt.veilkeeper.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

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
}
