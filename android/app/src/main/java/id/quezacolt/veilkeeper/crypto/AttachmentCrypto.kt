package id.quezacolt.veilkeeper.crypto

/**
 * Encrypts/decrypts attachment bytes and filenames with the VaultDataKey
 * (VDK), mirroring [VaultItemCrypto]'s role for vault item payloads
 * (SPEC-BASE.md Section 17, Phase 5). Kept as a thin wrapper over [AesGcm]
 * (rather than calling it directly from [id.quezacolt.veilkeeper.data.VaultRepository])
 * for the same reason [VaultItemCrypto] exists: a single, well-documented,
 * independently-unit-tested place that owns "how attachment bytes get
 * encrypted", separate from network/orchestration concerns.
 *
 * The filename and the file content are encrypted as two independent
 * AES-256-GCM operations (each with its own fresh nonce, since [AesGcm]
 * generates one per call) -- there is no cryptographic requirement that
 * they share a nonce, and keeping them independent means either can be
 * re-encrypted/rotated without touching the other (not needed today, but a
 * natural consequence of not artificially coupling them).
 */
object AttachmentCrypto {
    /** Encrypts raw file bytes (already compressed, if applicable) with [vdk]. */
    fun encryptFile(vdk: ByteArray, plaintext: ByteArray): ByteArray = AesGcm.encrypt(vdk, plaintext)

    /** Decrypts a blob produced by [encryptFile]. Throws on tamper/wrong-key. */
    fun decryptFile(vdk: ByteArray, blob: ByteArray): ByteArray = AesGcm.decrypt(vdk, blob)

    /** Encrypts a filename (UTF-8) with [vdk] -- filenames are metadata that can leak information, so never sent in plaintext. */
    fun encryptFilename(vdk: ByteArray, filename: String): ByteArray =
        AesGcm.encrypt(vdk, filename.toByteArray(Charsets.UTF_8))

    /** Decrypts a blob produced by [encryptFilename] back into the original filename string. */
    fun decryptFilename(vdk: ByteArray, blob: ByteArray): String =
        String(AesGcm.decrypt(vdk, blob), Charsets.UTF_8)
}
