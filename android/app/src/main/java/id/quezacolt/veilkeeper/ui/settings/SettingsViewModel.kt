package id.quezacolt.veilkeeper.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.AutoLockTimeout
import id.quezacolt.veilkeeper.data.ClipboardClearDelay
import id.quezacolt.veilkeeper.data.SettingsRepository
import id.quezacolt.veilkeeper.data.VaultBiometricManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val autoLockTimeout: AutoLockTimeout = AutoLockTimeout.DEFAULT,
    val clipboardClearDelay: ClipboardClearDelay = ClipboardClearDelay.DEFAULT,
    val biometricEnabled: Boolean = false,
    val biometricAvailableOnDevice: Boolean = false,
    val errorMessage: String? = null,
    val loggedOut: Boolean = false,
)

/**
 * Backs the minimal Settings screen (Sprint 3 scope item 6): auto-lock
 * timeout, clipboard auto-clear delay, and the biometric unlock toggle
 * (SPEC-BASE.md Sections 22-25). Biometric enrollment/disablement itself is
 * driven by the screen (enroll needs a `FragmentActivity` for
 * `BiometricPrompt`); this ViewModel persists the non-secret preferences and
 * tracks the resulting `biometricEnabled` flag explicitly (rather than
 * re-querying [VaultBiometricManager.isEnabled] reactively -- there is no
 * Flow-based signal for Keystore/SharedPreferences state, so the screen
 * reports outcomes back via [onBiometricEnrolled]/[onBiometricDisabled]).
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val biometricManager: VaultBiometricManager,
    private val authRepository: AuthRepository,
    biometricAvailableOnDevice: Boolean,
) : ViewModel() {

    private val _biometricEnabled = MutableStateFlow(biometricManager.isEnabled())
    private val _errorMessage = MutableStateFlow<String?>(null)
    private val _loggedOut = MutableStateFlow(false)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.autoLockTimeout,
        settingsRepository.clipboardClearDelay,
        _biometricEnabled,
        _errorMessage,
        _loggedOut,
    ) { autoLock, clipboardDelay, biometricEnabled, error, loggedOut ->
        SettingsUiState(
            autoLockTimeout = autoLock,
            clipboardClearDelay = clipboardDelay,
            biometricEnabled = biometricEnabled,
            biometricAvailableOnDevice = biometricAvailableOnDevice,
            errorMessage = error,
            loggedOut = loggedOut,
        )
    }.stateIn(
        viewModelScope,
        kotlinx.coroutines.flow.SharingStarted.Eagerly,
        SettingsUiState(
            autoLockTimeout = settingsRepository.autoLockTimeout.value,
            clipboardClearDelay = settingsRepository.clipboardClearDelay.value,
            biometricEnabled = _biometricEnabled.value,
            biometricAvailableOnDevice = biometricAvailableOnDevice,
        ),
    )

    fun setAutoLockTimeout(timeout: AutoLockTimeout) = settingsRepository.setAutoLockTimeout(timeout)

    fun setClipboardClearDelay(delay: ClipboardClearDelay) = settingsRepository.setClipboardClearDelay(delay)

    /** Called by the screen after a [VaultBiometricManager.enroll] callback fires. */
    fun onBiometricEnrollResult(result: Result<Unit>) {
        result.onSuccess { _biometricEnabled.value = true }
        _errorMessage.value = result.exceptionOrNull()?.message
    }

    fun disableBiometric() {
        biometricManager.disable()
        _biometricEnabled.value = false
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
            _loggedOut.value = true
        }
    }

    /** The VDK to enroll biometric unlock with -- only valid while unlocked (Settings is only reachable from Home). */
    fun currentVdkForEnrollment(): ByteArray? = AuthSessionHolder.vaultDataKey
}
