package id.quezacolt.veilkeeper.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.data.isVaultLocked
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
    // Post-launch fixes batch 2, item #4: edit mode.
    val isEditing: Boolean = false,
    val editTitle: String = "",
    val editBlocks: List<ContentBlockDto> = emptyList(),
    val isSavingEdit: Boolean = false,
    val editErrorMessage: String? = null,
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
                onFailure = {
                    if (it.isVaultLocked()) VaultDetailUiState(isLoading = false)
                    else VaultDetailUiState(isLoading = false, errorMessage = it.message ?: "Failed to load item")
                },
            )
        }
    }

    /**
     * Post-launch fixes batch 3: re-fetch without touching [VaultDetailUiState.isLoading]
     * -- the same `refreshSilently` pattern `HomeViewModel`/`CategoryViewModel`
     * already have, extended to this screen so returning here from the
     * Unlock screen (either the normal in-app auto-lock case, or the "vault
     * got locked while this screen was open" case this batch fixes) shows
     * freshly-decrypted content instead of whatever was last loaded before
     * the lock. Guarded against re-entrancy the same way. Deliberately does
     * *not* touch [VaultDetailUiState.isEditing]/`editBlocks` if a draft was
     * in progress -- re-seeding a live edit out from under the user on an
     * unrelated resume would be surprising; if the vault was actually
     * locked mid-edit, the edit-mode call sites below already exit edit
     * mode themselves.
     */
    fun refreshSilently() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            val result = repository.getItem(itemId)
            result.fold(
                onSuccess = { item -> _uiState.value = _uiState.value.copy(item = item) },
                onFailure = { if (!it.isVaultLocked()) _uiState.value = _uiState.value.copy(errorMessage = it.message ?: "Failed to load item") },
            )
        }
    }

    fun delete() {
        viewModelScope.launch {
            val result = repository.deleteItem(itemId)
            _uiState.value = result.fold(
                onSuccess = { _uiState.value.copy(deleted = true) },
                onFailure = { if (it.isVaultLocked()) _uiState.value else _uiState.value.copy(errorMessage = it.message ?: "Failed to delete item") },
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

    // --- Post-launch fixes batch 2, item #4: edit mode ----------------------
    //
    // Reuses the exact same content-block form UI as Add Item
    // (ContentBlockEditingComponents.kt's AddBlockRow/ContentPreviewRow) --
    // see VaultDetailScreen. Unlike Add Item's `PendingImage` deferral (which
    // exists only because Add Item's item doesn't have a server-side ID
    // yet), edit mode's item already exists, so a newly picked image is
    // uploaded immediately via [addEditImageBlock] -- one less moving part.

    /** Enters edit mode, seeding the draft from the currently-loaded item. No-ops if nothing is loaded yet. */
    fun startEdit() {
        val item = _uiState.value.item ?: return
        _uiState.value = _uiState.value.copy(
            isEditing = true,
            editTitle = item.title,
            editBlocks = item.content,
            editErrorMessage = null,
        )
    }

    /** Discards the draft (any images already uploaded via [addEditImageBlock] during this session are NOT rolled back -- same disclosed limitation as AddItemViewModel.save's doc comment). */
    fun cancelEdit() {
        _uiState.value = _uiState.value.copy(isEditing = false, editErrorMessage = null)
    }

    fun onEditTitleChange(value: String) {
        _uiState.value = _uiState.value.copy(editTitle = value, editErrorMessage = null)
    }

    fun addEditBlock(type: String, label: String?, value: String) {
        if (value.isBlank()) return
        _uiState.value = _uiState.value.copy(
            editBlocks = _uiState.value.editBlocks + ContentBlockDto(type = type, label = label?.takeIf { it.isNotBlank() }, value = value),
            editErrorMessage = null,
        )
    }

    /**
     * Uploads [bytes] as a new attachment against this (already-existing)
     * item immediately, then appends the resulting "image" block to the
     * draft. Post-launch fixes batch 3: if the vault got locked mid-edit
     * (see [isVaultLocked]'s doc comment), this exits edit mode instead of
     * showing an "upload failed" retry-style error -- the draft would be
     * re-encrypted against a VDK that no longer exists in memory, so there
     * is nothing a Retry could do here either; the global lock-state effect
     * takes the user to Unlock, and they can re-enter edit mode fresh
     * afterward via [refreshSilently]'s reload + [startEdit].
     */
    fun addEditImageBlock(filename: String, mimeType: String, bytes: ByteArray) {
        _uiState.value = _uiState.value.copy(isSavingEdit = true, editErrorMessage = null)
        viewModelScope.launch {
            val result = repository.uploadAttachment(itemId, filename, mimeType, bytes)
            _uiState.value = result.fold(
                onSuccess = { ref ->
                    val block = ContentBlockDto(type = "image", label = filename, value = ref.id.toString())
                    _uiState.value.copy(isSavingEdit = false, editBlocks = _uiState.value.editBlocks + block)
                },
                onFailure = { e ->
                    if (e.isVaultLocked()) _uiState.value.copy(isSavingEdit = false, isEditing = false)
                    else _uiState.value.copy(isSavingEdit = false, editErrorMessage = "Failed to upload image: ${e.message}")
                },
            )
        }
    }

    /**
     * Removes block [index] from the draft. For an "image" block this also
     * deletes the underlying attachment server-side (attachments in edit
     * mode are uploaded immediately, see [addEditImageBlock] -- there is no
     * "pending, not yet real" state to just discard locally, unlike text/
     * secret/note blocks which are draft-only until [saveEdit]). The screen
     * is expected to have already confirmed this via
     * [id.quezacolt.veilkeeper.ui.components.VeilKeeperConfirmDeleteDialog]
     * before calling this for an image block (Post-launch fixes batch 2,
     * item #2) -- non-image blocks are just draft edits, same as Add Item,
     * and don't need a confirmation.
     */
    fun removeEditBlock(index: Int) {
        val block = _uiState.value.editBlocks.getOrNull(index) ?: return
        val attachmentId = block.value.toLongOrNull()
        if (block.type == "image" && attachmentId != null) {
            viewModelScope.launch {
                val result = repository.deleteAttachment(itemId, attachmentId)
                _uiState.value = result.fold(
                    onSuccess = { _uiState.value.copy(editBlocks = _uiState.value.editBlocks.filterIndexed { i, _ -> i != index }) },
                    onFailure = { e ->
                        if (e.isVaultLocked()) _uiState.value.copy(isEditing = false)
                        else _uiState.value.copy(editErrorMessage = "Failed to delete attachment: ${e.message}")
                    },
                )
            }
            return
        }
        _uiState.value = _uiState.value.copy(editBlocks = _uiState.value.editBlocks.filterIndexed { i, _ -> i != index })
    }

    /** Re-encrypts the full draft payload with the VDK and PUTs it (SPEC-BASE.md `PUT /api/v1/vault/items/{id}`, live since Sprint 2). */
    fun saveEdit() {
        val state = _uiState.value
        if (state.editTitle.isBlank()) {
            _uiState.value = state.copy(editErrorMessage = "Title is required")
            return
        }
        if (state.editBlocks.isEmpty()) {
            _uiState.value = state.copy(editErrorMessage = "Add at least one piece of content")
            return
        }

        val title = state.editTitle.trim()
        _uiState.value = state.copy(isSavingEdit = true, editErrorMessage = null)
        viewModelScope.launch {
            val result = repository.updateItem(itemId, null, title, state.editBlocks)
            _uiState.value = result.fold(
                onSuccess = { updated -> _uiState.value.copy(isSavingEdit = false, isEditing = false, item = updated) },
                onFailure = { e ->
                    if (e.isVaultLocked()) _uiState.value.copy(isSavingEdit = false, isEditing = false)
                    else _uiState.value.copy(isSavingEdit = false, editErrorMessage = e.message ?: "Failed to save changes")
                },
            )
        }
    }
}
