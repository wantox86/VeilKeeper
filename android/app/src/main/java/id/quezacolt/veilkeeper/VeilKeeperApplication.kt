package id.quezacolt.veilkeeper

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.lifecycle.ProcessLifecycleOwner
import id.quezacolt.veilkeeper.data.AndroidClipboardPort
import id.quezacolt.veilkeeper.data.AuthSessionHolder
import id.quezacolt.veilkeeper.data.AutoLockManager
import id.quezacolt.veilkeeper.data.BiometricVaultCache
import id.quezacolt.veilkeeper.data.ClipboardSecurity
import id.quezacolt.veilkeeper.data.PersistedSessionStore
import id.quezacolt.veilkeeper.data.SettingsRepository
import id.quezacolt.veilkeeper.data.SharedPrefsSessionStorage
import id.quezacolt.veilkeeper.data.SharedPrefsSettingsStorage
import id.quezacolt.veilkeeper.data.VaultBiometricManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * App-wide singletons for Sprint 3 "Secure UX" (SPEC-BASE.md Phase 3):
 * settings, the biometric VDK cache, clipboard auto-clear, and the auto-lock
 * manager -- wired here once at process start rather than per-Activity, so
 * [id.quezacolt.veilkeeper.data.AutoLockManager] observes the whole app's
 * foreground/background transitions (via `ProcessLifecycleOwner`) instead of
 * a single Activity's, and the screen-off receiver keeps working across
 * Activity recreation.
 */
class VeilKeeperApplication : Application() {
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var biometricVaultCache: BiometricVaultCache
        private set
    lateinit var vaultBiometricManager: VaultBiometricManager
        private set
    lateinit var clipboardSecurity: ClipboardSecurity
        private set

    /**
     * Post-launch fixes batch 2, item #1. Built and wired here (process
     * start, before any Activity/UI exists) rather than in `MainActivity`,
     * matching every other Sprint 3 singleton in this class -- see
     * [PersistedSessionStore]'s doc comment for the full fix rationale.
     */
    lateinit var persistedSessionStore: PersistedSessionStore
        private set

    /** Process-lifetime scope for background work with no natural owner (e.g. the clipboard-clear delay). */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var autoLockManager: AutoLockManager
    private lateinit var screenOffReceiver: BroadcastReceiver

    override fun onCreate() {
        super.onCreate()

        settingsRepository = SettingsRepository(SharedPrefsSettingsStorage(this))
        biometricVaultCache = BiometricVaultCache(this)
        vaultBiometricManager = VaultBiometricManager(biometricVaultCache)
        clipboardSecurity = ClipboardSecurity(AndroidClipboardPort(this), appScope)
        persistedSessionStore = PersistedSessionStore(SharedPrefsSessionStorage(this))

        // Post-launch fixes batch 2, item #1: restore state (b) -- "was
        // logged in before, this is a fresh process" -- as LOCKED, before any
        // UI exists. `MainActivity`'s NavHost then picks its start
        // destination directly from AuthSessionHolder.lockState (already
        // LOCKED by the time setContent runs), so there is no Login-screen
        // flash. Never restores UNLOCKED -- see AuthSessionHolder.restoreLocked's
        // doc comment for why this can never skip a real authentication.
        persistedSessionStore.load()?.let { persisted ->
            AuthSessionHolder.restoreLocked(persisted.sessionToken, persisted.unwrapMaterial, persisted.email)
        }

        autoLockManager = AutoLockManager(settingsRepository)
        ProcessLifecycleOwner.get().lifecycle.addObserver(autoLockManager)

        // SPEC-BASE.md Section 24 "Device screen locks" trigger -- independent
        // of the app-background timeout above; locks immediately.
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                autoLockManager.onScreenOff()
            }
        }
        registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
}
