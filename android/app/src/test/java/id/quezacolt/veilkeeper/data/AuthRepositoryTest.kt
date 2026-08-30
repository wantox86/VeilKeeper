package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.FakeMasterKeyDeriver
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class AuthRepositoryTest {

    private lateinit var api: FakeAuthApi
    private lateinit var repository: AuthRepository

    @Before
    fun setUp() {
        api = FakeAuthApi()
        repository = AuthRepository(api, VaultCrypto(FakeMasterKeyDeriver()))
        AuthSessionHolder.clear()
    }

    @Test
    fun `register sends base64 auth_key and wrapped_vdk, never the raw password`() = runTest {
        val result = repository.register(
            email = "new@example.com",
            password = "super-secret-password".toCharArray(),
            username = "tester",
        )

        assertTrue(result.isSuccess)
        val sent = api.lastRegisterRequest
        assertNotNull(sent)
        assertEquals("new@example.com", sent!!.email)
        // Must be valid base64 (throws if not).
        Base64.getDecoder().decode(sent.authKey)
        Base64.getDecoder().decode(sent.kdfSalt)
        Base64.getDecoder().decode(sent.wrappedVdk)
        assertTrue("auth_key must not contain the raw password", !sent.authKey.contains("super-secret-password"))
    }

    @Test
    fun `register maps 409 to EmailTaken`() = runTest {
        api.registerResult = FakeAuthApi.errorResponse(409, "email_taken", "an account with this email already exists")

        val result = repository.register("dupe@example.com", "password123".toCharArray(), null)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthRepository.AuthError.EmailTaken)
    }

    @Test
    fun `login derives keys from prelogin salt and unwraps the VDK into AuthSessionHolder`() = runTest {
        val vaultCrypto = VaultCrypto(FakeMasterKeyDeriver())
        val salt = vaultCrypto.generateKdfSalt()
        val password = "correct horse battery staple".toCharArray()

        val masterKey = vaultCrypto.deriveMasterKey(String(password).toByteArray(), salt, id.quezacolt.veilkeeper.crypto.KdfParams.DEFAULT)
        val wrapKey = vaultCrypto.deriveWrapKey(masterKey)
        val vdk = vaultCrypto.generateVaultDataKey()
        val wrappedVdk = vaultCrypto.wrapVaultDataKey(vdk, wrapKey)

        api.preloginResponse = PreloginResponse(
            kdfSalt = Base64.getEncoder().encodeToString(salt),
            kdfParams = KdfParamsDto(65536, 3, 4),
            kdfVersion = 1,
        )
        api.loginResult = retrofit2.Response.success(
            LoginResponse(
                sessionToken = "test-session-token",
                expiresAt = "2030-01-01T00:00:00Z",
                wrappedVdk = Base64.getEncoder().encodeToString(wrappedVdk),
                kdfSalt = Base64.getEncoder().encodeToString(salt),
                kdfParams = KdfParamsDto(65536, 3, 4),
                kdfVersion = 1,
            ),
        )

        val result = repository.login("user@example.com", password, "device-1", "Test Device")

        assertTrue(result.isSuccess)
        assertEquals("test-session-token", AuthSessionHolder.sessionToken)
        assertArrayEqualsCustom(vdk, AuthSessionHolder.vaultDataKey!!)
    }

    @Test
    fun `login maps 401 to InvalidCredentials`() = runTest {
        api.loginResult = FakeAuthApi.errorResponse(401, "invalid_credentials", "invalid email or auth key")

        val result = repository.login("user@example.com", "wrong".toCharArray(), "d1", "Test Device")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthRepository.AuthError.InvalidCredentials)
        assertNull("session must not be set on failed login", AuthSessionHolder.sessionToken)
    }

    @Test
    fun `login maps 429 to RateLimited`() = runTest {
        api.loginResult = FakeAuthApi.errorResponse(429, "too_many_attempts", "slow down")

        val result = repository.login("locked@example.com", "whatever".toCharArray(), "d1", "Test Device")

        assertTrue(result.exceptionOrNull() is AuthRepository.AuthError.RateLimited)
    }

    @Test
    fun `logout clears the session and sends a bearer token`() = runTest {
        AuthSessionHolder.set("some-token", ByteArray(32))

        val result = repository.logout()

        assertTrue(result.isSuccess)
        assertEquals("Bearer some-token", api.lastLogoutBearer)
        assertNull(AuthSessionHolder.sessionToken)
    }

    @Test
    fun `logout with no active session is a no-op success`() = runTest {
        AuthSessionHolder.clear()

        val result = repository.logout()

        assertTrue(result.isSuccess)
        assertNull(api.lastLogoutBearer)
    }

    private fun assertArrayEqualsCustom(expected: ByteArray, actual: ByteArray) {
        org.junit.Assert.assertArrayEquals(expected, actual)
    }
}
