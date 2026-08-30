@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.vault

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.crypto.ContentBlockDto

/**
 * Vault Detail screen (SPEC-BASE.md Section 20): a secure-notebook-style
 * list of content blocks. Secrets are hidden by default (Section 22) and
 * revealed per-block; copy uses the platform clipboard (auto-clear timer is
 * Sprint 3 "Clipboard Security" scope, SPEC-BASE.md Section 23 -- not built
 * here to avoid scope creep beyond Sprint 2's "Vault Foundation").
 */
@Composable
fun VaultDetailScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: VaultDetailViewModel = viewModel(factory = factory),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.item?.title ?: "Vault Item") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.item != null) {
                        IconButton(onClick = viewModel::delete) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete item")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.errorMessage != null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(state.errorMessage ?: "Something went wrong", color = MaterialTheme.colorScheme.error)
            }
            state.item != null -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                items(state.item!!.content) { block ->
                    ContentBlockCard(block)
                }
            }
        }
    }
}

@Composable
private fun ContentBlockCard(block: ContentBlockDto) {
    val clipboard = LocalClipboardManager.current
    var revealed by remember(block) { mutableStateOf(false) }
    val isSecret = block.type == "secret"

    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(modifier = Modifier.padding(16.dp)) {
            block.label?.let {
                Text(it, style = MaterialTheme.typography.labelMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (isSecret && !revealed) "•".repeat(minOf(block.value.length, 16).coerceAtLeast(8)) else block.value,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                if (isSecret) {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "Hide" else "Show",
                        )
                    }
                }
                IconButton(onClick = { clipboard.setText(AnnotatedString(block.value)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy")
                }
            }
        }
    }
}
