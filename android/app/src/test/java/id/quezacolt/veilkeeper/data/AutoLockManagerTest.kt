package id.quezacolt.veilkeeper.data

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Sprint 3 (SPEC-BASE.md Section 24): state-transition tests for
 * [AutoLockManager] driven directly (no real Android process lifecycle
 * needed -- [DefaultLifecycleObserver]'s onStop/onStart are plain method
 * calls we can invoke with a stub [LifecycleOwner]).
 */
class AutoLockManagerTest {

    private object StubLifecycleOwner : LifecycleOwner {
        override val lifecycle: Lifecycle get() = throw NotImplementedError("AutoLockManager never reads this")
    }

    private var fakeNowMillis = 0L
    private lateinit var settings: SettingsRepository
    private lateinit var manager: AutoLockManager

    @Before
    fun setUp() {
        settings = SettingsRepository(InMemorySettingsStorage())
        manager = AutoLockManager(settings, clock = { fakeNowMillis })
        AuthSessionHolder.set("token", ByteArray(32) { 1 })
    }

    @After
    fun tearDown() {
        AuthSessionHolder.clear()
    }

    @Test
    fun `IMMEDIATE timeout locks as soon as the app backgrounds`() {
        settings.setAutoLockTimeout(AutoLockTimeout.IMMEDIATE)

        manager.onStop(StubLifecycleOwner)

        assertEquals(VaultLockState.LOCKED, AuthSessionHolder.lockState.value)
    }

    @Test
    fun `configured timeout does not lock on background alone`() {
        settings.setAutoLockTimeout(AutoLockTimeout.FIVE_MINUTES)

        manager.onStop(StubLifecycleOwner)

        assertEquals("must not lock until the app actually returns to foreground past the timeout", VaultLockState.UNLOCKED, AuthSessionHolder.lockState.value)
    }

    @Test
    fun `returning to foreground before timeout elapses does not lock`() {
        settings.setAutoLockTimeout(AutoLockTimeout.FIVE_MINUTES)
        fakeNowMillis = 0
        manager.onStop(StubLifecycleOwner)

        fakeNowMillis = AutoLockTimeout.FIVE_MINUTES.millis - 1
        manager.onStart(StubLifecycleOwner)

        assertEquals(VaultLockState.UNLOCKED, AuthSessionHolder.lockState.value)
    }

    @Test
    fun `returning to foreground after timeout elapses locks`() {
        settings.setAutoLockTimeout(AutoLockTimeout.ONE_MINUTE)
        fakeNowMillis = 0
        manager.onStop(StubLifecycleOwner)

        fakeNowMillis = AutoLockTimeout.ONE_MINUTE.millis + 1
        manager.onStart(StubLifecycleOwner)

        assertEquals(VaultLockState.LOCKED, AuthSessionHolder.lockState.value)
    }

    @Test
    fun `onScreenOff locks immediately regardless of configured timeout`() {
        settings.setAutoLockTimeout(AutoLockTimeout.FIFTEEN_MINUTES)

        manager.onScreenOff()

        assertEquals(VaultLockState.LOCKED, AuthSessionHolder.lockState.value)
    }

    @Test
    fun `onStart with no prior onStop is a no-op`() {
        settings.setAutoLockTimeout(AutoLockTimeout.IMMEDIATE)

        manager.onStart(StubLifecycleOwner)

        assertEquals(VaultLockState.UNLOCKED, AuthSessionHolder.lockState.value)
    }
}
