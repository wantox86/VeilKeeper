package id.quezacolt.veilkeeper.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android Keystore-backed AES-256-GCM key protecting the persisted session
 * blob (Post-launch fixes batch 2, item #1: swiping the app from
 * recent-apps must show "Unlock", not force a fresh Login/Register). See
 * [id.quezacolt.veilkeeper.data.PersistedSessionStore]'s doc comment for the
 * full design rationale of *what* is persisted and why.
 *
 * Deliberately `setUserAuthenticationRequired` is left at its default
 * (`false`) -- unlike [KeystoreVdkCipher]'s biometric-gated key. This blob
 * must be readable at cold app start, before any authentication (password
 * or biometric) has happened yet, purely to answer "does a locked session
 * exist" (state (b) of the Login/Unlock/Home startup state machine) so the
 * app can route to the Unlock screen instead of Login. Gating it behind
 * biometric would make password-only unlock (always required as a
 * fallback, even when biometric is enrolled) impossible to reach after a
 * process kill. This is safe because the blob never contains the actual
 * VDK -- only a bearer session token plus already-non-secret VDK-unwrap
 * material (`kdf_salt`/`kdf_params`/`wrapped_vdk`, all routinely sent
 * to/from the server unauthenticated already, see CLAUDE.md Decision #1)
 * -- unwrapping the real vault key still always requires a real password or
 * biometric authentication afterward, exactly as before this fix.
 * Encrypting it at rest via Keystore (vs. plain SharedPreferences, which is
 * what CLAUDE.md's own precedent -- [DeviceIdentity] -- uses for truly
 * non-sensitive data) is still a meaningful improvement over plaintext for
 * the one genuinely sensitive field here (the session token, a bearer
 * credential) against casual on-device file inspection.
 *
 * Not unit-testable on the host JVM (no real Android Keystore provider
 * there) -- same disclosed gap category as [KeystoreVdkCipher] since
 * Sprint 3. [id.quezacolt.veilkeeper.data.PersistedSessionStore] depends on
 * the [SessionCipherProvider] interface instead of this object directly, so
 * its own logic is still fully unit-tested via a fake.
 */
object KeystoreSessionCipher : SessionCipherProvider {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "veilkeeper_session_cache_key_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_LENGTH_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun secretKey(): SecretKey {
        val ks = keyStore()
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return generateKey()
    }

    private fun generateKey(): SecretKey {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    override fun encryptCipher(): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher
    }

    override fun decryptCipher(iv: ByteArray): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher
    }

    override fun deleteKey() {
        val ks = keyStore()
        if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
    }
}
