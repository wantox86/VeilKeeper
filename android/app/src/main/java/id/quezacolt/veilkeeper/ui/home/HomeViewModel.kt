package id.quezacolt.veilkeeper.ui.home

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

private const val RECENT_ITEMS_LIMIT = 5

data class HomeUiState(
    val isLoading: Boolean = true,
    val categories: List<Category> = emptyList(),
    /** Every decrypted vault item across all categories, fetched once per [refresh]. */
    val allItems: List<DecryptedVaultItem> = emptyList(),
    /** Sprint 4 global search query (SPEC-BASE.md Section 16 / Phase 4). */
    val searchQuery: String = "",
    val errorMessage: String? = null,
) {
    val recentItems: List<DecryptedVaultItem> get() = allItems.take(RECENT_ITEMS_LIMIT)

    val isSearching: Boolean get() = searchQuery.isNotBlank()

    /**
     * Global search results: a pure in-memory filter (via [VaultSearch])
     * over [allItems], which is already the full decrypted list fetched by
     * [HomeViewModel.refresh] -- searching never triggers a new network
     * call or re-fetch, see CLAUDE.md Sprint 4 entry.
     */
    val searchResults: List<DecryptedVaultItem> get() = VaultSearch.filter(allItems, searchQuery)
}

/**
 * Backs the Home screen (SPEC-BASE.md Section 18.3): category tiles with
 * item counts, a "Recent" list of the most recently updated vault items
 * across all categories, and (Sprint 4) a global search bar over those same
 * already-decrypted items.
 */
class HomeViewModel(private val repository: VaultRepository) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            val categoriesResult = repository.listCategories()
            val itemsResult = repository.listItems()

            val categories = categoriesResult.getOrElse {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = it.message ?: "Failed to load categories")
                return@launch
            }
            val items = itemsResult.getOrElse {
                _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = it.message ?: "Failed to load vault items")
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                isLoading = false,
                categories = categories,
                allItems = items,
            )
        }
    }

    /**
     * Updates the global search query. Purely a local state change -- no
     * repository call, no network request, no persistence (see
     * [HomeUiState.searchResults] / CLAUDE.md Sprint 4 entry).
     */
    fun onSearchQueryChange(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }
}
