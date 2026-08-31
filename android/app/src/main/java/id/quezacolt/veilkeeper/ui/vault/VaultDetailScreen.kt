@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.vault

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.VeilKeeperApplication
import id.quezacolt.veilkeeper.crypto.ContentBlockDto
import id.quezacolt.veilkeeper.ui.components.VeilKeeperConfirmDeleteDialog
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
 *
 * Post-launch fixes batch 2: item #4 adds an edit mode (Edit button ->
 * title + add/remove/change blocks, reusing Add Item's
 * `AddBlockRow`/`ContentPreviewRow` -- see `ContentBlockEditingComponents.kt`
 * -- -> Save re-encrypts and PUTs via the existing `VaultRepository.updateItem`);
 * item #2 wraps the Delete Item action (and, in edit mode, removing an
 * "image" block -- a real, immediate attachment delete, see
 * `VaultDetailViewModel.removeEditBlock`'s doc comment for why that one
 * specifically needs confirmation and plain draft-block removal doesn't) in
 * [VeilKeeperConfirmDeleteDialog].
 */
@Composable
fun VaultDetailScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: VaultDetailViewModel = viewModel(factory = factory),
) {
    val state by viewModel.uiState.collectAsState()
    var showDeleteItemDialog by remember { mutableStateOf(false) }
    // Index into state.editBlocks of an image block pending a confirmed
    // removal (Post-launch fixes batch 2, item #2) -- null means no dialog
    // is showing. Only image-block removal goes through this (it's a real,
    // immediate server-side attachment delete); text/secret/note block
    // removal in the edit draft is unconfirmed, same as Add Item.
    var pendingRemoveImageIndex by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.deleted) {
        if (state.deleted) onDeleted()
    }

    val screenState: DetailScreenState = when {
        state.isLoading -> DetailScreenState.Loading
        state.errorMessage != null -> DetailScreenState.Error(state.errorMessage ?: "Something went wrong")
        state.item != null -> DetailScreenState.Content(state.item!!)
        else -> DetailScreenState.Loading
    }

    if (showDeleteItemDialog) {
        VeilKeeperConfirmDeleteDialog(
            title = "Delete this item?",
            message = "This permanently deletes \"${state.item?.title?.ifBlank { "(untitled)" } ?: "this item"}\" and all its content, including any attachments. This cannot be undone.",
            onConfirm = {
                showDeleteItemDialog = false
                viewModel.delete()
            },
            onDismiss = { showDeleteItemDialog = false },
        )
    }

    pendingRemoveImageIndex?.let { index ->
        VeilKeeperConfirmDeleteDialog(
            title = "Delete this attachment?",
            message = "This permanently deletes the attached image. This cannot be undone.",
            onConfirm = {
                pendingRemoveImageIndex = null
                viewModel.removeEditBlock(index)
            },
            onDismiss = { pendingRemoveImageIndex = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.isEditing) "Edit item" else state.item?.title?.ifBlank { "(untitled)" } ?: "Vault Item",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (state.isEditing) viewModel.cancelEdit() else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.item != null) {
                        if (state.isEditing) {
                            IconButton(onClick = viewModel::saveEdit, enabled = !state.isSavingEdit) {
                                Icon(Icons.Filled.Check, contentDescription = "Save changes")
                            }
                        } else {
                            IconButton(onClick = viewModel::startEdit) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit item")
                            }
                            IconButton(onClick = { showDeleteItemDialog = true }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete item")
                            }
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
                is DetailScreenState.Content -> if (state.isEditing) {
                    VaultDetailEditContent(
                        padding = padding,
                        state = state,
                        onTitleChange = viewModel::onEditTitleChange,
                        onAddBlock = viewModel::addEditBlock,
                        onAddImageBlock = viewModel::addEditImageBlock,
                        onRemoveBlock = { index, block ->
                            if (block.type == "image") pendingRemoveImageIndex = index else viewModel.removeEditBlock(index)
                        },
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
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
}

@Composable
private fun VaultDetailEditContent(
    padding: androidx.compose.foundation.layout.PaddingValues,
    state: VaultDetailUiState,
    onTitleChange: (String) -> Unit,
    onAddBlock: (type: String, label: String?, value: String) -> Unit,
    onAddImageBlock: (filename: String, mimeType: String, bytes: ByteArray) -> Unit,
    onRemoveBlock: (index: Int, block: ContentBlockDto) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.md)) {
        OutlinedTextField(
            value = state.editTitle,
            onValueChange = onTitleChange,
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(Spacing.md))

        AddBlockRow(onAdd = onAddBlock, onAddImage = onAddImageBlock)
        Spacer(Modifier.height(Spacing.md))

        Text("Content", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(Spacing.xs))
        if (state.editBlocks.isEmpty()) {
            Text(
                "Nothing here yet -- pick a type above and add a block.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Spacing.sm),
            )
        }
        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
            itemsIndexed(state.editBlocks) { index, block ->
                ContentPreviewRow(
                    primary = block.label?.takeIf { it.isNotBlank() } ?: block.type.replaceFirstChar { it.uppercase() },
                    secondary = if (block.type == "secret") "•".repeat(minOf(block.value.length, 12).coerceAtLeast(6)) else block.value,
                    onRemove = { onRemoveBlock(index, block) },
                )
            }
        }

        state.editErrorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .padding(top = Spacing.sm)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }

        if (state.isSavingEdit) {
            Spacer(Modifier.height(Spacing.sm))
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
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
            Box(
                modifier = Modifier.fillMaxWidth().height(200.dp).clipToBounds(),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    attachmentId == null -> BrokenImagePlaceholder("Invalid attachment reference")
                    attachmentState == null || attachmentState is AttachmentImageState.Loading -> CircularProgressIndicator()
                    attachmentState is AttachmentImageState.Error -> BrokenImagePlaceholder(attachmentState.message)
                    attachmentState is AttachmentImageState.Loaded -> {
                        val bitmap = remember(attachmentState) {
                            BitmapFactory.decodeByteArray(attachmentState.bytes, 0, attachmentState.bytes.size)
                        }
                        if (bitmap != null) {
                            ZoomableAttachmentImage(bitmap = bitmap.asImageBitmap(), contentDescription = block.label ?: "Attachment image")
                        } else {
                            BrokenImagePlaceholder("Could not decode image")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Post-launch fix: pinch-to-zoom + pan for the attachment preview
 * (SPEC-BASE.md Section 20). A single [detectTransformGestures] pointer
 * handler drives both scale and pan together from the same multi-touch
 * gesture stream, rather than pairing it with a separate single-finger
 * `draggable`/pan-only detector -- two independent gesture detectors on the
 * same composable is exactly the pattern that caused a real pinch/pan
 * conflict bug in an unrelated project (signPdf's pinch-to-resize), because
 * competing detectors can each partially consume the same pointer events.
 * `detectTransformGestures` reports pan/zoom/rotation as one combined
 * per-frame delta, so there is nothing to arbitrate between here.
 *
 * Scale is clamped to `1f..5f` (1f = fit, matching the card's default
 * un-zoomed state). Panning only takes effect once zoomed in
 * (`scale > 1f`); zooming back out to `1f` snaps the pan offset back to
 * zero so the image doesn't stay off-center the next time the card is
 * re-zoomed. `Modifier.clipToBounds()` on the parent [Box] keeps the zoomed
 * image contained within the existing 200dp preview area rather than
 * bleeding into neighboring content blocks.
 */
@Composable
private fun ZoomableAttachmentImage(bitmap: androidx.compose.ui.graphics.ImageBitmap, contentDescription: String?) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 5f)
                    scale = newScale
                    offset = if (newScale > 1f) offset + pan else Offset.Zero
                }
            }
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offset.x,
                translationY = offset.y,
            ),
    )
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
