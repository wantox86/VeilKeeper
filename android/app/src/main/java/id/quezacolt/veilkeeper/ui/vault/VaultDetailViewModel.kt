package id.quezacolt.veilkeeper.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VaultDetailUiState(
    val isLoading: Boolean = true,
    val item: DecryptedVaultItem? = null,
    val deleted: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Backs the Vault Detail screen (SPEC-BASE.md Section 20): fetches the
 * item's ciphertext and decrypts it via [VaultRepository] (which uses the
 * VDK unwrapped at login) before this ViewModel ever sees plaintext
 * content -- nothing here decrypts directly.
 */
class VaultDetailViewModel(
    private val repository: VaultRepository,
    private val itemId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VaultDetailUiState())
    val uiState: StateFlow<VaultDetailUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.getItem(itemId)
            _uiState.value = result.fold(
                onSuccess = { VaultDetailUiState(isLoading = false, item = it) },
                onFailure = { VaultDetailUiState(isLoading = false, errorMessage = it.message ?: "Failed to load item") },
            )
        }
    }

    fun delete() {
        viewModelScope.launch {
            val result = repository.deleteItem(itemId)
            _uiState.value = result.fold(
                onSuccess = { _uiState.value.copy(deleted = true) },
                onFailure = { _uiState.value.copy(errorMessage = it.message ?: "Failed to delete item") },
            )
        }
    }
}
