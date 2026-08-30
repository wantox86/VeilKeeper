package id.quezacolt.veilkeeper.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HkdfTest {

    /**
     * RFC 5869 Appendix A.3 ("Test Case 3" for HKDF-SHA256): zero-length
     * salt and zero-length info. Per RFC 5869 Section 2.2, when no salt is
     * provided it defaults to a string of HashLen zero bytes -- exactly
     * what [Hkdf.deriveKey] always does internally -- so this is a direct,
     * exact-match verification of our implementation against the published
     * test vector, not an approximation.
     */
    @Test
    fun `matches RFC 5869 SHA-256 test case 3 (zero-length salt and info)`() {
        val ikm = ByteArray(22) { 0x0b }
        val info = ByteArray(0)

        val okm = Hkdf.deriveKey(ikm, info, outputLength = 42)

        val expected = hexToBytes(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2" +
                "d9d201395faa4b61a96c8",
        )
        assertArrayEquals(expected, okm)
    }

    @Test
    fun `is deterministic for the same inputs`() {
        val ikm = "some-master-key-material-32bytes".toByteArray()
        val info = "veilkeeper:auth:v1".toByteArray()

        val a = Hkdf.deriveKey(ikm, info, 32)
        val b = Hkdf.deriveKey(ikm, info, 32)

        assertArrayEquals(a, b)
    }

    @Test
    fun `domain separation produces different keys for different info`() {
        val ikm = "some-master-key-material-32bytes".toByteArray()

        val authKey = Hkdf.deriveKey(ikm, "veilkeeper:auth:v1".toByteArray(), 32)
        val wrapKey = Hkdf.deriveKey(ikm, "veilkeeper:wrap:v1".toByteArray(), 32)

        assertFalse("AuthKey and WrapKey must differ despite sharing a MasterKey", authKey.contentEquals(wrapKey))
    }

    @Test
    fun `supports output longer than one hash block`() {
        val ikm = "ikm".toByteArray()
        val info = "info".toByteArray()

        val okm = Hkdf.deriveKey(ikm, info, outputLength = 100)

        org.junit.Assert.assertEquals(100, okm.size)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
