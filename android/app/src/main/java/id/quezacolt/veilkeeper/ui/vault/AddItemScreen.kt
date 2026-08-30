@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.vault

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel

private val BLOCK_TYPES = listOf("text", "secret", "note")

/**
 * Add Item screen (SPEC-BASE.md Section 21): a title field plus a fast,
 * chat-like flow for adding text/secret/note blocks -- deliberately not a
 * long password-manager-style form.
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
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = state.title,
                onValueChange = viewModel::onTitleChange,
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            AddBlockRow(onAdd = viewModel::addBlock)
            Spacer(Modifier.height(16.dp))

            Text("Content", style = MaterialTheme.typography.titleSmall)
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(state.blocks) { index, block ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text("${block.type}${block.label?.let { ": $it" } ?: ""} — ${block.value}", modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.removeBlock(index) }) {
                            Icon(Icons.Filled.Close, contentDescription = "Remove")
                        }
                    }
                }
            }

            state.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                onClick = viewModel::save,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                }
                Text("Save")
            }
        }
    }
}

@Composable
private fun AddBlockRow(onAdd: (type: String, label: String?, value: String) -> Unit) {
    var selectedType by remember { mutableStateOf("text") }
    var label by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    Column {
        Row(modifier = Modifier.fillMaxWidth()) {
            BLOCK_TYPES.forEach { type ->
                AssistChip(
                    onClick = { selectedType = type },
                    label = { Text(type.replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (selectedType != "note") {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Label (e.g. Username, Token)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
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
            modifier = Modifier.padding(top = 8.dp),
        ) {
            Text("Add block")
        }
    }
}
