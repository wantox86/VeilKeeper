@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.ui.theme.Spacing

/**
 * Add Item screen (SPEC-BASE.md Section 21): a title field plus a fast,
 * chat-like flow for adding text/secret/note/image content -- deliberately
 * not a long password-manager-style form.
 */
@Composable
fun AddItemScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: AddItemViewModel = viewModel(factory = factory),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saved) {
        if (state.saved) onSaved()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Add content") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.md))

            AddBlockRow(onAdd = viewModel::addBlock, onAddImage = viewModel::addPendingImage)
            Spacer(Modifier.height(Spacing.md))

            Text("Content", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(Spacing.xs))
            if (state.blocks.isEmpty() && state.pendingImages.isEmpty()) {
                Text(
                    "Nothing added yet -- pick a type above and add a block.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
            }
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(state.blocks) { index, block ->
                    ContentPreviewRow(
                        primary = block.label?.takeIf { it.isNotBlank() } ?: block.type.replaceFirstChar { it.uppercase() },
                        secondary = if (block.type == "secret") "•".repeat(minOf(block.value.length, 12).coerceAtLeast(6)) else block.value,
                        onRemove = { viewModel.removeBlock(index) },
                    )
                }
                itemsIndexed(state.pendingImages) { index, image ->
                    ContentPreviewRow(
                        primary = image.filename,
                        secondary = "${image.bytes.size / 1024} KB -- will upload on save",
                        onRemove = { viewModel.removePendingImage(index) },
                    )
                }
            }

            state.errorMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = Spacing.sm),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text("Save")
            }
        }
    }
}

