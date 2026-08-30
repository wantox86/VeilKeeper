package id.quezacolt.veilkeeper.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * An image picked (and already compressed, see
 * [id.quezacolt.veilkeeper.data.ImageCompressor]) but not yet uploaded --
 * held in memory only until [AddItemViewModel.save] uploads it, since
 * uploading requires an already-existing vault item ID (see [save]'s doc
 * comment).
 */
data class PendingImage(
    val filename: String,
    val mimeType: String,
    val bytes: ByteArray,
)

data class AddItemUiState(
    val title: String = "",
    val blocks: List<ContentBlockDto> = emptyList(),
    val pendingImages: List<PendingImage> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Backs the Add Item flow (SPEC-BASE.md Section 21): freely add
 * text/secret/note/image content, then encrypt-and-upload as one vault
 * item.
 */
class AddItemViewModel(
    private val repository: VaultRepository,
    private val categoryId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddItemUiState())
    val uiState: StateFlow<AddItemUiState> = _uiState.asStateFlow()

    fun onTitleChange(value: String) {
        _uiState.value = _uiState.value.copy(title = value, errorMessage = null)
    }

    fun addBlock(type: String, label: String?, value: String) {
        if (value.isBlank()) return
        _uiState.value = _uiState.value.copy(
            blocks = _uiState.value.blocks + ContentBlockDto(type = type, label = label?.takeIf { it.isNotBlank() }, value = value),
            errorMessage = null,
        )
    }

    fun removeBlock(index: Int) {
        _uiState.value = _uiState.value.copy(blocks = _uiState.value.blocks.filterIndexed { i, _ -> i != index })
    }

    /** Called by the screen after picking + compressing an image (SPEC-BASE.md Section 17's picker/compress steps). */
    fun addPendingImage(filename: String, mimeType: String, bytes: ByteArray) {
        _uiState.value = _uiState.value.copy(pendingImages = _uiState.value.pendingImages + PendingImage(filename, mimeType, bytes), errorMessage = null)
    }

    fun removePendingImage(index: Int) {
        _uiState.value = _uiState.value.copy(pendingImages = _uiState.value.pendingImages.filterIndexed { i, _ -> i != index })
    }

    /**
     * Saves the item. Attachments can only be uploaded against an
     * *existing* vault item (`POST /vault/items/{id}/attachments`), so
     * image blocks can't be included in the initial `createItem` call the
     * way text/secret/note blocks are. Flow: create the item with the
     * non-image blocks first, upload each pending image against the new
     * item ID, then `updateItem` once more with the image blocks appended.
     *
     * Known, disclosed limitation: if an image upload fails partway
     * through, the item has already been created (with whatever images
     * uploaded successfully before the failure) -- there's no automatic
     * rollback of the just-created item. This is called out in the error
     * message so the user isn't left thinking nothing happened; a full
     * transactional multi-attachment save is more machinery than this
     * single-user homelab app's failure modes justify (SPEC-BASE.md
     * Section 56 Rule 1) -- the user can just delete the item and retry if
     * this happens.
     */
    fun save() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Title is required")
            return
        }
        if (state.blocks.isEmpty() && state.pendingImages.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "Add at least one piece of content")
            return
        }

        val title = state.title.trim()
        _uiState.value = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            val createResult = repository.createItem(categoryId, title, state.blocks)
            createResult.fold(
                onSuccess = { item -> finishSaveWithImages(item.id, title, state.blocks, state.pendingImages) },
                onFailure = { _uiState.value = _uiState.value.copy(isSaving = false, errorMessage = it.message ?: "Failed to save item") },
            )
        }
    }

    private suspend fun finishSaveWithImages(itemId: Long, title: String, baseBlocks: List<ContentBlockDto>, pendingImages: List<PendingImage>) {
        if (pendingImages.isEmpty()) {
            _uiState.value = _uiState.value.copy(isSaving = false, saved = true)
            return
        }

        val imageBlocks = mutableListOf<ContentBlockDto>()
        for (image in pendingImages) {
            val uploadResult = repository.uploadAttachment(itemId, image.filename, image.mimeType, image.bytes)
            val ref = uploadResult.getOrElse {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    errorMessage = "Item was saved, but uploading \"${image.filename}\" failed: ${it.message}",
                )
                return
            }
            imageBlocks += ContentBlockDto(type = "image", label = image.filename, value = ref.id.toString())
        }

        val updateResult = repository.updateItem(itemId, null, title, baseBlocks + imageBlocks)
        _uiState.value = updateResult.fold(
            onSuccess = { _uiState.value.copy(isSaving = false, saved = true) },
            onFailure = { _uiState.value.copy(isSaving = false, errorMessage = "Images uploaded, but saving the item failed: ${it.message}") },
        )
    }
}
