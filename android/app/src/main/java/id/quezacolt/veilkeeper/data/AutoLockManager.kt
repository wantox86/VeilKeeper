package id.quezacolt.veilkeeper.data

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Wires SPEC-BASE.md Section 24 ("Auto Lock") to app process lifecycle
 * events. Registered once, against `ProcessLifecycleOwner` (whole-app
 * foreground/background, not per-Activity -- so rotating the screen or
 * navigating between this app's own screens never triggers a false lock),
 * from [id.quezacolt.veilkeeper.VeilKeeperApplication].
 *
 * The "device screen locks" trigger (also Section 24) is independent of the
 * configured timeout and independent of process lifecycle -- turning the
 * screen off is reported via a `SCREEN_OFF` broadcast receiver (also
 * registered in the Application) which calls [onScreenOff] directly.
 *
 * Actual locking is delegated to [AuthSessionHolder.lock] (clears only the
 * in-memory VDK; session token + unwrap material survive so the Unlock
 * screen can restore access via password or biometric -- see that class's
 * doc comment).
 */
class AutoLockManager(
    private val settingsRepository: SettingsRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : DefaultLifecycleObserver {
    private var backgroundedAtMillis: Long? = null

    override fun onStop(owner: LifecycleOwner) {
        val timeout = settingsRepository.autoLockTimeout.value
        if (timeout == AutoLockTimeout.IMMEDIATE) {
            AuthSessionHolder.lock()
            backgroundedAtMillis = null
        } else {
            backgroundedAtMillis = clock()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        val backgroundedAt = backgroundedAtMillis ?: return
        val timeout = settingsRepository.autoLockTimeout.value
        if (AutoLockPolicy.shouldLock(timeout, backgroundedAt, clock())) {
            AuthSessionHolder.lock()
        }
        backgroundedAtMillis = null
    }

    /** Called by the SCREEN_OFF broadcast receiver -- locks immediately regardless of the configured timeout. */
    fun onScreenOff() {
        AuthSessionHolder.lock()
    }
}
