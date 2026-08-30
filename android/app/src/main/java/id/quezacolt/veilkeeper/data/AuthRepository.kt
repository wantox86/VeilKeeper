package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.KdfParams
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Base64
import retrofit2.HttpException

/**
 * Orchestrates the Sprint 1 register/login/logout flows: client-side crypto
 * (via [VaultCrypto]) + the [AuthApi] network calls, per CLAUDE.md Resolved
 * Design Decision #1.
 *
 * All suspend functions here run their Argon2id-touching work on
 * [computeDispatcher] (CPU-bound, and Argon2id at the configured parameters
 * is intentionally slow/memory-heavy) -- never on the caller's dispatcher,
 * so ViewModels must not assume this returns quickly on [Dispatchers.Main].
 * [computeDispatcher]/[ioDispatcher] default to real dispatchers in
 * production and are overridden with a single test dispatcher in unit tests
 * (see AuthRepositoryTest / LoginViewModelTest) so `runTest` can correctly
 * track and await this work instead of racing a real background thread
 * pool.
 */
class AuthRepository(
    private val api: AuthApi,
    private val vaultCrypto: VaultCrypto,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    sealed class AuthError : Exception() {
        data class InvalidCredentials(override val message: String) : AuthError()
        data class EmailTaken(override val message: String) : AuthError()
        data class RateLimited(override val message: String) : AuthError()
        data class ServerError(override val message: String) : AuthError()
        data class NetworkError(override val message: String) : AuthError()
    }

    /**
     * Registers a new account.
     *
     * Implementation note: per CLAUDE.md the server "generates kdf_salt ...
     * at account creation," but a single-round-trip register call needs the
     * salt *before* AuthKey can be derived client-side. Since kdf_salt is
     * not secret, this client generates it locally (CSPRNG,
     * [VaultCrypto.generateKdfSalt]) and sends it to the server for storage
     * -- see backend/internal/httpserver/auth_handlers.go's handleRegister
     * doc comment for the matching server-side rationale.
     */
    suspend fun register(email: String, password: CharArray, username: String?): Result<Unit> =
        withContext(computeDispatcher) {
            val passwordBytes = password.toUtf8Bytes()
            val kdfSalt = vaultCrypto.generateKdfSalt()
            val params = KdfParams.DEFAULT

            var masterKey: ByteArray? = null
            var authKey: ByteArray? = null
            var wrapKey: ByteArray? = null
            var vdk: ByteArray? = null
            try {
                masterKey = vaultCrypto.deriveMasterKey(passwordBytes, kdfSalt, params)
                authKey = vaultCrypto.deriveAuthKey(masterKey)
                wrapKey = vaultCrypto.deriveWrapKey(masterKey)
                vdk = vaultCrypto.generateVaultDataKey()
                val wrappedVdk = vaultCrypto.wrapVaultDataKey(vdk, wrapKey)

                val response = api.register(
                    RegisterRequest(
                        email = email,
                        username = username,
                        authKey = authKey.b64(),
                        kdfSalt = kdfSalt.b64(),
                        kdfParams = KdfParamsDto(params.memoryKiB, params.iterations, params.parallelism),
                        kdfVersion = KdfParams.CURRENT_VERSION,
                        wrappedVdk = wrappedVdk.b64(),
                    ),
                )

                if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(mapErrorResponse(response.code(), response.errorBody()?.string()))
                }
            } catch (e: HttpException) {
                Result.failure(AuthError.NetworkError(e.message ?: "network error"))
            } catch (e: java.io.IOException) {
                Result.failure(AuthError.NetworkError(e.message ?: "network error"))
            } finally {
                VaultCrypto.wipe(passwordBytes, masterKey, authKey, wrapKey, vdk)
            }
        }

    /**
     * Logs in: fetches KDF parameters via /prelogin (indistinguishable
     * response shape for real vs nonexistent accounts, see CLAUDE.md), then
     * derives keys locally and authenticates.
     */
    suspend fun login(email: String, password: CharArray, deviceIdentifier: String, deviceName: String): Result<Unit> =
        withContext(computeDispatcher) {
            val passwordBytes = password.toUtf8Bytes()

            var masterKey: ByteArray? = null
            var authKey: ByteArray? = null
            var wrapKey: ByteArray? = null
            var vdk: ByteArray? = null
            try {
                val prelogin = api.prelogin(PreloginRequest(email))
                val kdfSalt = prelogin.kdfSalt.fromB64()
                val params = KdfParams(
                    memoryKiB = prelogin.kdfParams.memory,
                    iterations = prelogin.kdfParams.iterations,
                    parallelism = prelogin.kdfParams.parallelism,
                )

                masterKey = vaultCrypto.deriveMasterKey(passwordBytes, kdfSalt, params)
                authKey = vaultCrypto.deriveAuthKey(masterKey)
                wrapKey = vaultCrypto.deriveWrapKey(masterKey)

                val response = api.login(
                    LoginRequest(
                        email = email,
                        authKey = authKey.b64(),
                        deviceIdentifier = deviceIdentifier,
                        deviceName = deviceName,
                    ),
                )

                if (!response.isSuccessful) {
                    return@withContext Result.failure(mapErrorResponse(response.code(), response.errorBody()?.string()))
                }

                val body = response.body() ?: return@withContext Result.failure(AuthError.ServerError("empty login response"))
                vdk = vaultCrypto.unwrapVaultDataKey(body.wrappedVdk.fromB64(), wrapKey)
                AuthSessionHolder.set(sessionToken = body.sessionToken, vaultDataKey = vdk)
                vdk = null // ownership transferred to AuthSessionHolder; don't wipe it below

                Result.success(Unit)
            } catch (e: HttpException) {
                Result.failure(AuthError.NetworkError(e.message ?: "network error"))
            } catch (e: java.io.IOException) {
                Result.failure(AuthError.NetworkError(e.message ?: "network error"))
            } finally {
                VaultCrypto.wipe(passwordBytes, masterKey, authKey, wrapKey, vdk)
            }
        }

    /** Revokes the current session. Idempotent (matches backend logout semantics). */
    suspend fun logout(): Result<Unit> = withContext(ioDispatcher) {
        val token = AuthSessionHolder.sessionToken
        if (token == null) {
            AuthSessionHolder.clear()
            return@withContext Result.success(Unit)
        }
        try {
            api.logout("Bearer $token")
            Result.success(Unit)
        } catch (e: HttpException) {
            Result.failure(AuthError.NetworkError(e.message ?: "network error"))
        } catch (e: java.io.IOException) {
            Result.failure(AuthError.NetworkError(e.message ?: "network error"))
        } finally {
            AuthSessionHolder.clear()
        }
    }

    private fun mapErrorResponse(code: Int, body: String?): AuthError {
        val message = body?.let { runCatching { errorJson.decodeFromString(ApiErrorResponse.serializer(), it).message }.getOrNull() }
            ?: "request failed"
        return when (code) {
            401 -> AuthError.InvalidCredentials("invalid email or password")
            409 -> AuthError.EmailTaken("an account with this email already exists")
            429 -> AuthError.RateLimited("too many attempts, try again later")
            else -> AuthError.ServerError(message)
        }
    }

    companion object {
        private val errorJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}

private fun CharArray.toUtf8Bytes(): ByteArray {
    val charBuffer = java.nio.CharBuffer.wrap(this)
    val byteBuffer = Charsets.UTF_8.encode(charBuffer)
    val bytes = ByteArray(byteBuffer.remaining())
    byteBuffer.get(bytes)
    return bytes
}

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)
