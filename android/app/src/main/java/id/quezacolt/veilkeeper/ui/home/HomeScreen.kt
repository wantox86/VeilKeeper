@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.data.Category
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.ui.components.VeilKeeperEmptyState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperErrorState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperLoading
import id.quezacolt.veilkeeper.ui.components.VeilKeeperStateCrossfade
import id.quezacolt.veilkeeper.ui.theme.Spacing

private sealed interface HomeScreenState {
    data object Loading : HomeScreenState
    data class Error(val message: String) : HomeScreenState
    data object Content : HomeScreenState
}

/**
 * Home screen (SPEC-BASE.md Section 18.3): category tiles with item counts
 * + a "Recent" list, a global search bar, and a FAB for quick capture --
 * deliberately not a generic settings-style list.
 */
@Composable
fun HomeScreen(
    factory: ViewModelProvider.Factory,
    onOpenCategory: (Category) -> Unit,
    onOpenItem: (DecryptedVaultItem) -> Unit,
    /** Invoked with a default target category (the first available one) when the FAB is tapped. */
    onAddItem: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = factory),
) {
    val state by viewModel.uiState.collectAsState()
    val defaultCategoryId = state.categories.firstOrNull()?.id
    val screenState: HomeScreenState = when {
        state.isLoading -> HomeScreenState.Loading
        state.errorMessage != null -> HomeScreenState.Error(state.errorMessage ?: "Something went wrong")
        else -> HomeScreenState.Content
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VeilKeeper") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (defaultCategoryId != null && screenState is HomeScreenState.Content) {
                FloatingActionButton(onClick = { onAddItem(defaultCategoryId) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add item")
                }
            }
        },
    ) { padding ->
        VeilKeeperStateCrossfade(targetState = screenState, modifier = Modifier.fillMaxSize()) { current ->
            when (current) {
                is HomeScreenState.Loading -> VeilKeeperLoading(modifier = Modifier.padding(padding), label = "Loading your vault…")
                is HomeScreenState.Error -> VeilKeeperErrorState(message = current.message, modifier = Modifier.padding(padding), onRetry = viewModel::refresh)
                is HomeScreenState.Content -> HomeContent(
                    padding = padding,
                    categories = state.categories,
                    recentItems = state.recentItems,
                    searchQuery = state.searchQuery,
                    isSearching = state.isSearching,
                    searchResults = state.searchResults,
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    onOpenCategory = onOpenCategory,
                    onOpenItem = onOpenItem,
                )
            }
        }
    }
}

@Composable
private fun HomeContent(
    padding: PaddingValues,
    categories: List<Category>,
    recentItems: List<DecryptedVaultItem>,
    searchQuery: String,
    isSearching: Boolean,
    searchResults: List<DecryptedVaultItem>,
    onSearchQueryChange: (String) -> Unit,
    onOpenCategory: (Category) -> Unit,
    onOpenItem: (DecryptedVaultItem) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
        item {
            // SPEC-BASE.md Section 18.3 / Phase 4: global search bar. Filters
            // over already-decrypted items in memory (VaultSearch) -- no
            // plaintext query is ever sent to the backend (Section 16).
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search your vault…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))
        }

        if (isSearching) {
            item {
                Text("Results", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
            }
            if (searchResults.isEmpty()) {
                item {
                    VeilKeeperEmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = "No results",
                        message = "Nothing in your vault matches \"$searchQuery\".",
                    )
                }
            } else {
                items(searchResults, key = { it.id }) { item ->
                    RecentItemRow(item = item, onClick = { onOpenItem(item) })
                }
            }
        } else {
            item {
                Text("Categories", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
            }
            item {
                if (categories.isEmpty()) {
                    VeilKeeperEmptyState(
                        icon = Icons.Filled.FolderOff,
                        title = "No categories yet",
                        message = "Categories help organize your vault -- they're created automatically on registration.",
                    )
                } else {
                    LazyRow {
                        items(categories, key = { it.id }) { category ->
                            CategoryTile(category = category, onClick = { onOpenCategory(category) })
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.lg))
            }
            item {
                Text("Recent", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(Spacing.sm))
            }
            if (recentItems.isEmpty()) {
                item {
                    VeilKeeperEmptyState(
                        icon = Icons.Filled.Lock,
                        title = "Your vault is empty",
                        message = "Tap the + button to add your first secret.",
                    )
                }
            } else {
                items(recentItems, key = { it.id }) { item ->
                    RecentItemRow(item = item, onClick = { onOpenItem(item) })
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(category: Category, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(132.dp)
            .padding(end = Spacing.sm, bottom = Spacing.sm),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                category.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                "${category.itemCount}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                if (category.itemCount == 1) "item" else "items",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentItemRow(item: DecryptedVaultItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(modifier = Modifier.padding(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(Spacing.sm).size(16.dp),
                )
            }
            Spacer(Modifier.width(Spacing.sm))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    item.title.ifBlank { "(untitled)" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
