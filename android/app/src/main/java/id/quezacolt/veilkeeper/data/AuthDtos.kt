package id.quezacolt.veilkeeper.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs for the Sprint 1 auth API (backend/internal/httpserver/auth_handlers.go).
 * Field names/JSON keys are matched 1:1 against the Go structs there.
 */
@Serializable
data class KdfParamsDto(
    val memory: Int,
    val iterations: Int,
    val parallelism: Int,
)

@Serializable
data class PreloginRequest(val email: String)

@Serializable
data class PreloginResponse(
    @SerialName("kdf_salt") val kdfSalt: String, // base64
    @SerialName("kdf_params") val kdfParams: KdfParamsDto,
    @SerialName("kdf_version") val kdfVersion: Int,
)

@Serializable
data class RegisterRequest(
    val email: String,
    val username: String? = null,
    @SerialName("auth_key") val authKey: String, // base64
    @SerialName("kdf_salt") val kdfSalt: String, // base64, client-generated -- see AuthRepository
    @SerialName("kdf_params") val kdfParams: KdfParamsDto,
    @SerialName("kdf_version") val kdfVersion: Int,
    @SerialName("wrapped_vdk") val wrappedVdk: String, // base64
    @SerialName("invite_code") val inviteCode: String, // required -- see backend's invite-code gate
)

@Serializable
data class RegisterResponse(
    @SerialName("user_id") val userId: Long,
    val email: String,
)

@Serializable
data class LoginRequest(
    val email: String,
    @SerialName("auth_key") val authKey: String, // base64
    @SerialName("device_identifier") val deviceIdentifier: String,
    @SerialName("device_name") val deviceName: String,
)

@Serializable
data class LoginResponse(
    @SerialName("session_token") val sessionToken: String,
    @SerialName("expires_at") val expiresAt: String, // RFC3339, parsed by caller if needed
    @SerialName("wrapped_vdk") val wrappedVdk: String, // base64
    @SerialName("kdf_salt") val kdfSalt: String, // base64
    @SerialName("kdf_params") val kdfParams: KdfParamsDto,
    @SerialName("kdf_version") val kdfVersion: Int,
)

@Serializable
data class ApiErrorResponse(
    val error: String,
    val message: String,
)
