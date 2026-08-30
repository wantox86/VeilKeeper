package id.quezacolt.veilkeeper.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.Category
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class CategoryUiState(
    val isLoading: Boolean = true,
    val category: Category? = null,
    val allItems: List<DecryptedVaultItem> = emptyList(),
    val query: String = "",
    val errorMessage: String? = null,
) {
    /**
     * Local, client-side-only filter over already-decrypted items
     * (SPEC-BASE.md Section 16: search must never send plaintext queries to
     * the backend). Matches on title or preview text.
     */
    val visibleItems: List<DecryptedVaultItem>
        get() = if (query.isBlank()) {
            allItems
        } else {
            allItems.filter {
                it.title.contains(query, ignoreCase = true) || it.preview.contains(query, ignoreCase = true)
            }
        }
}

/** Backs the Category screen (SPEC-BASE.md Section 19). */
class CategoryViewModel(
    private val repository: VaultRepository,
    private val categoryId: Long,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CategoryUiState())
    val uiState: StateFlow<CategoryUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val categoriesResult = repository.listCategories()
            val itemsResult = repository.listItems(categoryId)

            val categories = categoriesResult.getOrElse {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = it.message ?: "Failed to load category")
                return@launch
            }
            val items = itemsResult.getOrElse {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = it.message ?: "Failed to load items")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                category = categories.firstOrNull { it.id == categoryId },
                allItems = items,
            )
        }
    }

    fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }
}
