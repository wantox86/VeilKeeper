package id.quezacolt.veilkeeper.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Host-JVM fake of [SessionCipherProvider] (real impl: [KeystoreSessionCipher],
 * which needs a real Android Keystore provider). Uses a plain in-memory
 * AES-256-GCM key instead of a Keystore-backed one -- same "fake the
 * unavailable Android crypto, exercise everything around it for real"
 * pattern as [FakeMasterKeyDeriver] (Sprint 1). This lets
 * [id.quezacolt.veilkeeper.data.PersistedSessionStore]'s actual
 * serialize/encrypt/decrypt/deserialize logic run for real in unit tests,
 * not just be assumed correct.
 */
class FakeSessionCipherProvider : SessionCipherProvider {
    private var key = randomKey()
    private var deleted = false

    override fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        if (deleted) throw IllegalStateException("key deleted")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher
    }

    override fun deleteKey() {
        deleted = true
        key = randomKey()
    }

    private fun randomKey() = SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES")

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
    }
}
