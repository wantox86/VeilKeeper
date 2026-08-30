package id.quezacolt.veilkeeper.crypto

/**
 * Derives MasterKey = Argon2id(password, kdf_salt, kdf_params) per CLAUDE.md
 * Resolved Design Decision #1.
 *
 * This is an interface (rather than a plain object, unlike [Hkdf]/[AesGcm])
 * specifically so [VaultCrypto] and the auth ViewModels/repository can be
 * unit-tested on the host JVM with a fake implementation -- see
 * [Argon2idMasterKeyDeriver]'s doc comment for why the *real* implementation
 * cannot run in local (`testDebugUnitTest`) unit tests at all.
 */
fun interface MasterKeyDeriver {
    /**
     * @param password UTF-8 encoded password bytes. Callers are responsible
     *                 for wiping this array after use.
     * @param kdfSalt Salt bytes (16+ bytes; from /auth/prelogin or freshly
     *                generated at registration).
     * @param params Argon2id cost parameters.
     * @return A 32-byte MasterKey. Never transmitted; only used locally to
     *         derive AuthKey/WrapKey (see [VaultCrypto]).
     */
    fun deriveMasterKey(password: ByteArray, kdfSalt: ByteArray, params: KdfParams): ByteArray
}
