package id.quezacolt.veilkeeper.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class RegisterUiState(
    val email: String = "",
    val username: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val inviteCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val registered: Boolean = false,
)

/**
 * Backs the Register screen (SPEC-BASE.md Section 18.2). The UI built on top
 * of this must surface the "no password recovery" disclosure required by
 * CLAUDE.md Resolved Design Decision #2 -- this ViewModel only owns
 * input/validation/submission state, not that copy.
 */
class RegisterViewModel(
    private val repository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RegisterUiState())
    val uiState: StateFlow<RegisterUiState> = _uiState.asStateFlow()

    fun onEmailChange(value: String) {
        _uiState.value = _uiState.value.copy(email = value, errorMessage = null)
    }

    fun onUsernameChange(value: String) {
        _uiState.value = _uiState.value.copy(username = value, errorMessage = null)
    }

    fun onPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(password = value, errorMessage = null)
    }

    fun onConfirmPasswordChange(value: String) {
        _uiState.value = _uiState.value.copy(confirmPassword = value, errorMessage = null)
    }

    fun onInviteCodeChange(value: String) {
        _uiState.value = _uiState.value.copy(inviteCode = value, errorMessage = null)
    }

    fun register() {
        val state = _uiState.value

        if (state.email.isBlank() || state.password.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Email and password are required")
            return
        }
        if (state.password.length < MIN_PASSWORD_LENGTH) {
            _uiState.value = state.copy(errorMessage = "Password must be at least $MIN_PASSWORD_LENGTH characters")
            return
        }
        if (state.password != state.confirmPassword) {
            _uiState.value = state.copy(errorMessage = "Passwords do not match")
            return
        }
        if (state.inviteCode.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Invite code is required")
            return
        }

        _uiState.value = state.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val passwordChars = state.password.toCharArray()
            val result = repository.register(
                email = state.email.trim(),
                password = passwordChars,
                username = state.username.trim().ifBlank { null },
                inviteCode = state.inviteCode.trim(),
            )
            passwordChars.fill(' ')

            _uiState.value = result.fold(
                onSuccess = { _uiState.value.copy(isLoading = false, registered = true) },
                onFailure = { e -> _uiState.value.copy(isLoading = false, errorMessage = e.message ?: "Registration failed") },
            )
        }
    }

    companion object {
        private const val MIN_PASSWORD_LENGTH = 8
    }
}
