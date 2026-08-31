@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.VeilKeeperApplication
import id.quezacolt.veilkeeper.ui.theme.Spacing

/**
 * Shown when [id.quezacolt.veilkeeper.data.AuthSessionHolder]'s lock state
 * is `LOCKED` (SPEC-BASE.md Section 24 auto-lock fired). Offers biometric
 * unlock (Section 25, if enrolled and available) and always offers password
 * unlock as a fallback -- both restore the VDK without any network call.
 */
@Composable
fun UnlockScreen(
    factory: ViewModelProvider.Factory,
    viewModel: UnlockViewModel = viewModel(factory = factory),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val app = context.applicationContext as VeilKeeperApplication
    val biometricManager = remember { app.vaultBiometricManager }
    val biometricAvailable = remember { biometricManager.isEnabled() && biometricManager.isAvailable(context) }
    val activity = context as? androidx.fragment.app.FragmentActivity

    // Offer biometric immediately when the screen appears -- standard UX for
    // "re-open the app -> immediately see the fingerprint prompt" rather than
    // requiring an extra tap every time.
    LaunchedEffect(Unit) {
        if (biometricAvailable && activity != null) {
            biometricManager.unlock(activity, viewModel::onBiometricResult)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(Spacing.xxl))
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(Spacing.md).size(32.dp),
                )
            }
            Spacer(Modifier.height(Spacing.md))
            Text("Vault locked", style = MaterialTheme.typography.headlineSmall)
            state.email?.let {
                Spacer(Modifier.height(Spacing.xs))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(Spacing.xl))

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(Spacing.sm))

            if (state.errorMessage != null) {
                Text(
                    state.errorMessage!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
                Spacer(Modifier.height(Spacing.sm))
            }

            Button(onClick = viewModel::unlockWithPassword, modifier = Modifier.fillMaxWidth(), enabled = !state.isLoading) {
                if (state.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                } else {
                    Text("Unlock")
                }
            }

            if (biometricAvailable && activity != null) {
                Spacer(Modifier.height(Spacing.sm))
                OutlinedButton(
                    onClick = { biometricManager.unlock(activity, viewModel::onBiometricResult) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("Use biometric")
                }
            }

            Spacer(Modifier.height(Spacing.lg))
            TextButton(onClick = viewModel::logout) {
                Text("Not you? Log out")
            }
        }
    }
}
