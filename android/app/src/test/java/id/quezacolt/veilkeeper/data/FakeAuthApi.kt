package id.quezacolt.veilkeeper.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * In-memory fake of [AuthApi] for host-JVM unit tests of [AuthRepository]
 * and the auth ViewModels, avoiding any real network call.
 */
class FakeAuthApi : AuthApi {
    var preloginResponse: PreloginResponse = PreloginResponse(
        kdfSalt = "AAAAAAAAAAAAAAAAAAAAAA==",
        kdfParams = KdfParamsDto(65536, 3, 4),
        kdfVersion = 1,
    )

    var registerResult: Response<RegisterResponse> = Response.success(RegisterResponse(1, "user@example.com"))
    var loginResult: Response<LoginResponse>? = null // set per-test; null triggers a hard failure if unset
    var logoutResult: Response<Unit> = Response.success(Unit)

    var lastRegisterRequest: RegisterRequest? = null
    var lastLoginRequest: LoginRequest? = null
    var lastLogoutBearer: String? = null

    override suspend fun prelogin(request: PreloginRequest): PreloginResponse = preloginResponse

    override suspend fun register(request: RegisterRequest): Response<RegisterResponse> {
        lastRegisterRequest = request
        return registerResult
    }

    override suspend fun login(request: LoginRequest): Response<LoginResponse> {
        lastLoginRequest = request
        return loginResult ?: error("FakeAuthApi.loginResult not configured for this test")
    }

    override suspend fun logout(bearerToken: String): Response<Unit> {
        lastLogoutBearer = bearerToken
        return logoutResult
    }

    companion object {
        fun <T> errorResponse(code: Int, error: String, message: String): Response<T> {
            val body = """{"error":"$error","message":"$message"}"""
                .toResponseBody("application/json".toMediaType())
            return Response.error(code, body)
        }
    }
}
