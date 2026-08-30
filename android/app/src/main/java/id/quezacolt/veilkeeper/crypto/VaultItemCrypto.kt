package id.quezacolt.veilkeeper.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A single content block within a vault item (SPEC-BASE.md Section 13).
 * [type] is one of "text", "secret", "note" for Sprint 2 (image is Sprint
 * 5 -- attachments are out of scope). [label] is optional (notes typically
 * don't have one).
 */
@Serializable
data class ContentBlockDto(
    val type: String,
    val label: String? = null,
    val value: String,
)

/** The full plaintext logical structure of a vault item (SPEC-BASE.md Section 13). */
@Serializable
data class VaultItemPayload(
    val title: String,
    val content: List<ContentBlockDto>,
)

/**
 * Encrypts/decrypts a [VaultItemPayload] end-to-end with the VaultDataKey
 * (VDK), so the exact same JSON schema decided in CLAUDE.md/SPEC-BASE.md
 * Section 13 is what round-trips through the server as opaque ciphertext.
 * Server never sees [VaultItemPayload] -- only the output of [encrypt].
 */
object VaultItemCrypto {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    /** Serializes [payload] to UTF-8 JSON and encrypts it with [vdk] (AES-256-GCM, fresh nonce). */
    fun encrypt(vdk: ByteArray, payload: VaultItemPayload): ByteArray {
        val plaintext = json.encodeToString(VaultItemPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        return AesGcm.encrypt(vdk, plaintext)
    }

    /**
     * Decrypts [blob] (as produced by [encrypt], or by the server round-trip
     * of the same bytes) with [vdk] and parses the resulting JSON back into
     * a [VaultItemPayload]. Throws on tamper/wrong-key (from [AesGcm]) or
     * malformed JSON.
     */
    fun decrypt(vdk: ByteArray, blob: ByteArray): VaultItemPayload {
        val plaintext = AesGcm.decrypt(vdk, blob)
        return json.decodeFromString(VaultItemPayload.serializer(), String(plaintext, Charsets.UTF_8))
    }
}
