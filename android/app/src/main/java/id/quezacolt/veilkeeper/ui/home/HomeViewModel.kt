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
    /**
     * Post-launch fix: pull-to-refresh's own spinner state (SPEC-BASE.md
     * Section 18.3 gesture request), kept separate from [isLoading] so a
     * manual pull-to-refresh shows the small top indicator over the existing
     * content instead of swapping the whole screen to the full-screen
     * [id.quezacolt.veilkeeper.ui.components.VeilKeeperLoading] state.
     */
    val isRefreshing: Boolean = false,
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

    /** Full-screen initial load / explicit "Retry" tap: shows [HomeUiState.isLoading]. */
    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch {
            fetchAndApply()
            _uiState.value = _uiState.value.copy(isLoading = false)
        }
    }

    /**
     * Post-launch fix: manual pull-to-refresh gesture (SPEC-BASE.md Section
     * 18.3). Uses [HomeUiState.isRefreshing] instead of [HomeUiState.isLoading]
     * so the existing content stays visible under the pull indicator rather
     * than being replaced by the full-screen loading state.
     */
    fun onPullToRefresh() {
        _uiState.value = _uiState.value.copy(isRefreshing = true, errorMessage = null)
        viewModelScope.launch {
            fetchAndApply()
            _uiState.value = _uiState.value.copy(isRefreshing = false)
        }
    }

    /**
     * Post-launch fix: re-fetch without touching [HomeUiState.isLoading] or
     * [HomeUiState.isRefreshing] at all -- called when Home becomes visible
     * again (e.g. navigating back from Add Item after saving a new vault
     * item), so newly added content shows up without the user having to
     * force-close/reopen the app. No visible spinner is shown for this case
     * (SPEC-BASE.md Section 56 Rule 1: a loading flash on every back-
     * navigation would be more distracting than useful here); the screen
     * simply swaps in fresh data once the fetch completes.
     *
     * Guarded against re-entrancy: skipped while a foreground fetch
     * ([isLoading]/[isRefreshing]) is already in flight, which also avoids a
     * redundant duplicate fetch on the very first composition (Home's
     * lifecycle ON_RESUME fires around the same time as [init]'s own
     * [refresh] call).
     */
    fun refreshSilently() {
        val current = _uiState.value
        if (current.isLoading || current.isRefreshing) return
        viewModelScope.launch { fetchAndApply() }
    }

    /** Fetches categories + items and applies the result (or error) to [_uiState]. Leaves [HomeUiState.isLoading]/[HomeUiState.isRefreshing] untouched -- callers manage those. */
    private suspend fun fetchAndApply() {
        val categoriesResult = repository.listCategories()
        val itemsResult = repository.listItems()

        val categories = categoriesResult.getOrElse {
            _uiState.value = _uiState.value.copy(errorMessage = it.message ?: "Failed to load categories")
            return
        }
        val items = itemsResult.getOrElse {
            _uiState.value = _uiState.value.copy(errorMessage = it.message ?: "Failed to load vault items")
            return
        }

        _uiState.value = _uiState.value.copy(
            categories = categories,
            allItems = items,
        )
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
