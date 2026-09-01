package id.quezacolt.veilkeeper.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.Category
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.data.VaultSearch
import id.quezacolt.veilkeeper.data.isVaultLocked
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

    /** Full-screen initial load / explicit "Retry" tap: shows [CategoryUiState.isLoading]. */
    fun refresh() {
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
        viewModelScope.launch { fetchAndApply() }
    }

    /**
     * Post-launch fixes batch 2, item #3: same bug as Home's pre-batch-1
     * "new item doesn't show up without leaving and reopening the app,"
     * just in this screen instead -- `CategoryViewModel` never got the
     * `HomeViewModel.refreshSilently()` fix from that batch. Root cause is
     * identical: Compose Navigation keeps this screen's `NavBackStackEntry`
     * (and this ViewModel) alive on the back stack while Add Item is on top,
     * so `init`'s one-time [refresh] never re-runs on its own when
     * navigating back. Fix is the exact same pattern as
     * `HomeViewModel.refreshSilently()`: re-fetch without touching
     * [CategoryUiState.isLoading] (no loading-flash on back-navigation), and
     * skip while a fetch is already in flight (covers both re-entrancy and
     * the redundant call right on top of `init`'s own [refresh]).
     */
    fun refreshSilently() {
        if (_uiState.value.isLoading) return
        viewModelScope.launch { fetchAndApply() }
    }

    /**
     * Post-launch fixes batch 3: a
     * [VaultRepository.VaultError.NotUnlocked] failure (auto-lock fired
     * while this screen was still open) never sets [CategoryUiState.errorMessage]
     * -- see [isVaultLocked]'s doc comment. `isLoading` is still cleared so
     * the screen doesn't stay stuck full-screen-loading underneath the
     * Unlock screen that `MainActivity`'s global lock-state effect is about
     * to push on top (triggered by [VaultRepository] itself having already
     * flipped `AuthSessionHolder.lockState` to `LOCKED`).
     */
    private suspend fun fetchAndApply() {
        val categoriesResult = repository.listCategories()
        val itemsResult = repository.listItems(categoryId)

        val categories = categoriesResult.getOrElse {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = if (it.isVaultLocked()) null else it.message ?: "Failed to load category",
            )
            return
        }
        val items = itemsResult.getOrElse {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = if (it.isVaultLocked()) null else it.message ?: "Failed to load items",
            )
            return
        }

        _uiState.value = _uiState.value.copy(
            isLoading = false,
            category = categories.firstOrNull { it.id == categoryId },
            allItems = items,
        )
    }

    fun onQueryChange(value: String) {
        _uiState.value = _uiState.value.copy(query = value)
    }
}
