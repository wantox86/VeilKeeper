package id.quezacolt.veilkeeper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import id.quezacolt.veilkeeper.ui.theme.VeilKeeperTheme

/**
 * Sprint 0 skeleton entrypoint.
 *
 * This is intentionally NOT the real Home/Login screen from SPEC-BASE.md
 * Section 18 -- vault/auth UI lands in Sprint 1+. This screen only proves
 * the app compiles, launches, and renders Compose + Material 3 correctly.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VeilKeeperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BootstrapScreen()
                }
            }
        }
    }
}

@Composable
fun BootstrapScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Veil Keepers",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Keep your secrets behind the veil.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun BootstrapScreenPreview() {
    VeilKeeperTheme {
        BootstrapScreen()
    }
}
