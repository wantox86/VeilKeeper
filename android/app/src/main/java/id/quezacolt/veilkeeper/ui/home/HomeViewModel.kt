package id.quezacolt.veilkeeper.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.quezacolt.veilkeeper.data.Category
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.data.VaultRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val categories: List<Category> = emptyList(),
    val recentItems: List<DecryptedVaultItem> = emptyList(),
    val errorMessage: String? = null,
)

private const val RECENT_ITEMS_LIMIT = 5

/**
 * Backs the Home screen (SPEC-BASE.md Section 18.3): category tiles with
 * item counts + a "Recent" list of the most recently updated vault items
 * across all categories.
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

            _uiState.value = HomeUiState(
                isLoading = false,
                categories = categories,
                recentItems = items.take(RECENT_ITEMS_LIMIT),
            )
        }
    }
}
