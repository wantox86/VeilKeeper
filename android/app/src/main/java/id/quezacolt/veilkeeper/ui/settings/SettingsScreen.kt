@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.VeilKeeperApplication
import id.quezacolt.veilkeeper.data.AutoLockTimeout
import id.quezacolt.veilkeeper.data.ClipboardClearDelay

/**
 * Minimal Settings screen (Sprint 3 scope item 6): auto-lock timeout
 * (SPEC-BASE.md Section 24), clipboard auto-clear delay (Section 23), the
 * biometric unlock toggle (Section 25), and logout. Deliberately not more
 * than this -- no account/profile/theme settings, per Section 56 Rule 1.
 */
@Composable
fun SettingsScreen(
    factory: ViewModelProvider.Factory,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = factory),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as VeilKeeperApplication
    val biometricManager = remember { app.vaultBiometricManager }
    val activity = context as? androidx.fragment.app.FragmentActivity

    // Logout navigation is handled globally by MainActivity's
    // AuthSessionHolder.lockState observer -- state.loggedOut here just
    // reflects that the logout call was made (used by tests / to disable
    // double-tapping the button if this screen re-composes before nav happens).

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding).padding(16.dp)) {
            Text("Auto Lock", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            AutoLockTimeout.entries.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = state.autoLockTimeout == option,
                        onClick = { viewModel.setAutoLockTimeout(option) },
                    )
                    Text(option.label)
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Text("Clipboard auto-clear", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            ClipboardClearDelay.entries.forEach { option ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    RadioButton(
                        selected = state.clipboardClearDelay == option,
                        onClick = { viewModel.setClipboardClearDelay(option) },
                    )
                    Text(option.label)
                }
            }

            Spacer(Modifier.height(16.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            Text("Biometric unlock", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            if (!state.biometricAvailableOnDevice) {
                Text(
                    "No biometric enrolled on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Unlock with biometrics", modifier = Modifier.weight(1f))
                    Switch(
                        checked = state.biometricEnabled,
                        onCheckedChange = { enable ->
                            if (enable) {
                                val vdk = viewModel.currentVdkForEnrollment()
                                if (vdk != null && activity != null) {
                                    biometricManager.enroll(activity, vdk, viewModel::onBiometricEnrollResult)
                                }
                            } else {
                                viewModel.disableBiometric()
                            }
                        },
                    )
                }
            }

            if (state.errorMessage != null) {
                Spacer(Modifier.height(8.dp))
                Text(state.errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(24.dp))
            Divider()
            Spacer(Modifier.height(24.dp))

            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
                Text("Log out")
            }
        }
    }
}
