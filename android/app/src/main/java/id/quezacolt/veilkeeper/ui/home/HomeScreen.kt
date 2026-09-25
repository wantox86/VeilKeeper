@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.R
import id.quezacolt.veilkeeper.data.Category
import id.quezacolt.veilkeeper.data.DecryptedVaultItem
import id.quezacolt.veilkeeper.ui.components.VeilKeeperEmptyState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperErrorState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperLoading
import id.quezacolt.veilkeeper.ui.components.VeilKeeperStateCrossfade
import id.quezacolt.veilkeeper.ui.components.SectionHeader
import id.quezacolt.veilkeeper.ui.theme.Spacing

private sealed interface HomeScreenState {
    data object Loading : HomeScreenState
    data class Error(val message: String) : HomeScreenState
    data object Content : HomeScreenState
}

/**
 * Home screen (SPEC-BASE.md Section 18.3; Phase 3 dashboard layout revamp):
 * a top bar (brand title + tagline, settings + lock actions), a local-only
 * search bar, a two-column "Categories" grid with an accent-bar tile per
 * category, a "New category" link, a "Recent" list, and a "+" FAB for quick
 * capture -- deliberately not a generic settings-style list.
 *
 * This is a visual-only refactor of the previous Home layout: every callback
 * that existed before ([onOpenCategory], [onOpenItem], [onAddItem],
 * [onOpenSettings]) is unchanged, plus two new ones needed for the revamp
 * ([onLockVault] for the top bar's lock icon, and category creation which is
 * wired straight to [HomeViewModel.createCategory] since that's purely a
 * repository call with no navigation involved).
 */
@Composable
fun HomeScreen(
    factory: ViewModelProvider.Factory,
    onOpenCategory: (Category) -> Unit,
    onOpenItem: (DecryptedVaultItem) -> Unit,
    /** Invoked with a default target category (the first available one) when the FAB is tapped. */
    onAddItem: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    /** Top bar's lock icon (revamp reference screenshot) -- locks the vault immediately, same effect as auto-lock (SPEC-BASE.md Section 24). */
    onLockVault: () -> Unit,
    viewModel: HomeViewModel = viewModel(factory = factory),
) {
    val state by viewModel.uiState.collectAsState()
    var showNewCategoryDialog by remember { mutableStateOf(false) }

    // Post-launch fix: Home previously only fetched data once (in
    // HomeViewModel's init), so newly added items never showed up after
    // navigating back from Add Item without a full app restart. Compose
    // Navigation keeps this composable's NavBackStackEntry (and therefore
    // this HomeViewModel) alive on the back stack while Add Item is on top,
    // so `init` never re-runs on its own -- re-fetching on every ON_RESUME
    // of this screen's lifecycle owner (fires on return from Add Item, and
    // on app foreground/backgrounded-then-resumed) is the simplest fix that
    // needs no new state-management library and matches the existing MVVM
    // shape (ViewModel owns the fetch, screen just reacts to lifecycle).
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

    val defaultCategoryId = state.categories.firstOrNull()?.id
    val screenState: HomeScreenState = when {
        state.isLoading -> HomeScreenState.Loading
        state.errorMessage != null -> HomeScreenState.Error(state.errorMessage ?: "Something went wrong")
        else -> HomeScreenState.Content
    }

    val settingsLabel = stringResource(R.string.cd_settings)
    val lockLabel = stringResource(R.string.cd_lock)
    val addItemLabel = stringResource(R.string.cd_add_item)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = settingsLabel)
                    }
                    IconButton(onClick = onLockVault) {
                        Icon(Icons.Filled.Lock, contentDescription = lockLabel)
                    }
                },
            )
        },
        floatingActionButton = {
            if (defaultCategoryId != null && screenState is HomeScreenState.Content) {
                FloatingActionButton(onClick = { onAddItem(defaultCategoryId) }) {
                    Icon(Icons.Filled.Add, contentDescription = addItemLabel)
                }
            }
        },
    ) { padding ->
        VeilKeeperStateCrossfade(targetState = screenState, modifier = Modifier.fillMaxSize()) { current ->
            when (current) {
                is HomeScreenState.Loading -> VeilKeeperLoading(modifier = Modifier.padding(padding), label = "Loading your vault…")
                is HomeScreenState.Error -> VeilKeeperErrorState(message = current.message, modifier = Modifier.padding(padding), onRetry = viewModel::refresh)
                is HomeScreenState.Content -> PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::onPullToRefresh,
                    modifier = Modifier.padding(padding),
                ) {
                    HomeContent(
                        padding = PaddingValues(0.dp),
                        categories = state.categories,
                        recentItems = state.recentItems,
                        searchQuery = state.searchQuery,
                        isSearching = state.isSearching,
                        searchResults = state.searchResults,
                        onSearchQueryChange = viewModel::onSearchQueryChange,
                        onOpenCategory = onOpenCategory,
                        onOpenItem = onOpenItem,
                        onNewCategory = { showNewCategoryDialog = true },
                    )
                }
            }
        }
    }

    if (showNewCategoryDialog) {
        NewCategoryDialog(
            onSubmit = { name -> viewModel.createCategory(name) },
            onDismiss = { showNewCategoryDialog = false },
        )
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
    onNewCategory: () -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
        item {
            // SPEC-BASE.md Section 18.3 / Phase 4: global search bar. Filters
            // over already-decrypted items in memory (VaultSearch) -- no
            // plaintext query is ever sent to the backend (Section 16). The
            // supporting text under the field states that explicitly (revamp
            // reference screenshot's "Local only — queries never leave this
            // device.").
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text(stringResource(R.string.home_search_placeholder)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                supportingText = { Text(stringResource(R.string.home_search_local_note)) },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
        }

        if (isSearching) {
            item {
                SectionHeader(stringResource(R.string.section_results))
                Spacer(Modifier.height(Spacing.sm))
            }
            if (searchResults.isEmpty()) {
                item {
                    VeilKeeperEmptyState(
                        icon = Icons.Filled.SearchOff,
                        title = stringResource(R.string.home_no_results_title),
                        message = stringResource(R.string.home_no_results_message, searchQuery),
                    )
                }
            } else {
                items(searchResults, key = { it.id }) { item ->
                    RecentItemRow(item = item, onClick = { onOpenItem(item) })
                }
            }
        } else {
            item {
                SectionHeader(stringResource(R.string.section_categories))
                Spacer(Modifier.height(Spacing.sm))
            }
            item {
                if (categories.isEmpty()) {
                    VeilKeeperEmptyState(
                        icon = Icons.Filled.CreateNewFolder,
                        title = stringResource(R.string.home_no_categories_title),
                        message = stringResource(R.string.home_no_categories_message),
                    )
                } else {
                    // Two-column grid with an accent bar per tile (revamp
                    // reference screenshot), replacing the previous single
                    // horizontally-scrolling row -- the category count for a
                    // homelab-scale vault is small, so a plain chunked Column
                    // is simplest (no new lazy-grid dependency needed).
                    Column {
                        categories.chunked(2).forEach { pair ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            ) {
                                pair.forEach { category ->
                                    CategoryTile(
                                        category = category,
                                        onClick = { onOpenCategory(category) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                            Spacer(Modifier.height(Spacing.sm))
                        }
                    }
                }
                Spacer(Modifier.height(Spacing.xs))
                TextButton(onClick = onNewCategory) {
                    Icon(
                        Icons.Filled.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    Text(stringResource(R.string.home_new_category))
                }
                Spacer(Modifier.height(Spacing.md))
            }
            item {
                SectionHeader(stringResource(R.string.section_recent))
                Spacer(Modifier.height(Spacing.sm))
            }
            if (recentItems.isEmpty()) {
                item {
                    VeilKeeperEmptyState(
                        icon = Icons.Filled.Inbox,
                        title = stringResource(R.string.home_empty_vault_title),
                        message = stringResource(R.string.home_empty_vault_message),
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

/** One category tile in the Home grid (revamp reference screenshot): a small accent bar top-left, the category name, and its item count. */
@Composable
private fun CategoryTile(category: Category, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(Spacing.md)) {
            Box(
                modifier = Modifier
                    .padding(bottom = Spacing.sm)
                    .width(22.dp)
                    .height(3.dp)
                    .alpha(0.9f),
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primary) {}
            }
            Text(
                category.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                if (category.itemCount == 1) "1 item" else "${category.itemCount} items",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RecentItemRow(item: DecryptedVaultItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs).clickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
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

/** "New category" dialog (revamp reference screenshot's folder-icon link) -- same shape as the create/rename dialogs already used elsewhere for categories. */
@Composable
private fun NewCategoryDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_category_dialog_title)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.category_name_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    onSubmit(name.trim())
                    onDismiss()
                },
                enabled = name.isNotBlank(),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}
