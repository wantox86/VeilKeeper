package id.quezacolt.veilkeeper

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import id.quezacolt.veilkeeper.data.AuthRepository
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.DeviceIdentity
import id.quezacolt.veilkeeper.data.NetworkModule
import id.quezacolt.veilkeeper.data.VaultLockState
import id.quezacolt.veilkeeper.data.VaultRepository
import id.quezacolt.veilkeeper.crypto.Argon2idMasterKeyDeriver
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import id.quezacolt.veilkeeper.ui.auth.AuthViewModelFactory
import id.quezacolt.veilkeeper.ui.auth.LoginScreen
import id.quezacolt.veilkeeper.ui.auth.RegisterScreen
import id.quezacolt.veilkeeper.ui.auth.UnlockScreen
import id.quezacolt.veilkeeper.ui.category.CategoryScreen
import id.quezacolt.veilkeeper.ui.home.HomeScreen
import id.quezacolt.veilkeeper.ui.home.VaultViewModelFactory
import id.quezacolt.veilkeeper.ui.settings.SettingsScreen
import id.quezacolt.veilkeeper.ui.settings.SettingsViewModelFactory
import id.quezacolt.veilkeeper.ui.theme.VeilKeeperTheme
import id.quezacolt.veilkeeper.ui.vault.AddItemScreen
import id.quezacolt.veilkeeper.ui.vault.VaultDetailScreen

private const val ROUTE_LOGIN = "login"
private const val ROUTE_REGISTER = "register"
private const val ROUTE_UNLOCK = "unlock"
private const val ROUTE_HOME = "home"
private const val ROUTE_SETTINGS = "settings"
private const val ARG_CATEGORY_ID = "categoryId"
private const val ARG_ITEM_ID = "itemId"
private const val ROUTE_CATEGORY = "category/{$ARG_CATEGORY_ID}"
private const val ROUTE_ITEM_DETAIL = "item/{$ARG_ITEM_ID}"
private const val ROUTE_ADD_ITEM = "add-item/{$ARG_CATEGORY_ID}"

/**
 * `FragmentActivity` (not plain `ComponentActivity`) since Sprint 3's
 * biometric unlock (SPEC-BASE.md Section 25) needs `BiometricPrompt`, which
 * requires a `FragmentActivity` host. Compose interop (`setContent`, etc.)
 * is unaffected -- `FragmentActivity` extends `ComponentActivity`.
 */
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // SPEC-BASE.md Section 26: screenshot protection. Applied
        // activity-wide (auth screens + all vault/secret screens are
        // sensitive) rather than per-screen toggling -- this app has no
        // screen where showing content in the recents-tray thumbnail or
        // allowing a screenshot would be acceptable.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        enableEdgeToEdge()

        val app = application as VeilKeeperApplication
        val vaultCrypto = VaultCrypto(Argon2idMasterKeyDeriver())
        val authRepository = AuthRepository(NetworkModule.authApi, vaultCrypto, sessionStore = app.persistedSessionStore)
        val vaultRepository = VaultRepository(NetworkModule.vaultApi)
        val deviceIdentifier = DeviceIdentity.getOrCreate(applicationContext)
        val authViewModelFactory = AuthViewModelFactory(authRepository, deviceIdentifier)
        val settingsViewModelFactory = SettingsViewModelFactory.create(
            settingsRepository = app.settingsRepository,
            biometricManager = app.vaultBiometricManager,
            authRepository = authRepository,
            biometricAvailableOnDevice = app.vaultBiometricManager.isAvailable(this),
        )

        setContent {
            VeilKeeperTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VeilKeeperApp(authViewModelFactory, settingsViewModelFactory, vaultRepository)
                }
            }
        }
    }
}

@Composable
fun VeilKeeperApp(
    authFactory: AuthViewModelFactory,
    settingsFactory: androidx.lifecycle.ViewModelProvider.Factory,
    vaultRepository: VaultRepository,
) {
    val navController = rememberNavController()

    // Sprint 3 (SPEC-BASE.md Section 24): react to auto-lock/unlock/logout
    // globally, from wherever the user happens to be in the app, rather than
    // each screen wiring this itself. AuthSessionHolder.lock() (called by
    // AutoLockManager on background/timeout/screen-off) is the single source
    // of truth for this.
    val lockState by AuthSessionHolder.lockState.collectAsState()
    LaunchedEffect(lockState) {
        val current = navController.currentDestination?.route
        when (lockState) {
            VaultLockState.LOCKED -> if (current != ROUTE_UNLOCK) navController.navigate(ROUTE_UNLOCK)
            VaultLockState.UNLOCKED -> if (current == ROUTE_UNLOCK) {
                // Post-launch fixes batch 2, item #1: popBackStack() alone
                // (the pre-batch behavior) correctly returns to wherever
                // Unlock was pushed from (Home, Category, Vault Detail --
                // preserving deep nav position) for the normal in-app
                // auto-lock case, so that stays the primary path. But found
                // via real on-device testing (force-stop -> relaunch ->
                // Unlock -> enter correct password) that it silently
                // no-ops -- returns false, does nothing -- when Unlock is
                // the graph's *start* destination (this batch's new
                // process-restart-into-Unlock case): there is nothing
                // beneath it on the back stack to pop back to, so the app
                // stayed stuck showing "Vault locked" even though
                // AuthSessionHolder had already flipped to UNLOCKED. Only
                // in that fallback case, navigate to Home explicitly.
                val poppedBackToPreviousScreen = navController.popBackStack()
                if (!poppedBackToPreviousScreen) {
                    navController.navigate(ROUTE_HOME) { popUpTo(ROUTE_UNLOCK) { inclusive = true } }
                }
            }
            VaultLockState.LOGGED_OUT -> if (current != ROUTE_LOGIN && current != ROUTE_REGISTER) {
                navController.navigate(ROUTE_LOGIN) { popUpTo(0) { inclusive = true } }
            }
        }
    }

    // Post-launch fixes batch 2, item #1: pick the start destination from
    // whatever AuthSessionHolder.lockState already is at first composition
    // (VeilKeeperApplication.onCreate has already run restoreLocked() by
    // this point, before setContent) instead of always hardcoding Login --
    // this is what actually avoids a Login-screen flash before the
    // LaunchedEffect above redirects to Unlock. `remember` (not
    // `collectAsState`) is deliberate: this must be read exactly once, at
    // startup, not re-evaluated on every later lock/unlock transition
    // (those are handled by the LaunchedEffect above; NavHost's own
    // `startDestination` is a one-time initial-composition value in
    // Navigation Compose, changing it later has no effect anyway).
    val startDestination = remember {
        when (AuthSessionHolder.lockState.value) {
            VaultLockState.LOCKED -> ROUTE_UNLOCK
            VaultLockState.UNLOCKED -> ROUTE_HOME
            VaultLockState.LOGGED_OUT -> ROUTE_LOGIN
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
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
        composable(ROUTE_UNLOCK) {
            UnlockScreen(factory = authFactory)
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
                onOpenSettings = { navController.navigate(ROUTE_SETTINGS) },
            )
        }
        composable(ROUTE_SETTINGS) {
            SettingsScreen(
                factory = settingsFactory,
                onBack = { navController.popBackStack() },
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
