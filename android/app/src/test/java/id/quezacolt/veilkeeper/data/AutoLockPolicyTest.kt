package id.quezacolt.veilkeeper.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockPolicyTest {

    @Test
    fun `IMMEDIATE always locks regardless of elapsed time`() {
        assertTrue(AutoLockPolicy.shouldLock(AutoLockTimeout.IMMEDIATE, backgroundedAtMillis = 1_000, nowMillis = 1_000))
        assertTrue(AutoLockPolicy.shouldLock(AutoLockTimeout.IMMEDIATE, backgroundedAtMillis = 1_000, nowMillis = 1_001))
    }

    @Test
    fun `does not lock before the configured timeout elapses`() {
        val timeout = AutoLockTimeout.FIVE_MINUTES
        assertFalse(AutoLockPolicy.shouldLock(timeout, backgroundedAtMillis = 0, nowMillis = timeout.millis - 1))
    }

    @Test
    fun `locks exactly at and after the configured timeout`() {
        val timeout = AutoLockTimeout.ONE_MINUTE
        assertTrue(AutoLockPolicy.shouldLock(timeout, backgroundedAtMillis = 0, nowMillis = timeout.millis))
        assertTrue(AutoLockPolicy.shouldLock(timeout, backgroundedAtMillis = 0, nowMillis = timeout.millis + 5_000))
    }
}
