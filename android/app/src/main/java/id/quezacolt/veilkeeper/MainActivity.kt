package id.quezacolt.veilkeeper

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.DeviceIdentity
import id.quezacolt.veilkeeper.data.NetworkModule
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.crypto.Argon2idMasterKeyDeriver
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import id.quezacolt.veilkeeper.ui.auth.AuthViewModelFactory
import id.quezacolt.veilkeeper.ui.auth.LoginScreen
import id.quezacolt.veilkeeper.ui.auth.RegisterScreen
import id.quezacolt.veilkeeper.ui.category.CategoryScreen
import id.quezacolt.veilkeeper.ui.home.HomeScreen
import id.quezacolt.veilkeeper.ui.home.VaultViewModelFactory
import id.quezacolt.veilkeeper.ui.theme.VeilKeeperTheme
import id.quezacolt.veilkeeper.ui.vault.AddItemScreen
import id.quezacolt.veilkeeper.ui.vault.VaultDetailScreen

private const val ROUTE_LOGIN = "login"
private const val ROUTE_REGISTER = "register"
private const val ROUTE_HOME = "home"
private const val ARG_CATEGORY_ID = "categoryId"
private const val ARG_ITEM_ID = "itemId"
private const val ROUTE_CATEGORY = "category/{$ARG_CATEGORY_ID}"
private const val ROUTE_ITEM_DETAIL = "item/{$ARG_ITEM_ID}"
private const val ROUTE_ADD_ITEM = "add-item/{$ARG_CATEGORY_ID}"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-BASE.md Section 26: screenshot protection. Applied
        // activity-wide (auth screens + all vault/secret screens are
        // sensitive) rather than per-screen toggling -- this app has no
        // screen where showing content in the recents-tray thumbnail or
        // allowing a screenshot would be acceptable.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()

        val vaultCrypto = VaultCrypto(Argon2idMasterKeyDeriver())
        val authRepository = AuthRepository(NetworkModule.authApi, vaultCrypto)
        val vaultRepository = VaultRepository(NetworkModule.vaultApi)
        val deviceIdentifier = DeviceIdentity.getOrCreate(applicationContext)
        val authViewModelFactory = AuthViewModelFactory(authRepository, deviceIdentifier)

        setContent {
            VeilKeeperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VeilKeeperApp(authViewModelFactory, vaultRepository)
                }
            }
        }
    }
}

@Composable
fun VeilKeeperApp(authFactory: AuthViewModelFactory, vaultRepository: VaultRepository) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = ROUTE_LOGIN) {
        composable(ROUTE_LOGIN) {
            LoginScreen(
                factory = authFactory,
                onLoggedIn = { navController.navigate(ROUTE_HOME) { popUpTo(ROUTE_LOGIN) { inclusive = true } } },
                onNavigateToRegister = { navController.navigate(ROUTE_REGISTER) },
            )
        }
        composable(ROUTE_REGISTER) {
            RegisterScreen(
                factory = authFactory,
                onRegistered = { navController.navigate(ROUTE_LOGIN) { popUpTo(ROUTE_REGISTER) { inclusive = true } } },
                onNavigateToLogin = { navController.popBackStack() },
            )
        }
        composable(ROUTE_HOME) {
            HomeScreen(
                factory = VaultViewModelFactory.home(vaultRepository),
                onOpenCategory = { navController.navigate("category/${it.id}") },
                onOpenItem = { navController.navigate("item/${it.id}") },
                onAddItem = { defaultCategoryId ->
                    // Home's single "+" (SPEC-BASE.md Section 18.3) has no
                    // category context, so it defaults to the user's first
                    // category; HomeScreen only shows the FAB once at least
                    // one category exists (see defaultCategoryId there).
                    navController.navigate("add-item/$defaultCategoryId")
                },
            )
        }
        composable(
            ROUTE_CATEGORY,
            arguments = listOf(navArgument(ARG_CATEGORY_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getLong(ARG_CATEGORY_ID) ?: 0L
            CategoryScreen(
                factory = VaultViewModelFactory.category(vaultRepository, categoryId),
                onBack = { navController.popBackStack() },
                onOpenItem = { navController.navigate("item/${it.id}") },
                onAddItem = { navController.navigate("add-item/$categoryId") },
            )
        }
        composable(
            ROUTE_ITEM_DETAIL,
            arguments = listOf(navArgument(ARG_ITEM_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val itemId = backStackEntry.arguments?.getLong(ARG_ITEM_ID) ?: 0L
            VaultDetailScreen(
                factory = VaultViewModelFactory.vaultDetail(vaultRepository, itemId),
                onBack = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
            )
        }
        composable(
            ROUTE_ADD_ITEM,
            arguments = listOf(navArgument(ARG_CATEGORY_ID) { type = NavType.LongType }),
        ) { backStackEntry ->
            val categoryId = backStackEntry.arguments?.getLong(ARG_CATEGORY_ID) ?: 0L
            AddItemScreen(
                factory = VaultViewModelFactory.addItem(vaultRepository, categoryId),
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() },
            )
        }
    }
}
