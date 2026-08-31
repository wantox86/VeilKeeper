package id.quezacolt.veilkeeper.crypto

import javax.crypto.Cipher

/**
 * Abstraction over the cipher used to encrypt/decrypt the persisted session
 * blob (see [id.quezacolt.veilkeeper.data.PersistedSessionStore]), added for
 * Post-launch fixes batch 2 item #1 (swiping the app from recent-apps must
 * show "Unlock", not force a fresh Login/Register).
 *
 * Same interface-for-testability pattern already used in this codebase for
 * Android crypto that cannot run on the host JVM: [MasterKeyDeriver]
 * (Sprint 1, Argon2id) and [KeystoreVdkCipher] (Sprint 3, biometric VDK
 * cache) are the precedents. The real Android Keystore provider
 * ([KeystoreSessionCipher]) needs a real device/emulator; tests substitute a
 * fake plain-AES implementation so [id.quezacolt.veilkeeper.data.PersistedSessionStore]'s
 * actual serialize/encrypt/decrypt/deserialize logic is still exercised for
 * real on the host JVM.
 */
interface SessionCipherProvider {
    /** A Cipher initialized for ENCRYPT_MODE. Caller must read [Cipher.getIV] afterward and store it alongside the ciphertext. */
    fun encryptCipher(): Cipher

    /** A Cipher initialized for DECRYPT_MODE with the given (previously stored) IV. */
    fun decryptCipher(iv: ByteArray): Cipher

    /** Deletes the underlying key, if any -- any previously encrypted blob becomes permanently undecryptable, by design (used on full logout). */
    fun deleteKey()
}
