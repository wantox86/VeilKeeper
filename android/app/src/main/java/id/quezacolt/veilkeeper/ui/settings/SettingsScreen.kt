@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.VeilKeeperApplication
import id.quezacolt.veilkeeper.data.AutoLockTimeout
import id.quezacolt.veilkeeper.data.ClipboardClearDelay
import id.quezacolt.veilkeeper.ui.theme.Spacing

/**
 * Minimal Settings screen (Sprint 3 scope item 6): auto-lock timeout
 * (SPEC-BASE.md Section 24), clipboard auto-clear delay (Section 23), the
 * biometric unlock toggle (Section 25), and logout. Deliberately not more
 * than this -- no account/profile/theme settings, per Section 56 Rule 1
 * (dark/light mode itself follows the system setting automatically, per
 * Phase 6's Theme.kt -- no separate in-app theme toggle is added here, that
 * would be a new feature outside this sprint's polish-only scope).
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding).padding(Spacing.md)) {
            SettingsSectionTitle("Auto Lock")
            AutoLockTimeout.entries.forEach { option ->
                SettingsRadioRow(
                    label = option.label,
                    selected = state.autoLockTimeout == option,
                    onClick = { viewModel.setAutoLockTimeout(option) },
                )
            }

            SettingsDivider()

            SettingsSectionTitle("Clipboard auto-clear")
            ClipboardClearDelay.entries.forEach { option ->
                SettingsRadioRow(
                    label = option.label,
                    selected = state.clipboardClearDelay == option,
                    onClick = { viewModel.setClipboardClearDelay(option) },
                )
            }

            SettingsDivider()

            SettingsSectionTitle("Biometric unlock")
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
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    state.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            SettingsDivider(topPadding = Spacing.lg)

            OutlinedButton(onClick = viewModel::logout, modifier = Modifier.fillMaxWidth()) {
                Text("Log out")
            }
        }
    }
}

@Composable
private fun SettingsSectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(Spacing.sm))
}

@Composable
private fun SettingsDivider(topPadding: androidx.compose.ui.unit.Dp = Spacing.md) {
    Spacer(Modifier.height(topPadding))
    HorizontalDivider()
    Spacer(Modifier.height(Spacing.md))
}

/** A radio row that is entirely tappable (the row's [selectable] modifier, not just the small radio glyph) -- Section 27's accessibility ask for adequate touch targets. */
@Composable
private fun SettingsRadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, onClick = onClick),
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(Spacing.sm))
        Text(label)
    }
}
