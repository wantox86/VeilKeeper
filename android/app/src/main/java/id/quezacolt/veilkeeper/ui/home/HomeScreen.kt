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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.data.Category
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import androidx.compose.material.icons.filled.Search

/**
 * Home screen (SPEC-BASE.md Section 18.3): category tiles with item counts
 * + a "Recent" list. Deliberately not a generic settings-style list --
 * category tiles in a grid-like row, a distinct "Recent" section below.
 * Full commercial-grade visual polish (Section 27) is left for a later
 * design pass; this focuses on the Sprint 2 functional flow.
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
            if (defaultCategoryId != null) {
                FloatingActionButton(onClick = { onAddItem(defaultCategoryId) }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add item")
                }
            }
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.errorMessage != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(state.errorMessage ?: "Something went wrong", color = MaterialTheme.colorScheme.error)
            }
            else -> HomeContent(
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
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        item {
            // SPEC-BASE.md Section 18.3 / Phase 4: global search bar. Filters
            // over already-decrypted items in memory (VaultSearch) -- no
            // plaintext query is ever sent to the backend (Section 16).
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                label = { Text("Search your vault...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
        }

        if (isSearching) {
            item {
                Text("Results", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            if (searchResults.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Text("No results for \"$searchQuery\".", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(searchResults) { item ->
                    RecentItemRow(item = item, onClick = { onOpenItem(item) })
                }
            }
        } else {
            item {
                Text("Categories", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            item {
                if (categories.isEmpty()) {
                    Text("No categories yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    LazyRow {
                        items(categories) { category ->
                            CategoryTile(category = category, onClick = { onOpenCategory(category) })
                            Spacer(Modifier.height(0.dp))
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            item {
                Text("Recent", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
            }
            if (recentItems.isEmpty()) {
                item {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                        Spacer(Modifier.height(8.dp))
                        Text("Your vault is empty. Tap + to add your first secret.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(recentItems) { item ->
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
        modifier = Modifier.padding(end = 12.dp, bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(category.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("${category.itemCount}", style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
private fun RecentItemRow(item: DecryptedVaultItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(item.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.titleSmall)
                Text(item.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
    }
}
