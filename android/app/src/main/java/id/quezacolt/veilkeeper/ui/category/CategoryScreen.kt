@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.category

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.ui.components.VeilKeeperEmptyState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperErrorState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperLoading
import id.quezacolt.veilkeeper.ui.components.VeilKeeperStateCrossfade
import id.quezacolt.veilkeeper.ui.theme.Spacing

private sealed interface CategoryScreenState {
    data object Loading : CategoryScreenState
    data class Error(val message: String) : CategoryScreenState
    data object Content : CategoryScreenState
}

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

    // Post-launch fixes batch 2, item #3: same fix as HomeScreen got in
    // batch 1 -- re-fetch on every ON_RESUME (covers "navigated back from
    // Add Item after saving" and "app resumed from background") so newly
    // added items show up without leaving and reopening the app. See
    // CategoryViewModel.refreshSilently()'s doc comment for the root cause.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshSilently()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val screenState: CategoryScreenState = when {
        state.isLoading -> CategoryScreenState.Loading
        state.errorMessage != null -> CategoryScreenState.Error(state.errorMessage ?: "Something went wrong")
        else -> CategoryScreenState.Content
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.category?.name ?: "Category",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            if (screenState is CategoryScreenState.Content) {
                FloatingActionButton(onClick = onAddItem) {
                    Icon(Icons.Filled.Add, contentDescription = "Add item")
                }
            }
        },
    ) { padding ->
        VeilKeeperStateCrossfade(targetState = screenState, modifier = Modifier.fillMaxSize()) { current ->
            when (current) {
                is CategoryScreenState.Loading -> VeilKeeperLoading(modifier = Modifier.padding(padding))
                is CategoryScreenState.Error -> VeilKeeperErrorState(message = current.message, modifier = Modifier.padding(padding), onRetry = viewModel::refresh)
                is CategoryScreenState.Content -> CategoryContent(padding, state, viewModel::onQueryChange, onOpenItem)
            }
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
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search this category…") },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth(),
        )

        val visible = state.visibleItems
        if (visible.isEmpty()) {
            if (state.allItems.isEmpty()) {
                VeilKeeperEmptyState(
                    icon = Icons.Filled.Inbox,
                    title = "No items yet",
                    message = "Add your first secret to \"${state.category?.name ?: "this category"}\".",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                VeilKeeperEmptyState(
                    icon = Icons.Filled.SearchOff,
                    title = "No matches",
                    message = "Nothing in this category matches \"${state.query}\".",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(top = Spacing.md)) {
                items(visible, key = { it.id }) { item ->
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
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                item.title.ifBlank { "(untitled)" },
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.preview,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                item.updatedAt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
