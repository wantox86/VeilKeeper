package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.KdfParams
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Sprint 3 (SPEC-BASE.md Section 24 "Auto Lock") state-transition tests for
 * [AuthSessionHolder]: logged-out -> unlocked -> locked -> unlocked, and
 * that a lock clears the VDK bytes in place (best-effort zeroing) while
 * keeping the session token + unwrap material alive.
 */
class AuthSessionHolderTest {

    @Before
    fun setUp() {
        AuthSessionHolder.clear()
    }

    @After
    fun tearDown() {
        AuthSessionHolder.clear()
    }

    private fun material() = VdkUnwrapMaterial(
        kdfSalt = ByteArray(16) { it.toByte() },
        kdfParams = KdfParams.DEFAULT,
        wrappedVdk = ByteArray(48) { it.toByte() },
    )

    @Test
    fun `starts logged out`() {
        assertEquals(VaultLockState.LOGGED_OUT, AuthSessionHolder.lockState.value)
        assertNull(AuthSessionHolder.sessionToken)
        assertNull(AuthSessionHolder.vaultDataKey)
    }

    @Test
    fun `set transitions to unlocked and stores session, vdk, and unwrap material`() {
        val vdk = ByteArray(32) { 7 }
        AuthSessionHolder.set("token-1", vdk, material(), "user@example.com")

        assertEquals(VaultLockState.UNLOCKED, AuthSessionHolder.lockState.value)
        assertEquals("token-1", AuthSessionHolder.sessionToken)
        assertArrayEquals(vdk, AuthSessionHolder.vaultDataKey)
        assertEquals("user@example.com", AuthSessionHolder.email)
        assertNotNull(AuthSessionHolder.unwrapMaterial)
    }

    @Test
    fun `lock clears the VDK but keeps session token and unwrap material`() {
        val vdk = ByteArray(32) { 9 }
        val mat = material()
        AuthSessionHolder.set("token-1", vdk, mat, "user@example.com")

        AuthSessionHolder.lock()

        assertEquals(VaultLockState.LOCKED, AuthSessionHolder.lockState.value)
        assertNull("VDK must be cleared from memory on lock", AuthSessionHolder.vaultDataKey)
        assertEquals("session token must survive a lock (Unlock screen needs it)", "token-1", AuthSessionHolder.sessionToken)
        assertEquals("unwrap material must survive a lock (offline password unlock needs it)", mat, AuthSessionHolder.unwrapMaterial)
    }

    @Test
    fun `lock is a no-op when already logged out`() {
        AuthSessionHolder.lock()
        assertEquals(VaultLockState.LOGGED_OUT, AuthSessionHolder.lockState.value)
    }

    @Test
    fun `unlock restores the VDK and transitions back to unlocked`() {
        AuthSessionHolder.set("token-1", ByteArray(32) { 1 }, material(), "user@example.com")
        AuthSessionHolder.lock()

        val restoredVdk = ByteArray(32) { 1 }
        AuthSessionHolder.unlock(restoredVdk)

        assertEquals(VaultLockState.UNLOCKED, AuthSessionHolder.lockState.value)
        assertArrayEquals(restoredVdk, AuthSessionHolder.vaultDataKey)
    }

    @Test(expected = IllegalStateException::class)
    fun `unlock without an active session throws`() {
        AuthSessionHolder.unlock(ByteArray(32))
    }

    @Test
    fun `clear resets everything including unwrap material and email`() {
        AuthSessionHolder.set("token-1", ByteArray(32), material(), "user@example.com")

        AuthSessionHolder.clear()

        assertEquals(VaultLockState.LOGGED_OUT, AuthSessionHolder.lockState.value)
        assertNull(AuthSessionHolder.sessionToken)
        assertNull(AuthSessionHolder.vaultDataKey)
        assertNull(AuthSessionHolder.unwrapMaterial)
        assertNull(AuthSessionHolder.email)
    }
}
