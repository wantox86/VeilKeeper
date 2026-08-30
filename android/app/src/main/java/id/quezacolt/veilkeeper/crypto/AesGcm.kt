package id.quezacolt.veilkeeper.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM authenticated encryption, used to wrap/unwrap the
 * VaultDataKey (CLAUDE.md Resolved Design Decision #1) and, from Sprint 2
 * onward, vault item payloads (SPEC-BASE.md Section 9.1). Backed entirely
 * by [javax.crypto.Cipher] -- already in the Android/JDK standard library,
 * no extra dependency needed.
 *
 * Wire format: `nonce (12 bytes) || ciphertext (includes the 16-byte GCM
 * tag)`. The nonce is generated fresh via [SecureRandom] on every call to
 * [encrypt] -- SPEC-BASE.md Section 47 explicitly requires verifying
 * nonce/IV uniqueness (see AesGcmTest).
 */
object AesGcm {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val KEY_LENGTH_BYTES = 32 // AES-256
    const val NONCE_LENGTH_BYTES = 12 // NIST SP 800-38D recommended GCM nonce size
    private const val TAG_LENGTH_BITS = 128

    private val secureRandom = SecureRandom()

    /**
     * Encrypts [plaintext] with [key] (must be [KEY_LENGTH_BYTES] bytes),
     * returning `nonce || ciphertext+tag`.
     */
    fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray? = null): ByteArray {
        require(key.size == KEY_LENGTH_BYTES) { "AesGcm: key must be $KEY_LENGTH_BYTES bytes, got ${key.size}" }

        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        associatedData?.let(cipher::updateAAD)

        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    /**
     * Decrypts a blob produced by [encrypt]. Throws
     * [javax.crypto.AEADBadTagException] (a [java.security.GeneralSecurityException])
     * if the ciphertext was tampered with or the wrong key is used --
     * callers must not swallow that distinction.
     */
    fun decrypt(key: ByteArray, nonceAndCiphertext: ByteArray, associatedData: ByteArray? = null): ByteArray {
        require(key.size == KEY_LENGTH_BYTES) { "AesGcm: key must be $KEY_LENGTH_BYTES bytes, got ${key.size}" }
        require(nonceAndCiphertext.size > NONCE_LENGTH_BYTES) { "AesGcm: input too short to contain a nonce" }

        val nonce = nonceAndCiphertext.copyOfRange(0, NONCE_LENGTH_BYTES)
        val ciphertext = nonceAndCiphertext.copyOfRange(NONCE_LENGTH_BYTES, nonceAndCiphertext.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        associatedData?.let(cipher::updateAAD)

        return cipher.doFinal(ciphertext)
    }
}
