package id.quezacolt.veilkeeper.data

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import id.quezacolt.veilkeeper.crypto.KeystoreVdkCipher

/**
 * Ties [androidx.biometric.BiometricPrompt] to [KeystoreVdkCipher] +
 * [BiometricVaultCache] for SPEC-BASE.md Section 25 / CLAUDE.md Resolved
 * Design Decision #3.
 *
 * Deliberately not unit-testable on the host JVM -- BiometricPrompt requires
 * a real [FragmentActivity] and the Android Keystore provider, neither of
 * which exist there. Same disclosed gap category as
 * `Argon2idMasterKeyDeriverInstrumentedTest` in Sprint 1: this should be
 * exercised manually / via `connectedAndroidTest` on a real device or
 * emulator with an enrolled biometric before shipping. The pieces it calls
 * ([BiometricVaultCache], [AuthSessionHolder]) are unit-tested in isolation
 * with fakes.
 */
class VaultBiometricManager(private val cache: BiometricVaultCache) {

    /** True if the device has at least one biometric enrolled and Keystore-backed strong auth is available. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(): Boolean = cache.isEnabled()

    /**
     * Opt-in enrollment: generates a fresh Keystore key, prompts for
     * biometric confirmation, and on success encrypts+caches [vdk] (the
     * currently-unwrapped VaultDataKey -- caller must already be unlocked).
     * Never logs [vdk].
     */
    fun enroll(activity: FragmentActivity, vdk: ByteArray, onResult: (Result<Unit>) -> Unit) {
        val newKey = runCatching { KeystoreVdkCipher.generateKey() }
        if (newKey.isFailure) {
            onResult(Result.failure(newKey.exceptionOrNull() ?: IllegalStateException("key generation failed")))
            return
        }
        val cipher = runCatching { KeystoreVdkCipher.encryptCipher() }.getOrElse {
            onResult(Result.failure(it))
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authedCipher = result.cryptoObject?.cipher
                    if (authedCipher == null) {
                        onResult(Result.failure(IllegalStateException("no authenticated cipher returned")))
                        return
                    }
                    val stored = runCatching { cache.store(authedCipher, vdk) }
                    onResult(stored)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(Result.failure(BiometricUnavailableException(errString.toString())))
                }

                override fun onAuthenticationFailed() {
                    // A single failed attempt (e.g. unrecognized fingerprint) -- the
                    // prompt stays open for retry, so no terminal callback here.
                }
            },
        )
        prompt.authenticate(enrollPromptInfo(), BiometricPrompt.CryptoObject(cipher))
    }

    /** Prompts for biometric confirmation and, on success, decrypts and restores the cached VDK into [AuthSessionHolder]. */
    fun unlock(activity: FragmentActivity, onResult: (Result<Unit>) -> Unit) {
        val iv = cache.cachedIv()
        if (iv == null) {
            onResult(Result.failure(IllegalStateException("biometric unlock not enrolled")))
            return
        }
        val cipher = runCatching { KeystoreVdkCipher.decryptCipher(iv) }.getOrElse {
            onResult(Result.failure(it))
            return
        }

        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authedCipher = result.cryptoObject?.cipher
                    if (authedCipher == null) {
                        onResult(Result.failure(IllegalStateException("no authenticated cipher returned")))
                        return
                    }
                    val outcome = runCatching {
                        val vdk = cache.decrypt(authedCipher)
                        AuthSessionHolder.unlock(vdk)
                    }
                    onResult(outcome)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    onResult(Result.failure(BiometricUnavailableException(errString.toString())))
                }

                override fun onAuthenticationFailed() {
                    // Prompt stays open for retry.
                }
            },
        )
        prompt.authenticate(unlockPromptInfo(), BiometricPrompt.CryptoObject(cipher))
    }

    /** Opt-out: wipes the cached blob and the underlying Keystore key. */
    fun disable() {
        cache.clear()
    }

    private fun enrollPromptInfo() = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Enable biometric unlock")
        .setSubtitle("Confirm your identity to protect your vault key on this device")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Cancel")
        .build()

    private fun unlockPromptInfo() = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Unlock VeilKeeper")
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        .setNegativeButtonText("Use password instead")
        .build()

    class BiometricUnavailableException(message: String) : Exception(message)
}
