package id.quezacolt.veilkeeper.crypto

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HKDF-SHA256 (RFC 5869), used to derive the domain-separated AuthKey and
 * WrapKey subkeys from MasterKey (CLAUDE.md Resolved Design Decision #1).
 *
 * Not available as a ready-made primitive in the Android/JDK standard
 * library (unlike AES-GCM via [javax.crypto.Cipher] or HMAC via
 * [javax.crypto.Mac], which this *is* built on), but HMAC-SHA256 is --
 * per SPEC-BASE.md Section 56 Rule 7 ("verify whether the ... standard
 * library ... can solve the problem" before adding a dependency), a ~30
 * line RFC-faithful implementation over stdlib Mac is preferable to pulling
 * in a general-purpose crypto library (e.g. Tink) for one function.
 */
object Hkdf {
    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HASH_LENGTH = 32

    /**
     * HKDF-Extract followed by HKDF-Expand, producing [outputLength] bytes.
     *
     * @param ikm Input keying material (here: MasterKey, already
     *            high-entropy from Argon2id, so an all-zero salt is used
     *            for Extract -- standard practice per RFC 5869 Section 3.1
     *            when a salt value isn't otherwise available/needed for
     *            additional entropy concentration).
     * @param info Domain-separation context (e.g. "veilkeeper:auth:v1").
     */
    fun deriveKey(ikm: ByteArray, info: ByteArray, outputLength: Int = HASH_LENGTH): ByteArray {
        val salt = ByteArray(HASH_LENGTH) // zero-filled, see doc comment above
        val prk = extract(salt, ikm)
        return expand(prk, info, outputLength)
    }

    private fun extract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(salt, HMAC_ALGORITHM))
        return mac.doFinal(ikm)
    }

    private fun expand(prk: ByteArray, info: ByteArray, outputLength: Int): ByteArray {
        require(outputLength in 1..(255 * HASH_LENGTH)) { "HKDF: invalid output length $outputLength" }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(prk, HMAC_ALGORITHM))

        val output = ByteArray(outputLength)
        var previousBlock = ByteArray(0)
        var generated = 0
        var counter = 1

        while (generated < outputLength) {
            mac.reset()
            mac.update(previousBlock)
            mac.update(info)
            mac.update(counter.toByte())
            val block = mac.doFinal()

            val toCopy = minOf(block.size, outputLength - generated)
            System.arraycopy(block, 0, output, generated, toCopy)

            generated += toCopy
            previousBlock = block
            counter++
        }

        return output
    }
}
