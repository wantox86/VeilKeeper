package id.quezacolt.veilkeeper.ui.settings

import id.quezacolt.veilkeeper.crypto.FakeMasterKeyDeriver
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.DeviceDto
import id.quezacolt.veilkeeper.data.FakeAuthApi
import id.quezacolt.veilkeeper.ui.auth.MainDispatcherRule
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var api: FakeAuthApi
    private lateinit var repository: AuthRepository
    private lateinit var viewModel: DevicesViewModel

    private val thisDevice = DeviceDto(
        id = 1,
        deviceIdentifier = "device-1",
        deviceName = "This Phone",
        createdAt = "2026-09-01T00:00:00Z",
        lastSeenAt = "2026-09-25T00:00:00Z",
        isCurrent = true,
    )
    private val otherDevice = DeviceDto(
        id = 2,
        deviceIdentifier = "device-2",
        deviceName = "Other Tablet",
        createdAt = "2026-09-10T00:00:00Z",
        lastSeenAt = "2026-09-20T00:00:00Z",
        isCurrent = false,
    )

    @Before
    fun setUp() {
        api = FakeAuthApi()
        AuthSessionHolder.set("token-1", ByteArray(32))
        val testDispatcher = StandardTestDispatcher()
        repository = AuthRepository(
            api = api,
            vaultCrypto = VaultCrypto(FakeMasterKeyDeriver()),
            computeDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )
        viewModel = DevicesViewModel(repository)
    }

    @Test
    fun `loadDevices starts in Loading then moves to Loaded with the server's devices`() = runTest {
        api.listDevicesResult = retrofit2.Response.success(listOf(thisDevice, otherDevice))

        viewModel.loadDevices()
        assertTrue(viewModel.state.value is DevicesUiState.Loading)

        advanceUntilIdle()

        val state = viewModel.state.value
        assertTrue(state is DevicesUiState.Loaded)
        assertEquals(2, (state as DevicesUiState.Loaded).devices.size)
        assertTrue(state.devices.first { it.id == 1L }.isCurrent)
    }

    @Test
    fun `loadDevices surfaces a server error`() = runTest {
        api.listDevicesResult = FakeAuthApi.errorResponse(500, "internal", "something broke")

        viewModel.loadDevices()
        advanceUntilIdle()

        assertTrue(viewModel.state.value is DevicesUiState.Error)
    }

    @Test
    fun `revokeDevice tracks the revoking id and reloads the list on success`() = runTest {
        api.listDevicesResult = retrofit2.Response.success(listOf(thisDevice, otherDevice))
        viewModel.loadDevices()
        advanceUntilIdle()

        // After revoke, the server would report device 2 with revoked_at set;
        // the ViewModel refetches rather than trusting a client-side filter.
        api.listDevicesResult = retrofit2.Response.success(
            listOf(thisDevice, otherDevice.copy(revokedAt = "2026-09-25T01:00:00Z")),
        )

        viewModel.revokeDevice(2)
        assertEquals(2L, viewModel.revokingDeviceId.value)

        advanceUntilIdle()

        assertNull(viewModel.revokingDeviceId.value)
        assertEquals(2L, api.lastRevokeDeviceId)
        val state = viewModel.state.value
        assertTrue(state is DevicesUiState.Loaded)
        val revoked = (state as DevicesUiState.Loaded).devices.first { it.id == 2L }
        assertTrue(revoked.revokedAt != null)
    }

    @Test
    fun `revokeDevice ignores a second call while one is already in flight`() = runTest {
        api.listDevicesResult = retrofit2.Response.success(listOf(thisDevice, otherDevice))
        viewModel.loadDevices()
        advanceUntilIdle()

        viewModel.revokeDevice(2)
        viewModel.revokeDevice(2)
        advanceUntilIdle()

        assertEquals(2L, api.lastRevokeDeviceId)
    }
}
