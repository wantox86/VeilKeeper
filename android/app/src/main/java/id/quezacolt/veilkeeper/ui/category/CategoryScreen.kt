@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.category

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
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
import id.quezacolt.veilkeeper.data.DecryptedVaultItem

/** Category screen (SPEC-BASE.md Section 19): item list, search/filter, add item. */
@Composable
fun CategoryScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onOpenItem: (DecryptedVaultItem) -> Unit,
    onAddItem: () -> Unit,
    viewModel: CategoryViewModel = viewModel(factory = factory),
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.category?.name ?: "Category") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAddItem) {
                Icon(Icons.Filled.Add, contentDescription = "Add item")
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
            else -> CategoryContent(padding, state, viewModel::onQueryChange, onOpenItem)
        }
    }
}

@Composable
private fun CategoryContent(
    padding: PaddingValues,
    state: CategoryUiState,
    onQueryChange: (String) -> Unit,
    onOpenItem: (DecryptedVaultItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            label = { Text("Search this category...") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val visible = state.visibleItems
        if (visible.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (state.allItems.isEmpty()) "No items in this category yet." else "No items match your search.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(top = 12.dp)) {
                items(visible) { item ->
                    VaultItemRow(item = item, onClick = { onOpenItem(item) })
                }
            }
        }
    }
}

@Composable
private fun VaultItemRow(item: DecryptedVaultItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(item.title.ifBlank { "(untitled)" }, style = MaterialTheme.typography.titleSmall)
            Text(item.preview, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(item.updatedAt, style = MaterialTheme.typography.labelSmall)
        }
    }
}
