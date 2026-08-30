package id.quezacolt.veilkeeper.crypto

import java.security.SecureRandom
import java.util.Arrays

/**
 * Orchestrates the full client-side key hierarchy from CLAUDE.md Resolved
 * Design Decision #1:
 *
 * ```
 * password + kdf_salt + kdf_params
 *   --Argon2id-->  MasterKey (never transmitted)
 *   --HKDF(info="veilkeeper:auth:v1")-->  AuthKey   (sent to server instead of password)
 *   --HKDF(info="veilkeeper:wrap:v1")-->  WrapKey   (never transmitted)
 *
 * VaultDataKey (VDK) = random 32 bytes, generated once at registration
 * wrapped_vdk = AES-256-GCM_encrypt(VDK, key=WrapKey)
 * ```
 *
 * Takes a [MasterKeyDeriver] rather than calling [Argon2idMasterKeyDeriver]
 * directly so this class (and anything built on it) is unit-testable on the
 * host JVM -- see that class's doc comment for why the real Argon2
 * implementation itself cannot run there.
 */
class VaultCrypto(private val masterKeyDeriver: MasterKeyDeriver) {

    /** Derives MasterKey. Callers should invoke this off the main thread. */
    fun deriveMasterKey(password: ByteArray, kdfSalt: ByteArray, params: KdfParams): ByteArray =
        masterKeyDeriver.deriveMasterKey(password, kdfSalt, params)

    fun deriveAuthKey(masterKey: ByteArray): ByteArray = Hkdf.deriveKey(masterKey, AUTH_KEY_INFO)

    fun deriveWrapKey(masterKey: ByteArray): ByteArray = Hkdf.deriveKey(masterKey, WRAP_KEY_INFO)

    /** Generates a fresh, random VaultDataKey (registration only -- never re-derived). */
    fun generateVaultDataKey(): ByteArray =
        ByteArray(AesGcm.KEY_LENGTH_BYTES).also(SecureRandom()::nextBytes)

    fun wrapVaultDataKey(vdk: ByteArray, wrapKey: ByteArray): ByteArray = AesGcm.encrypt(wrapKey, vdk)

    fun unwrapVaultDataKey(wrappedVdk: ByteArray, wrapKey: ByteArray): ByteArray = AesGcm.decrypt(wrapKey, wrappedVdk)

    /** Generates a fresh random kdf_salt for a new registration (see AuthRepository doc comment). */
    fun generateKdfSalt(): ByteArray = ByteArray(KDF_SALT_LENGTH_BYTES).also(SecureRandom()::nextBytes)

    companion object {
        private val AUTH_KEY_INFO = "veilkeeper:auth:v1".toByteArray(Charsets.UTF_8)
        private val WRAP_KEY_INFO = "veilkeeper:wrap:v1".toByteArray(Charsets.UTF_8)
        const val KDF_SALT_LENGTH_BYTES = 16

        /**
         * Best-effort zeroing of sensitive byte arrays (MasterKey, WrapKey,
         * VDK, password bytes) once no longer needed. The JVM/GC gives no
         * hard guarantee against copies made along the way (String interning,
         * GC compaction before overwrite, etc.) -- this reduces the window
         * key material sits in memory, it does not make it disappear
         * instantly and unconditionally.
         */
        fun wipe(vararg secrets: ByteArray?) {
            for (s in secrets) {
                if (s != null) Arrays.fill(s, 0)
            }
        }
    }
}
