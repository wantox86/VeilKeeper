@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.vault

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.data.ImageCompressor
import id.quezacolt.veilkeeper.ui.theme.Spacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BLOCK_TYPES = listOf("text", "secret", "note", "image")

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

@Composable
private fun ContentPreviewRow(primary: String, secondary: String, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(primary, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(secondary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = "Remove $primary")
            }
        }
    }
}

@Composable
private fun AddBlockRow(
    onAdd: (type: String, label: String?, value: String) -> Unit,
    onAddImage: (filename: String, mimeType: String, bytes: ByteArray) -> Unit,
) {
    var selectedType by remember { mutableStateOf("text") }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var isCompressing by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ActivityResultContracts.GetContent() (not PickVisualMedia) is used
    // because it works on minSdk 26 (this app's floor) without a
    // Google-Play-Services-adjacent dependency -- PickVisualMedia's photo
    // picker is a Play-services-backed system UI on older API levels,
    // GetContent is a plain framework contract available since API 19.
    val pickImageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        isCompressing = true
        scope.launch {
            val filename = queryDisplayName(context.contentResolver, uri) ?: "image.jpg"
            val compressed = withContext(Dispatchers.IO) { ImageCompressor.compress(context.contentResolver, uri) }
            isCompressing = false
            if (compressed != null) {
                onAddImage(filename, ImageCompressor.OUTPUT_MIME_TYPE, compressed)
            }
        }
    }

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            BLOCK_TYPES.forEach { type ->
                FilterChip(
                    selected = selectedType == type,
                    onClick = { selectedType = type },
                    label = { Text(type.replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.padding(end = Spacing.sm),
                )
            }
        }
        Spacer(Modifier.height(Spacing.sm))

        if (selectedType == "image") {
            // Image blocks skip the label/value fields entirely -- the flow
            // is pick -> compress -> encrypt-on-save (SPEC-BASE.md Section
            // 17), there is no free-text "value" to type for an image.
            OutlinedButton(
                onClick = { pickImageLauncher.launch("image/*") },
                enabled = !isCompressing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isCompressing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp).padding(end = Spacing.sm))
                    Text("Compressing…")
                } else {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.padding(end = Spacing.sm))
                    Text("Pick image")
                }
            }
            return@Column
        }

        if (selectedType != "note") {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (e.g. Username, Token)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.sm))
        }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(if (selectedType == "note") "Note" else "Value") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                onAdd(selectedType, label.takeIf { selectedType != "note" }, value)
                label = ""
                value = ""
            },
            modifier = Modifier.padding(top = Spacing.sm),
        ) {
            Text("Add block")
        }
    }
}

/** Best-effort lookup of a content Uri's display filename via [OpenableColumns.DISPLAY_NAME]; null if unavailable. */
private fun queryDisplayName(resolver: android.content.ContentResolver, uri: Uri): String? {
    val cursor = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
    return cursor.use {
        if (it.moveToFirst()) {
            val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) it.getString(index) else null
        } else {
            null
        }
    }
}
