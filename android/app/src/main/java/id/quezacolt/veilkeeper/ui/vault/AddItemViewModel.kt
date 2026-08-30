package id.quezacolt.veilkeeper.ui.vault

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AddItemUiState(
    val title: String = "",
    val blocks: List<ContentBlockDto> = emptyList(),
    val isSaving: Boolean = false,
    val saved: Boolean = false,
    val errorMessage: String? = null,
)

/**
 * Backs the Add Item flow (SPEC-BASE.md Section 21): freely add
 * text/secret/note content blocks, then encrypt-and-upload as one vault
 * item. Image blocks are Sprint 5 scope (Attachments), not offered here.
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

    fun save() {
        val state = _uiState.value
        if (state.title.isBlank()) {
            _uiState.value = state.copy(errorMessage = "Title is required")
            return
        }
        if (state.blocks.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "Add at least one piece of content")
            return
        }

        _uiState.value = state.copy(isSaving = true, errorMessage = null)
        viewModelScope.launch {
            val result = repository.createItem(categoryId, state.title.trim(), state.blocks)
            _uiState.value = result.fold(
                onSuccess = { _uiState.value.copy(isSaving = false, saved = true) },
                onFailure = { _uiState.value.copy(isSaving = false, errorMessage = it.message ?: "Failed to save item") },
            )
        }
    }
}
