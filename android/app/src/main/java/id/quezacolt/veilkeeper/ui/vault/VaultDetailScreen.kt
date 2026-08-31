@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.vault

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.VeilKeeperApplication
import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.ui.components.VeilKeeperErrorState
import id.quezacolt.veilkeeper.ui.components.VeilKeeperLoading
import id.quezacolt.veilkeeper.ui.components.VeilKeeperStateCrossfade
import id.quezacolt.veilkeeper.ui.theme.Spacing

private sealed interface DetailScreenState {
    data object Loading : DetailScreenState
    data class Error(val message: String) : DetailScreenState
    data class Content(val item: id.quezacolt.veilkeeper.data.DecryptedVaultItem) : DetailScreenState
}

/**
 * Vault Detail screen (SPEC-BASE.md Section 20): a secure-notebook-style
 * list of content blocks. Secrets are hidden by default (Section 22) and
 * revealed per-block; copy uses [id.quezacolt.veilkeeper.data.ClipboardSecurity]
 * (Sprint 3, SPEC-BASE.md Section 23) rather than the raw platform clipboard
 * -- marks the clip sensitive where the OS supports it and auto-clears it
 * after the user-configured delay (Settings screen). Sprint 5: "image"
 * blocks render as an [AttachmentImageCard] instead of a text row (Section
 * 20's mockup: "Screenshot [encrypted image preview]").
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

    val screenState: DetailScreenState = when {
        state.isLoading -> DetailScreenState.Loading
        state.errorMessage != null -> DetailScreenState.Error(state.errorMessage ?: "Something went wrong")
        state.item != null -> DetailScreenState.Content(state.item!!)
        else -> DetailScreenState.Loading
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        state.item?.title?.ifBlank { "(untitled)" } ?: "Vault Item",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        VeilKeeperStateCrossfade(targetState = screenState, modifier = Modifier.fillMaxSize()) { current ->
            when (current) {
                is DetailScreenState.Loading -> VeilKeeperLoading(modifier = Modifier.padding(padding))
                is DetailScreenState.Error -> VeilKeeperErrorState(message = current.message, modifier = Modifier.padding(padding), onRetry = viewModel::refresh)
                is DetailScreenState.Content -> LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
                    items(current.item.content) { block ->
                        if (block.type == "image") {
                            AttachmentImageCard(block, state.attachmentImages[block.value.toLongOrNull()], onLoad = viewModel::loadAttachmentImage)
                        } else {
                            ContentBlockCard(block)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentBlockCard(block: ContentBlockDto) {
    val context = LocalContext.current
    val app = context.applicationContext as VeilKeeperApplication
    var revealed by remember(block) { mutableStateOf(false) }
    val isSecret = block.type == "secret"

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            block.label?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.xs))
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
                            contentDescription = if (revealed) "Hide value" else "Show value",
                        )
                    }
                }
                IconButton(
                    onClick = {
                        // SPEC-BASE.md Section 23: every copy from this screen is
                        // treated as sensitive (secret AND non-secret text blocks
                        // alike live in a zero-knowledge vault), so it always goes
                        // through the auto-clear path, not just for `isSecret`.
                        val delayMillis = app.settingsRepository.clipboardClearDelay.value.millis
                        app.clipboardSecurity.copyAndScheduleClear(
                            label = block.label ?: "VeilKeeper",
                            value = block.value,
                            clearAfterMillis = delayMillis,
                        )
                    },
                ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copy ${block.label ?: "value"}")
                }
            }
        }
    }
}

/**
 * Renders an "image" content block (SPEC-BASE.md Section 20's mockup:
 * "Screenshot [encrypted image preview]"). [attachmentState] is looked up by
 * the caller from [VaultDetailUiState.attachmentImages] -- null means "not
 * requested yet", which triggers [onLoad] via [LaunchedEffect] exactly once
 * per block (keyed on the block itself, so it re-triggers only if the block
 * identity actually changes, e.g. a fresh [id.quezacolt.veilkeeper.data.DecryptedVaultItem]
 * after [id.quezacolt.veilkeeper.ui.vault.VaultDetailViewModel.refresh]).
 *
 * Decodes the decrypted bytes with [BitmapFactory] purely for local
 * rendering -- this never touches the network or disk again once decrypted
 * (matches FLAG_SECURE's existing app-wide screenshot protection, Sprint 3).
 */
@Composable
private fun AttachmentImageCard(block: ContentBlockDto, attachmentState: AttachmentImageState?, onLoad: (attachmentId: Long) -> Unit) {
    val attachmentId = block.value.toLongOrNull()

    LaunchedEffect(block) {
        if (attachmentId != null) onLoad(attachmentId)
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            block.label?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(Spacing.xs))
            }
            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                when {
                    attachmentId == null -> BrokenImagePlaceholder("Invalid attachment reference")
                    attachmentState == null || attachmentState is AttachmentImageState.Loading -> CircularProgressIndicator()
                    attachmentState is AttachmentImageState.Error -> BrokenImagePlaceholder(attachmentState.message)
                    attachmentState is AttachmentImageState.Loaded -> {
                        val bitmap = remember(attachmentState) {
                            BitmapFactory.decodeByteArray(attachmentState.bytes, 0, attachmentState.bytes.size)
                        }
                        if (bitmap != null) {
                            Image(bitmap = bitmap.asImageBitmap(), contentDescription = block.label ?: "Attachment image")
                        } else {
                            BrokenImagePlaceholder("Could not decode image")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BrokenImagePlaceholder(message: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.Filled.BrokenImage,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
