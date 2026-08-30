package id.quezacolt.veilkeeper.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of a single "image" content block's attachment, keyed by attachment ID (SPEC-BASE.md Section 20's "encrypted image preview"). */
sealed class AttachmentImageState {
    data object Loading : AttachmentImageState()
    data class Loaded(val bytes: ByteArray, val mimeType: String) : AttachmentImageState()
    data class Error(val message: String) : AttachmentImageState()
}

data class VaultDetailUiState(
    val isLoading: Boolean = true,
    val item: DecryptedVaultItem? = null,
    val deleted: Boolean = false,
    val errorMessage: String? = null,
    val attachmentImages: Map<Long, AttachmentImageState> = emptyMap(),
)

/**
 * Backs the Vault Detail screen (SPEC-BASE.md Section 20): fetches the
 * item's ciphertext and decrypts it via [VaultRepository] (which uses the
 * VDK unwrapped at login) before this ViewModel ever sees plaintext
 * content -- nothing here decrypts directly.
 *
 * Sprint 5: "image" content blocks reference an attachment ID (see
 * [id.quezacolt.veilkeeper.crypto.ContentBlockDto]'s doc comment); their
 * encrypted bytes are downloaded+decrypted lazily via [loadAttachmentImage],
 * one call per block the screen actually renders, not all up front.
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

    /**
     * Downloads + decrypts attachment [attachmentId]'s bytes for preview.
     * No-ops if already loading/loaded (called from the screen's
     * `LaunchedEffect(block)`, which can re-run on recomposition -- this
     * avoids re-fetching the same image repeatedly).
     */
    fun loadAttachmentImage(attachmentId: Long) {
        if (_uiState.value.attachmentImages.containsKey(attachmentId)) return

        _uiState.value = _uiState.value.copy(attachmentImages = _uiState.value.attachmentImages + (attachmentId to AttachmentImageState.Loading))
        viewModelScope.launch {
            val result = repository.downloadAttachment(itemId, attachmentId)
            val newState: AttachmentImageState = result.fold(
                onSuccess = { AttachmentImageState.Loaded(it.bytes, it.mimeType) },
                onFailure = { AttachmentImageState.Error(it.message ?: "Failed to load image") },
            )
            _uiState.value = _uiState.value.copy(attachmentImages = _uiState.value.attachmentImages + (attachmentId to newState))
        }
    }
}
