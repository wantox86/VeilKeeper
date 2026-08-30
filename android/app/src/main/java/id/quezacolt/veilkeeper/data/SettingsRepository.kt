package id.quezacolt.veilkeeper.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Minimal key-value storage abstraction so [SettingsRepository] is
 * unit-testable on the host JVM without a real Android [Context] -- same
 * pattern as Sprint 1's `MasterKeyDeriver` interface.
 */
interface SettingsStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
}

/** Real [SettingsStorage] backed by [android.content.SharedPreferences]. */
class SharedPrefsSettingsStorage(context: Context) : SettingsStorage {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "veilkeeper_settings"
    }
}

/**
 * Non-secret app settings (Sprint 3 "Settings screen minimal", SPEC-BASE.md
 * Section 24): auto-lock timeout. Biometric opt-in state itself is derived
 * from [BiometricVaultCache.isEnabled] (whether a cache blob + Keystore key
 * currently exist), not duplicated here, to avoid the two getting out of
 * sync.
 */
class SettingsRepository(private val storage: SettingsStorage) {
    private val _autoLockTimeout = MutableStateFlow(
        AutoLockTimeout.fromName(storage.getString(KEY_AUTO_LOCK_TIMEOUT)),
    )
    val autoLockTimeout: StateFlow<AutoLockTimeout> = _autoLockTimeout.asStateFlow()

    fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        storage.putString(KEY_AUTO_LOCK_TIMEOUT, timeout.name)
        _autoLockTimeout.value = timeout
    }

    private val _clipboardClearDelay = MutableStateFlow(
        ClipboardClearDelay.fromName(storage.getString(KEY_CLIPBOARD_CLEAR_DELAY)),
    )
    val clipboardClearDelay: StateFlow<ClipboardClearDelay> = _clipboardClearDelay.asStateFlow()

    fun setClipboardClearDelay(delay: ClipboardClearDelay) {
        storage.putString(KEY_CLIPBOARD_CLEAR_DELAY, delay.name)
        _clipboardClearDelay.value = delay
    }

    companion object {
        private const val KEY_AUTO_LOCK_TIMEOUT = "auto_lock_timeout"
        private const val KEY_CLIPBOARD_CLEAR_DELAY = "clipboard_clear_delay"
    }
}
