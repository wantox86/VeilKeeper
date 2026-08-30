package id.quezacolt.veilkeeper.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed AES-256-GCM key used to encrypt/decrypt the cached
 * VaultDataKey for biometric unlock (SPEC-BASE.md Section 25, CLAUDE.md
 * Resolved Design Decision #3). The key material itself never leaves the
 * secure hardware (StrongBox where available, TEE otherwise) -- this class
 * only ever gets a [Cipher] handle to it, never the raw key bytes.
 *
 * The key is generated with `setUserAuthenticationRequired(true)` and no
 * validity duration, i.e. the OS requires a *fresh* biometric authentication
 * for every single use (both wrap and unwrap) -- there is no time window
 * where the key can be used without the sensor firing. This matches
 * SPEC-BASE.md Section 25 ("Biometric authentication must NOT directly
 * authenticate against the backend" -- it gates a *local* key instead) and
 * is deliberately simpler than per-operation key variants: one alias, one
 * policy, for the one thing it protects (the cached VDK blob).
 *
 * Not unit-testable on the host JVM (no real Android Keystore provider there)
 * -- same category of gap as Argon2idMasterKeyDeriver in Sprint 1. See
 * BiometricVaultCache's doc comment for how the rest of the flow is still
 * covered by fakes.
 */
object KeystoreVdkCipher {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "veilkeeper_biometric_vdk_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    const val GCM_TAG_LENGTH_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    fun keyExists(): Boolean = keyStore().containsAlias(KEY_ALIAS)

    /** Deletes the Keystore key -- called when the user disables biometric unlock. */
    fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
    }

    /** (Re)generates the biometric-gated key. Any previously cached blob becomes permanently undecryptable, by design. */
    fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true) // new fingerprint/face enrolled -> old key (and cache) is dead
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        return ks.getKey(KEY_ALIAS, null) as? SecretKey
            ?: throw IllegalStateException("veilkeeper biometric key missing -- call generateKey() first")
    }

    /**
     * A Cipher initialized for ENCRYPT_MODE against the Keystore key, ready
     * to hand to [androidx.biometric.BiometricPrompt.CryptoObject]. The
     * caller must read [Cipher.getIV] after a successful biometric prompt
     * and store it alongside the ciphertext (GCM IV is not secret).
     */
    fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher
    }

    /** A Cipher initialized for DECRYPT_MODE with the given (previously stored) IV. */
    fun decryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher
    }
}
