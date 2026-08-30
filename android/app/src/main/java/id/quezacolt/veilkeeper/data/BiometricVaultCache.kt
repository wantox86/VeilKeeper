package id.quezacolt.veilkeeper.data

import android.content.Context
import id.quezacolt.veilkeeper.crypto.KeystoreVdkCipher
import java.util.Base64
import javax.crypto.Cipher

/**
 * Persisted (survives process death/app restart) storage for the
 * biometric-unlock VDK cache (SPEC-BASE.md Section 25, CLAUDE.md Resolved
 * Design Decision #3): `nonce (IV) || ciphertext+tag`, where the VDK was
 * encrypted with [KeystoreVdkCipher]'s biometric-gated Android Keystore key.
 *
 * Only ciphertext is stored here -- plain [android.content.SharedPreferences]
 * is fine for that (same reasoning as [DeviceIdentity]: nothing sensitive is
 * readable from the blob without the Keystore key, and that key itself
 * cannot be extracted from the secure hardware even with root/backup access).
 * This class never sees or logs the plaintext VDK.
 */
class BiometricVaultCache(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Whether the user has opted in and a cached blob currently exists. */
    fun isEnabled(): Boolean = prefs.contains(KEY_BLOB) && KeystoreVdkCipher.keyExists()

    /**
     * Encrypts and stores [vdk] using an already-authenticated [cipher]
     * (obtained via a successful [androidx.biometric.BiometricPrompt]
     * enrollment against [KeystoreVdkCipher.encryptCipher]). Does not touch
     * or log the plaintext VDK beyond this call.
     */
    fun store(cipher: Cipher, vdk: ByteArray) {
        val ciphertext = cipher.doFinal(vdk)
        val iv = cipher.iv
        val blob = iv + ciphertext
        prefs.edit()
            .putString(KEY_BLOB, Base64.getEncoder().encodeToString(blob))
            .putInt(KEY_IV_LEN, iv.size)
            .apply()
    }

    /** Decrypts the cached VDK using an already-authenticated [cipher] (from [KeystoreVdkCipher.decryptCipher]). */
    fun decrypt(cipher: Cipher): ByteArray {
        val blob = Base64.getDecoder().decode(prefs.getString(KEY_BLOB, null) ?: error("no cached biometric blob"))
        val ivLen = prefs.getInt(KEY_IV_LEN, -1).takeIf { it > 0 } ?: error("no cached IV length")
        val ciphertext = blob.copyOfRange(ivLen, blob.size)
        return cipher.doFinal(ciphertext)
    }

    /** The IV to build a decrypt [Cipher] with -- needed before the biometric prompt is shown. */
    fun cachedIv(): ByteArray? {
        val blob = prefs.getString(KEY_BLOB, null)?.let { Base64.getDecoder().decode(it) } ?: return null
        val ivLen = prefs.getInt(KEY_IV_LEN, -1).takeIf { it > 0 } ?: return null
        return blob.copyOfRange(0, ivLen)
    }

    /** Disables biometric unlock: wipes both the stored blob and the underlying Keystore key. */
    fun clear() {
        prefs.edit().remove(KEY_BLOB).remove(KEY_IV_LEN).apply()
        KeystoreVdkCipher.deleteKey()
    }

    companion object {
        private const val PREFS_NAME = "veilkeeper_biometric_cache"
        private const val KEY_BLOB = "vdk_blob"
        private const val KEY_IV_LEN = "vdk_iv_len"
    }
}
