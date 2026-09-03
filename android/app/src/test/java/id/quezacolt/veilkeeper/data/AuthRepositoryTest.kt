package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.FakeMasterKeyDeriver
import id.quezacolt.veilkeeper.crypto.FakeSessionCipherProvider
import id.quezacolt.veilkeeper.crypto.KdfParams
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
            inviteCode = "test-invite-code",
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

        val result = repository.register("dupe@example.com", "password123".toCharArray(), null, "test-invite-code")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthRepository.AuthError.EmailTaken)
    }

    @Test
    fun `register maps 403 to InviteCodeRejected with the server's message`() = runTest {
        api.registerResult = FakeAuthApi.errorResponse(403, "invalid_invite_code", "invalid invite code")

        val result = repository.register("wronginvite@example.com", "password123".toCharArray(), null, "not-a-real-code")

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is AuthRepository.AuthError.InviteCodeRejected)
        assertEquals("invalid invite code", error!!.message)
    }

    @Test
    fun `register sends the invite_code field to the server`() = runTest {
        val result = repository.register("invitecheck@example.com", "password123".toCharArray(), null, "family2026")

        assertTrue(result.isSuccess)
        assertEquals("family2026", api.lastRegisterRequest?.inviteCode)
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

    // --- Post-launch fixes batch 2, item #1: login persists (encrypted)
    // session material for process-death recovery; logout wipes it --------

    @Test
    fun `login also persists the session to the sessionStore for process-death recovery`() = runTest {
        val sessionStore = PersistedSessionStore(InMemorySettingsStorage(), FakeSessionCipherProvider())
        val repositoryWithStore = AuthRepository(api, VaultCrypto(FakeMasterKeyDeriver()), sessionStore = sessionStore)

        val vaultCrypto = VaultCrypto(FakeMasterKeyDeriver())
        val salt = vaultCrypto.generateKdfSalt()
        val password = "correct horse battery staple".toCharArray()
        val masterKey = vaultCrypto.deriveMasterKey(String(password).toByteArray(), salt, KdfParams.DEFAULT)
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
                sessionToken = "persisted-token",
                expiresAt = "2030-01-01T00:00:00Z",
                wrappedVdk = Base64.getEncoder().encodeToString(wrappedVdk),
                kdfSalt = Base64.getEncoder().encodeToString(salt),
                kdfParams = KdfParamsDto(65536, 3, 4),
                kdfVersion = 1,
            ),
        )

        val result = repositoryWithStore.login("user@example.com", password, "device-1", "Test Device")
        assertTrue(result.isSuccess)

        val persisted = sessionStore.load()
        assertEquals("persisted-token", persisted?.sessionToken)
        assertEquals("user@example.com", persisted?.email)
        // Crucially, only the wrapped VDK is persisted, never the raw one --
        // PersistedSessionStoreTest covers the store's own logic in detail,
        // this just proves AuthRepository wires the *right* material into it.
        assertArrayEqualsCustom(wrappedVdk, persisted!!.unwrapMaterial.wrappedVdk)
    }

    @Test
    fun `logout also clears the persisted sessionStore`() = runTest {
        val sessionStore = PersistedSessionStore(InMemorySettingsStorage(), FakeSessionCipherProvider())
        sessionStore.save("stale-token", VdkUnwrapMaterial(ByteArray(16), KdfParams.DEFAULT, ByteArray(48)), "user@example.com")
        val repositoryWithStore = AuthRepository(api, VaultCrypto(FakeMasterKeyDeriver()), sessionStore = sessionStore)
        AuthSessionHolder.set("some-token", ByteArray(32))

        val result = repositoryWithStore.logout()

        assertTrue(result.isSuccess)
        assertNull("logout must wipe the persisted session, not just the in-memory one", sessionStore.load())
    }

    @Test
    fun `logout with a null sessionStore does not crash (existing tests keep working unchanged)`() = runTest {
        AuthSessionHolder.set("some-token", ByteArray(32))

        val result = repository.logout() // repository from setUp() has sessionStore = null

        assertTrue(result.isSuccess)
    }

    // --- Sprint 3 (SPEC-BASE.md Section 24): offline password unlock ---

    @Test
    fun `unlockWithPassword re-derives and restores the same VDK without any network call`() = runTest {
        val vaultCrypto = VaultCrypto(FakeMasterKeyDeriver())
        val salt = vaultCrypto.generateKdfSalt()
        val password = "correct horse battery staple".toCharArray()
        val masterKey = vaultCrypto.deriveMasterKey(String(password).toByteArray(), salt, KdfParams.DEFAULT)
        val wrapKey = vaultCrypto.deriveWrapKey(masterKey)
        val vdk = vaultCrypto.generateVaultDataKey()
        val wrappedVdk = vaultCrypto.wrapVaultDataKey(vdk, wrapKey)

        AuthSessionHolder.set(
            sessionToken = "token-1",
            vaultDataKey = ByteArray(32), // arbitrary -- about to be locked
            unwrapMaterial = VdkUnwrapMaterial(salt, KdfParams.DEFAULT, wrappedVdk),
        )
        AuthSessionHolder.lock()

        val result = repository.unlockWithPassword(password)

        assertTrue(result.isSuccess)
        assertEquals(VaultLockState.UNLOCKED, AuthSessionHolder.lockState.value)
        assertArrayEqualsCustom(vdk, AuthSessionHolder.vaultDataKey!!)
        // FakeAuthApi must not have been touched -- no network round trip.
        org.junit.Assert.assertNull(api.lastLoginRequest)
    }

    @Test
    fun `unlockWithPassword with the wrong password fails and leaves the vault locked`() = runTest {
        val vaultCrypto = VaultCrypto(FakeMasterKeyDeriver())
        val salt = vaultCrypto.generateKdfSalt()
        val masterKey = vaultCrypto.deriveMasterKey("right-password".toByteArray(), salt, KdfParams.DEFAULT)
        val wrapKey = vaultCrypto.deriveWrapKey(masterKey)
        val vdk = vaultCrypto.generateVaultDataKey()
        val wrappedVdk = vaultCrypto.wrapVaultDataKey(vdk, wrapKey)

        AuthSessionHolder.set("token-1", ByteArray(32), VdkUnwrapMaterial(salt, KdfParams.DEFAULT, wrappedVdk))
        AuthSessionHolder.lock()

        val result = repository.unlockWithPassword("wrong-password".toCharArray())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is AuthRepository.AuthError.InvalidCredentials)
        assertEquals(VaultLockState.LOCKED, AuthSessionHolder.lockState.value)
    }

    @Test
    fun `unlockWithPassword fails cleanly when there is no active session`() = runTest {
        AuthSessionHolder.clear()

        val result = repository.unlockWithPassword("whatever".toCharArray())

        assertTrue(result.isFailure)
    }
}
