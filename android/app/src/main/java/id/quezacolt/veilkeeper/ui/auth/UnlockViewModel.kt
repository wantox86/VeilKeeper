package id.quezacolt.veilkeeper.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class UnlockUiState(
    val email: String? = AuthSessionHolder.email,
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Backs the Unlock screen shown after auto-lock (SPEC-BASE.md Section 24)
 * clears the in-memory VDK. Password-based unlock re-derives the VDK
 * entirely offline via [AuthRepository.unlockWithPassword] (no network call,
 * no fresh session) -- biometric unlock is driven separately by
 * [id.quezacolt.veilkeeper.data.VaultBiometricManager] from the screen
 * itself (it needs a `FragmentActivity`, which this ViewModel deliberately
 * has no reference to), reporting back into [onBiometricResult].
 *
 * Successful unlock (either path) is observed globally via
 * [AuthSessionHolder.lockState] in `MainActivity`'s NavHost, which pops this
 * screen -- this ViewModel does not navigate itself.
 */
class UnlockViewModel(private val repository: AuthRepository) : ViewModel() {
    private val _uiState = MutableStateFlow(UnlockUiState())
    val uiState: StateFlow<UnlockUiState> = _uiState.asStateFlow()

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, errorMessage = null)
    }

    fun unlockWithPassword() {
        val state = _uiState.value
        if (state.password.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Password is required")
            return
        }
        _uiState.value = state.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val passwordChars = state.password.toCharArray()
            val result = repository.unlockWithPassword(passwordChars)
            passwordChars.fill(' ')
            _uiState.value = result.fold(
                onSuccess = { _uiState.value.copy(isLoading = false, password = "") },
                onFailure = { e -> _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Unlock failed") },
            )
        }
    }

    /** Called by the screen after a [id.quezacolt.veilkeeper.data.VaultBiometricManager.unlock] callback fires. */
    fun onBiometricResult(result: Result<Unit>) {
        _uiState.value = result.fold(
            onSuccess = { _uiState.value.copy(errorMessage = null) },
            onFailure = { e -> _uiState.value.copy(errorMessage = e.message ?: "Biometric unlock failed") },
        )
    }

    fun logout() {
        viewModelScope.launch { repository.logout() }
    }
}
