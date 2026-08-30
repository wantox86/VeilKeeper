package id.quezacolt.veilkeeper.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.SecureRandom

/**
 * End-to-end round trip of a full [VaultItemPayload] (title + content
 * blocks) through [VaultItemCrypto] -- the client-side half of CLAUDE.md
 * Sprint 2's acceptance flow ("Create vault item -> Encrypt -> Upload ->
 * Retrieve -> Decrypt -> Display").
 */
class VaultItemCryptoTest {

    private fun randomVdk() = ByteArray(AesGcm.KEY_LENGTH_BYTES).also(SecureRandom()::nextBytes)

    private val samplePayload = VaultItemPayload(
        title = "GitLab Production",
        content = listOf(
            ContentBlockDto(type = "text", label = "Username", value = "wawan"),
            ContentBlockDto(type = "secret", label = "Token", value = "glpat-xxxxx"),
            ContentBlockDto(type = "note", value = "Production token, rotate quarterly."),
        ),
    )

    @Test
    fun `encrypt then decrypt returns an identical payload`() {
        val vdk = randomVdk()

        val ciphertext = VaultItemCrypto.encrypt(vdk, samplePayload)
        val decrypted = VaultItemCrypto.decrypt(vdk, ciphertext)

        assertEquals(samplePayload, decrypted)
    }

    @Test
    fun `ciphertext never contains the plaintext title or secret value`() {
        val vdk = randomVdk()
        val ciphertext = VaultItemCrypto.encrypt(vdk, samplePayload)
        val ciphertextAsLatin1 = String(ciphertext, Charsets.ISO_8859_1)

        org.junit.Assert.assertFalse(ciphertextAsLatin1.contains("GitLab Production"))
        org.junit.Assert.assertFalse(ciphertextAsLatin1.contains("glpat-xxxxx"))
    }

    @Test
    fun `same payload encrypted twice yields different ciphertext (unique nonce)`() {
        val vdk = randomVdk()
        val c1 = VaultItemCrypto.encrypt(vdk, samplePayload)
        val c2 = VaultItemCrypto.encrypt(vdk, samplePayload)

        org.junit.Assert.assertFalse(c1.contentEquals(c2))
    }

    @Test
    fun `decrypting with the wrong VDK fails`() {
        val ciphertext = VaultItemCrypto.encrypt(randomVdk(), samplePayload)

        assertThrows(GeneralSecurityException::class.java) {
            VaultItemCrypto.decrypt(randomVdk(), ciphertext)
        }
    }

    @Test
    fun `round trip works for an item with only a note block`() {
        val vdk = randomVdk()
        val payload = VaultItemPayload(title = "Quick note", content = listOf(ContentBlockDto(type = "note", value = "Remember to renew the domain.")))

        val decrypted = VaultItemCrypto.decrypt(vdk, VaultItemCrypto.encrypt(vdk, payload))

        assertEquals(payload, decrypted)
    }
}
