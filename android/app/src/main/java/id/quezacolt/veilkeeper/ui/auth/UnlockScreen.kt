@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package id.quezacolt.veilkeeper.ui.auth

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import id.quezacolt.veilkeeper.VeilKeeperApplication
import id.quezacolt.veilkeeper.data.VaultBiometricManager

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
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.height(48.dp))
            Spacer(Modifier.height(16.dp))
            Text("Vault locked", style = MaterialTheme.typography.headlineSmall)
            state.email?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(32.dp))

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))

            if (state.errorMessage != null) {
                Text(state.errorMessage!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(12.dp))
            }

            Button(onClick = viewModel::unlockWithPassword, modifier = Modifier.fillMaxWidth(), enabled = !state.isLoading) {
                if (state.isLoading) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                } else {
                    Text("Unlock")
                }
            }

            if (biometricAvailable && activity != null) {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { biometricManager.unlock(activity, viewModel::onBiometricResult) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text(" Use biometric")
                }
            }

            Spacer(Modifier.height(24.dp))
            TextButton(onClick = viewModel::logout) {
                Text("Not you? Log out")
            }
        }
    }
}
