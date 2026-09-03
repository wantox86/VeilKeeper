package id.quezacolt.veilkeeper.ui.auth

import id.quezacolt.veilkeeper.crypto.FakeMasterKeyDeriver
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.FakeAuthApi
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RegisterViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var api: FakeAuthApi
    private lateinit var viewModel: RegisterViewModel

    @Before
    fun setUp() {
        api = FakeAuthApi()
        val testDispatcher = StandardTestDispatcher()
        val repository = AuthRepository(
            api = api,
            vaultCrypto = VaultCrypto(FakeMasterKeyDeriver()),
            computeDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        viewModel = RegisterViewModel(repository)
    }

    @Test
    fun `mismatched passwords are rejected before calling the repository`() = runTest {
        viewModel.onEmailChange("user@example.com")
        viewModel.onPasswordChange("password123")
        viewModel.onConfirmPasswordChange("different123")

        viewModel.register()

        assertEquals("Passwords do not match", viewModel.uiState.value.errorMessage)
        assertNull(api.lastRegisterRequest)
    }

    @Test
    fun `too-short password is rejected before calling the repository`() = runTest {
        viewModel.onEmailChange("user@example.com")
        viewModel.onPasswordChange("short")
        viewModel.onConfirmPasswordChange("short")

        viewModel.register()

        assertTrue(viewModel.uiState.value.errorMessage!!.contains("at least"))
        assertNull(api.lastRegisterRequest)
    }

    @Test
    fun `successful registration updates state to registered`() = runTest {
        viewModel.onEmailChange("user@example.com")
        viewModel.onPasswordChange("password123")
        viewModel.onConfirmPasswordChange("password123")
        viewModel.onInviteCodeChange("family2026")

        viewModel.register()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state.registered)
        assertFalse(state.isLoading)
        assertEquals("user@example.com", api.lastRegisterRequest?.email)
        assertEquals("family2026", api.lastRegisterRequest?.inviteCode)
    }

    @Test
    fun `duplicate email surfaces an error and does not mark registered`() = runTest {
        api.registerResult = FakeAuthApi.errorResponse(409, "email_taken", "an account with this email already exists")

        viewModel.onEmailChange("dupe@example.com")
        viewModel.onPasswordChange("password123")
        viewModel.onConfirmPasswordChange("password123")
        viewModel.onInviteCodeChange("family2026")

        viewModel.register()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.registered)
        assertTrue(state.errorMessage != null)
    }

    @Test
    fun `missing invite code is rejected before calling the repository`() = runTest {
        viewModel.onEmailChange("user@example.com")
        viewModel.onPasswordChange("password123")
        viewModel.onConfirmPasswordChange("password123")
        // inviteCode left blank

        viewModel.register()

        assertEquals("Invite code is required", viewModel.uiState.value.errorMessage)
        assertNull(api.lastRegisterRequest)
    }

    @Test
    fun `invalid invite code from server surfaces its exact message and does not mark registered`() = runTest {
        api.registerResult = FakeAuthApi.errorResponse(403, "invalid_invite_code", "invalid invite code")

        viewModel.onEmailChange("user@example.com")
        viewModel.onPasswordChange("password123")
        viewModel.onConfirmPasswordChange("password123")
        viewModel.onInviteCodeChange("wrong-code")

        viewModel.register()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.registered)
        assertEquals("invalid invite code", state.errorMessage)
    }

    @Test
    fun `registration closed (no invite codes configured) surfaces the server's message`() = runTest {
        api.registerResult = FakeAuthApi.errorResponse(403, "registration_closed", "registration is currently closed")

        viewModel.onEmailChange("user@example.com")
        viewModel.onPasswordChange("password123")
        viewModel.onConfirmPasswordChange("password123")
        viewModel.onInviteCodeChange("anything")

        viewModel.register()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.registered)
        assertEquals("registration is currently closed", state.errorMessage)
    }
}
