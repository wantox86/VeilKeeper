package id.quezacolt.veilkeeper.data

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sprint 3 (SPEC-BASE.md Section 23 "Clipboard Security") timing tests:
 * copy-then-auto-clear-after-delay, and that a superseding copy or an
 * external clipboard change isn't clobbered by a stale delayed clear.
 */
class ClipboardSecurityTest {

    @Test
    fun `clears the clipboard after the configured delay`() = runTest {
        val clipboard = FakeClipboardPort()
        val security = ClipboardSecurity(clipboard, this)

        security.copyAndScheduleClear("API Token", "s3cr3t-value", clearAfterMillis = 30_000)
        assertEquals("s3cr3t-value", clipboard.currentText())

        advanceTimeBy(30_001)
        assertNull(clipboard.currentText())
        assertEquals(1, clipboard.clearCallCount)
    }

    @Test
    fun `does not clear before the delay elapses`() = runTest {
        val clipboard = FakeClipboardPort()
        val security = ClipboardSecurity(clipboard, this)

        security.copyAndScheduleClear("API Token", "s3cr3t-value", clearAfterMillis = 30_000)
        advanceTimeBy(29_000)

        assertEquals("s3cr3t-value", clipboard.currentText())
        assertEquals(0, clipboard.clearCallCount)
    }

    @Test
    fun `a newer copy supersedes and cancels the older pending clear`() = runTest {
        val clipboard = FakeClipboardPort()
        val security = ClipboardSecurity(clipboard, this)

        security.copyAndScheduleClear("Field A", "first-secret", clearAfterMillis = 10_000)
        advanceTimeBy(5_000)
        security.copyAndScheduleClear("Field B", "second-secret", clearAfterMillis = 10_000)

        // The first copy's clear would have fired at t=10_000; by then the
        // second copy's own clear (scheduled for t=15_000) must not have
        // fired yet, and the second secret must still be present.
        advanceTimeBy(5_001)
        assertEquals("second-secret", clipboard.currentText())

        advanceTimeBy(5_000)
        assertNull(clipboard.currentText())
        assertEquals(1, clipboard.clearCallCount)
    }

    @Test
    fun `does not clobber clipboard content the user copied from elsewhere in the meantime`() = runTest {
        val clipboard = FakeClipboardPort()
        val security = ClipboardSecurity(clipboard, this)

        security.copyAndScheduleClear("API Token", "s3cr3t-value", clearAfterMillis = 10_000)
        clipboard.simulateExternalCopy("something the user copied from another app")

        advanceTimeBy(10_001)

        assertEquals("something the user copied from another app", clipboard.currentText())
        assertEquals(0, clipboard.clearCallCount)
    }

    @Test
    fun `zero delay never schedules a clear`() = runTest {
        val clipboard = FakeClipboardPort()
        val security = ClipboardSecurity(clipboard, this)

        security.copyAndScheduleClear("API Token", "s3cr3t-value", clearAfterMillis = 0)
        advanceTimeBy(60_000)

        assertEquals("s3cr3t-value", clipboard.currentText())
        assertEquals(0, clipboard.clearCallCount)
    }
}
