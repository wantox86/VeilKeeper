package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.FakeSessionCipherProvider
import id.quezacolt.veilkeeper.crypto.KdfParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Post-launch fixes batch 2, item #1: unit tests for the persisted-session
 * save/load round trip that backs "swipe from recent-apps -> Unlock, not
 * Login." Uses [FakeSessionCipherProvider] (plain AES, no real Keystore
 * needed) so the actual serialize/encrypt/decrypt/deserialize logic in
 * [PersistedSessionStore] runs for real, not just against a mock.
 */
class PersistedSessionStoreTest {

    private lateinit var storage: InMemorySettingsStorage
    private lateinit var cipherProvider: FakeSessionCipherProvider
    private lateinit var store: PersistedSessionStore

    @Before
    fun setUp() {
        storage = InMemorySettingsStorage()
        cipherProvider = FakeSessionCipherProvider()
        store = PersistedSessionStore(storage, cipherProvider)
    }

    private fun material() = VdkUnwrapMaterial(
        kdfSalt = ByteArray(16) { it.toByte() },
        kdfParams = KdfParams.DEFAULT,
        wrappedVdk = ByteArray(48) { (it * 3).toByte() },
    )

    @Test
    fun `load returns null when nothing has been saved`() {
        assertNull(store.load())
    }

    @Test
    fun `save then load round-trips the session token, unwrap material, and email`() {
        val mat = material()
        store.save("session-token-123", mat, "user@example.com")

        val loaded = store.load()
        assertEquals("session-token-123", loaded?.sessionToken)
        assertEquals("user@example.com", loaded?.email)
        assertArrayEquals(mat.kdfSalt, loaded?.unwrapMaterial?.kdfSalt)
        assertArrayEquals(mat.wrappedVdk, loaded?.unwrapMaterial?.wrappedVdk)
        assertEquals(mat.kdfParams, loaded?.unwrapMaterial?.kdfParams)
    }

    @Test
    fun `save then load round-trips a null email`() {
        store.save("session-token-123", material(), null)

        val loaded = store.load()
        assertNull(loaded?.email)
        assertEquals("session-token-123", loaded?.sessionToken)
    }

    @Test
    fun `never stores the plaintext session token or salt anywhere in the underlying storage`() {
        store.save("super-secret-session-token", material(), "user@example.com")

        // The only thing PersistedSessionStore writes to storage is the
        // base64 ciphertext blob + IV length -- neither should ever contain
        // the raw token/email as a readable substring.
        val allValues = listOf("persisted_session_blob", "persisted_session_iv_len")
            .mapNotNull { storage.getString(it) }
        allValues.forEach { value ->
            assert(!value.contains("super-secret-session-token")) { "plaintext session token leaked into storage" }
            assert(!value.contains("user@example.com")) { "plaintext email leaked into storage" }
        }
    }

    @Test
    fun `clear wipes the stored blob so a subsequent load returns null`() {
        store.save("session-token-123", material(), "user@example.com")
        assertEquals("session-token-123", store.load()?.sessionToken)

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `load returns null and self-heals if the underlying key is gone (deleteKey called out from under it)`() {
        store.save("session-token-123", material(), "user@example.com")

        // Simulates the Keystore key becoming unusable (e.g. wiped by the
        // OS) without the blob itself being cleared -- load() must treat
        // this as "no session," not crash.
        cipherProvider.deleteKey()

        assertNull(store.load())
        // And it should have cleaned up the now-undecryptable stale blob.
        assertNull(store.load())
    }

    @Test
    fun `save overwrites a previously saved session`() {
        store.save("token-a", material(), "a@example.com")
        store.save("token-b", material(), "b@example.com")

        val loaded = store.load()
        assertEquals("token-b", loaded?.sessionToken)
        assertEquals("b@example.com", loaded?.email)
    }
}
