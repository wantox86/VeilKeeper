package id.quezacolt.veilkeeper.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.Category
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.data.VaultSearch
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
     * the backend). Sprint 4: delegates to the same [VaultSearch] matcher
     * used by the Home screen's global search, so category-scoped search
     * matches title/labels/notes/content consistently rather than the
     * title-or-preview-only heuristic this screen used pre-Sprint-4.
     */
    val visibleItems: List<DecryptedVaultItem>
        get() = VaultSearch.filter(allItems, query)
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
