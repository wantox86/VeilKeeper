package id.quezacolt.veilkeeper.ui.auth

import id.quezacolt.veilkeeper.crypto.FakeMasterKeyDeriver
import id.quezacolt.veilkeeper.crypto.KdfParams
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.FakeAuthApi
import id.quezacolt.veilkeeper.data.KdfParamsDto
import id.quezacolt.veilkeeper.data.LoginResponse
import id.quezacolt.veilkeeper.data.PreloginResponse
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Base64

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LoginViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var api: FakeAuthApi
    private lateinit var viewModel: LoginViewModel

    @Before
    fun setUp() {
        api = FakeAuthApi()
        AuthSessionHolder.clear()

        val testDispatcher = StandardTestDispatcher()
        val repository = AuthRepository(
            api = api,
            vaultCrypto = VaultCrypto(FakeMasterKeyDeriver()),
            computeDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        viewModel = LoginViewModel(repository, deviceIdentifier = "test-device")
    }

    @Test
    fun `blank fields show a validation error without calling the repository`() = runTest {
        viewModel.onEmailChange("")
        viewModel.onPasswordChange("")

        viewModel.login()

        assertEquals("Email and password are required", viewModel.uiState.value.errorMessage)
        assertNull(api.lastLoginRequest)
    }

    @Test
    fun `successful login updates state to loggedIn`() = runTest {
        val salt = ByteArray(16) { it.toByte() }
        api.preloginResponse = PreloginResponse(Base64.getEncoder().encodeToString(salt), KdfParamsDto(65536, 3, 4), 1)

        val vaultCrypto = VaultCrypto(FakeMasterKeyDeriver())
        val masterKey = vaultCrypto.deriveMasterKey("password123".toByteArray(), salt, KdfParams.DEFAULT)
        val wrapKey = vaultCrypto.deriveWrapKey(masterKey)
        val vdk = vaultCrypto.generateVaultDataKey()
        val wrapped = vaultCrypto.wrapVaultDataKey(vdk, wrapKey)

        api.loginResult = retrofit2.Response.success(
            LoginResponse(
                sessionToken = "abc123",
                expiresAt = "2030-01-01T00:00:00Z",
                wrappedVdk = Base64.getEncoder().encodeToString(wrapped),
                kdfSalt = Base64.getEncoder().encodeToString(salt),
                kdfParams = KdfParamsDto(65536, 3, 4),
                kdfVersion = 1,
            ),
        )

        viewModel.onEmailChange("user@example.com")
        viewModel.onPasswordChange("password123")
        viewModel.login()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.loggedIn)
        assertFalse(state.isLoading)
        assertNull(state.errorMessage)
        assertEquals("abc123", AuthSessionHolder.sessionToken)
    }

    @Test
    fun `failed login surfaces an error message and does not set loggedIn`() = runTest {
        api.loginResult = FakeAuthApi.errorResponse(401, "invalid_credentials", "invalid email or auth key")

        viewModel.onEmailChange("user@example.com")
        viewModel.onPasswordChange("wrong-password")
        viewModel.login()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.loggedIn)
        assertFalse(state.isLoading)
        assertTrue(state.errorMessage != null)
    }
}
