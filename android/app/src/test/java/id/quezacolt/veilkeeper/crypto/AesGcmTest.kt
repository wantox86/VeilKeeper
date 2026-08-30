package id.quezacolt.veilkeeper.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * SPEC-BASE.md Section 47 ("Critical Security Tests" -- Encryption):
 * plaintext -> encrypt -> decrypt must exactly match the original, and the
 * nonce/IV must be unique per encryption.
 */
class AesGcmTest {

    private fun randomKey() = ByteArray(AesGcm.KEY_LENGTH_BYTES).also(SecureRandom()::nextBytes)

    @Test
    fun `round trip returns the original plaintext exactly`() {
        val key = randomKey()
        val plaintext = "the quick brown fox jumps over the lazy dog".toByteArray()

        val ciphertext = AesGcm.encrypt(key, plaintext)
        val decrypted = AesGcm.decrypt(key, ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `round trip works for empty plaintext`() {
        val key = randomKey()
        val ciphertext = AesGcm.encrypt(key, ByteArray(0))
        val decrypted = AesGcm.decrypt(key, ciphertext)
        assertEquals(0, decrypted.size)
    }

    @Test
    fun `each encryption uses a unique nonce`() {
        val key = randomKey()
        val plaintext = "same plaintext every time".toByteArray()

        val nonces = (1..50).map { AesGcm.encrypt(key, plaintext).copyOfRange(0, AesGcm.NONCE_LENGTH_BYTES) }

        val distinctNonces = nonces.map { it.toList() }.toSet()
        assertEquals("expected all 50 nonces to be unique", 50, distinctNonces.size)
    }

    @Test
    fun `same plaintext produces different ciphertext on each call`() {
        val key = randomKey()
        val plaintext = "same plaintext every time".toByteArray()

        val c1 = AesGcm.encrypt(key, plaintext)
        val c2 = AesGcm.encrypt(key, plaintext)

        assertFalse("ciphertext must differ across calls due to unique nonces", c1.contentEquals(c2))
    }

    @Test
    fun `decrypting with the wrong key fails`() {
        val ciphertext = AesGcm.encrypt(randomKey(), "secret".toByteArray())

        assertThrows(GeneralSecurityException::class.java) {
            AesGcm.decrypt(randomKey(), ciphertext)
        }
    }

    @Test
    fun `tampering with ciphertext is detected`() {
        val key = randomKey()
        val ciphertext = AesGcm.encrypt(key, "secret payload".toByteArray())
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            AesGcm.decrypt(key, tampered)
        }
    }

    @Test
    fun `rejects wrong key length`() {
        assertThrows(IllegalArgumentException::class.java) {
            AesGcm.encrypt(ByteArray(16), "x".toByteArray()) // AES-128 length, we require AES-256
        }
    }

    @Test
    fun `ciphertext length differs from a naive concatenation without a nonce`() {
        val key = randomKey()
        val plaintext = "x".toByteArray()
        val ciphertext = AesGcm.encrypt(key, plaintext)
        assertNotEquals(plaintext.size, ciphertext.size)
    }
}
