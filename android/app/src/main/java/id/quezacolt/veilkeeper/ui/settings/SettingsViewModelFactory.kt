package id.quezacolt.veilkeeper.ui.settings

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.SettingsRepository
import id.quezacolt.veilkeeper.data.VaultBiometricManager

/** See [id.quezacolt.veilkeeper.ui.home.VaultViewModelFactory] for why this DSL is used over a shared factory class. */
object SettingsViewModelFactory {
    fun create(
        settingsRepository: SettingsRepository,
        biometricManager: VaultBiometricManager,
        authRepository: AuthRepository,
        biometricAvailableOnDevice: Boolean,
    ): ViewModelProvider.Factory = viewModelFactory {
        initializer { SettingsViewModel(settingsRepository, biometricManager, authRepository, biometricAvailableOnDevice) }
    }
}
