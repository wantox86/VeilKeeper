package id.quezacolt.veilkeeper.crypto

import java.security.MessageDigest

/**
 * Deterministic stand-in for [Argon2idMasterKeyDeriver] used in host-JVM
 * unit tests. NOT a real KDF (no memory-hardness, no iteration cost) --
 * see [Argon2idMasterKeyDeriver]'s doc comment for why the real
 * implementation cannot run here at all (native Android .so). This fake
 * only needs to behave like a deterministic function of
 * (password, salt, params) so the rest of the pipeline (HKDF, AES-GCM,
 * wrap/unwrap orchestration) can be exercised end-to-end.
 */
class FakeMasterKeyDeriver : MasterKeyDeriver {
    override fun deriveMasterKey(password: ByteArray, kdfSalt: ByteArray, params: KdfParams): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(password)
        digest.update(kdfSalt)
        digest.update(params.memoryKiB.toString().toByteArray())
        digest.update(params.iterations.toString().toByteArray())
        digest.update(params.parallelism.toString().toByteArray())
        return digest.digest()
    }
}
