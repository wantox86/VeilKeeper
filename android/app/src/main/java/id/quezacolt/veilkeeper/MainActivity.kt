package id.quezacolt.veilkeeper

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.DeviceIdentity
import id.quezacolt.veilkeeper.data.NetworkModule
import id.quezacolt.veilkeeper.crypto.Argon2idMasterKeyDeriver
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import id.quezacolt.veilkeeper.ui.auth.AuthViewModelFactory
import id.quezacolt.veilkeeper.ui.auth.LoginScreen
import id.quezacolt.veilkeeper.ui.auth.RegisterScreen
import id.quezacolt.veilkeeper.ui.theme.VeilKeeperTheme

private const val ROUTE_LOGIN = "login"
private const val ROUTE_REGISTER = "register"
private const val ROUTE_HOME = "home"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-BASE.md Section 26: screenshot protection on sensitive
        // screens (auth screens explicitly listed). Sprint 1 has no
        // non-sensitive screens yet (vault/home UI is Sprint 2+), so
        // applying it activity-wide is the simplest correct behavior for
        // now; revisit with a per-screen toggle once non-sensitive screens
        // exist.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()

        val vaultCrypto = VaultCrypto(Argon2idMasterKeyDeriver())
        val authRepository = AuthRepository(NetworkModule.authApi, vaultCrypto)
        val deviceIdentifier = DeviceIdentity.getOrCreate(applicationContext)
        val viewModelFactory = AuthViewModelFactory(authRepository, deviceIdentifier)

        setContent {
            VeilKeeperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VeilKeeperApp(viewModelFactory)
                }
            }
        }
    }
}

@Composable
fun VeilKeeperApp(factory: AuthViewModelFactory) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_LOGIN) {
        composable(ROUTE_LOGIN) {
            LoginScreen(
                factory = factory,
                onLoggedIn = { navController.navigate(ROUTE_HOME) { popUpTo(ROUTE_LOGIN) { inclusive = true } } },
                onNavigateToRegister = { navController.navigate(ROUTE_REGISTER) },
            )
        }
        composable(ROUTE_REGISTER) {
            RegisterScreen(
                factory = factory,
                onRegistered = { navController.navigate(ROUTE_LOGIN) { popUpTo(ROUTE_REGISTER) { inclusive = true } } },
                onNavigateToLogin = { navController.popBackStack() },
            )
        }
        composable(ROUTE_HOME) {
            // Sprint 2+ scope (Vault Foundation). Sprint 1 only needs to
            // prove a successful login transitions away from the auth flow.
            HomePlaceholder()
        }
    }
}

@Composable
private fun HomePlaceholder() {
    Surface(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        ) {
            androidx.compose.material3.Text(
                text = "Logged in. Vault UI lands in Sprint 2.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}
