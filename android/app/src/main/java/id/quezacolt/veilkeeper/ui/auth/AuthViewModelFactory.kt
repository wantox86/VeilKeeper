package id.quezacolt.veilkeeper.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import id.quezacolt.veilkeeper.data.AuthRepository

/**
 * Manual ViewModelProvider.Factory (no DI framework -- see NetworkModule's
 * doc comment for why that's an intentional Sprint 1 choice).
 */
class AuthViewModelFactory(
    private val repository: AuthRepository,
    private val deviceIdentifier: String,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        LoginViewModel::class.java -> LoginViewModel(repository, deviceIdentifier) as T
        RegisterViewModel::class.java -> RegisterViewModel(repository) as T
        else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
