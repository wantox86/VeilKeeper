package id.quezacolt.veilkeeper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.DeviceDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Phase 4 (plan.md): state for the Devices & Sessions screen. */
sealed class DevicesUiState {
    object Loading : DevicesUiState()
    data class Loaded(val devices: List<DeviceDto>) : DevicesUiState()
    data class Error(val message: String) : DevicesUiState()
}

/**
 * Backs the Devices & Sessions screen (plan.md Phase 4): loads every device
 * tied to the account (GET /api/v1/devices, backend already marks which one
 * is [DeviceDto.isCurrent]) and lets the user revoke any other device
 * (DELETE /api/v1/devices/{id}). Deliberately its own ViewModel rather than
 * folded into [SettingsViewModel] -- that one's state/uiState shape
 * (auto-lock/clipboard/biometric prefs, all synchronous local settings) has
 * nothing in common with this screen's network-backed list+loading+error
 * state, mirroring how vault screens each get their own ViewModel instead of
 * sharing one.
 */
class DevicesViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<DevicesUiState>(DevicesUiState.Loading)
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private val _revokingDeviceId = MutableStateFlow<Long?>(null)
    val revokingDeviceId: StateFlow<Long?> = _revokingDeviceId.asStateFlow()

    fun loadDevices() {
        _state.value = DevicesUiState.Loading
        viewModelScope.launch {
            authRepository.listDevices().fold(
                onSuccess = { devices -> _state.value = DevicesUiState.Loaded(devices) },
                onFailure = { error -> _state.value = DevicesUiState.Error(errorMessage(error)) },
            )
        }
    }

    /**
     * Revokes [deviceId], then reloads the list from the server rather than
     * just filtering it out client-side -- the backend keeps revoked devices
     * in GET /api/v1/devices (store.go's ListDevices doc comment: "revoked
     * ones included, so the client can show device history"), so a refetch
     * is what actually surfaces the resulting `revoked_at` badge instead of
     * silently dropping the row.
     */
    fun revokeDevice(deviceId: Long) {
        if (_revokingDeviceId.value != null) return
        _revokingDeviceId.value = deviceId
        viewModelScope.launch {
            try {
                authRepository.revokeDevice(deviceId).fold(
                    onSuccess = { loadDevices() },
                    onFailure = { error -> _state.value = DevicesUiState.Error(errorMessage(error)) },
                )
            } finally {
                _revokingDeviceId.value = null
            }
        }
    }

    private fun errorMessage(error: Throwable): String {
        if (error is CancellationException) throw error
        return error.message ?: "something went wrong"
    }

    companion object {
        fun factory(authRepository: AuthRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { DevicesViewModel(authRepository) }
        }
    }
}
