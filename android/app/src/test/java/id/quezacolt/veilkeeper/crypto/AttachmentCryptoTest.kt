package id.quezacolt.veilkeeper.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * SPEC-BASE.md Section 47 ("Critical Security Tests" -- Encryption),
 * applied to [AttachmentCrypto] (Sprint 5, Phase 5's "Encryption" step):
 * plaintext -> encrypt -> decrypt must exactly match the original for both
 * file bytes and filenames, and nonces must be unique. [AesGcmTest] already
 * covers the underlying primitive exhaustively; this focuses on
 * [AttachmentCrypto]'s own responsibilities (byte vs. string handling, the
 * two operations being independently nonced).
 */
class AttachmentCryptoTest {

    private fun randomVdk() = ByteArray(AesGcm.KEY_LENGTH_BYTES).also(SecureRandom()::nextBytes)

    @Test
    fun `encryptFile then decryptFile returns the original bytes exactly`() {
        val vdk = randomVdk()
        val plaintext = ByteArray(4096) { (it % 256).toByte() } // stand-in for compressed image bytes

        val ciphertext = AttachmentCrypto.encryptFile(vdk, plaintext)
        val decrypted = AttachmentCrypto.decryptFile(vdk, ciphertext)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `encryptFilename then decryptFilename returns the original filename exactly`() {
        val vdk = randomVdk()
        val filename = "chase_bank_backup_codes.png"

        val ciphertext = AttachmentCrypto.encryptFilename(vdk, filename)
        val decrypted = AttachmentCrypto.decryptFilename(vdk, ciphertext)

        assertEquals(filename, decrypted)
    }

    @Test
    fun `ciphertext never contains the plaintext filename`() {
        val vdk = randomVdk()
        val filename = "very-identifiable-filename.jpg"

        val ciphertext = AttachmentCrypto.encryptFilename(vdk, filename)

        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains(filename))
    }

    @Test
    fun `file and filename encryption use independent, unique nonces`() {
        val vdk = randomVdk()
        val plaintext = "same bytes every time".toByteArray()

        val fileNonces = (1..20).map { AttachmentCrypto.encryptFile(vdk, plaintext).copyOfRange(0, AesGcm.NONCE_LENGTH_BYTES).toList() }
        val filenameNonces = (1..20).map { AttachmentCrypto.encryptFilename(vdk, "name.jpg").copyOfRange(0, AesGcm.NONCE_LENGTH_BYTES).toList() }

        assertEquals("expected all 20 file-encryption nonces to be unique", 20, fileNonces.toSet().size)
        assertEquals("expected all 20 filename-encryption nonces to be unique", 20, filenameNonces.toSet().size)
    }

    @Test
    fun `decrypting file bytes with the wrong key fails`() {
        val ciphertext = AttachmentCrypto.encryptFile(randomVdk(), "secret image bytes".toByteArray())

        assertThrows(GeneralSecurityException::class.java) {
            AttachmentCrypto.decryptFile(randomVdk(), ciphertext)
        }
    }

    @Test
    fun `tampering with encrypted file bytes is detected`() {
        val vdk = randomVdk()
        val ciphertext = AttachmentCrypto.encryptFile(vdk, "secret image bytes".toByteArray())
        val tampered = ciphertext.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1] + 1).toByte()

        assertThrows(GeneralSecurityException::class.java) {
            AttachmentCrypto.decryptFile(vdk, tampered)
        }
    }
}
