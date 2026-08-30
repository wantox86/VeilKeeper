package id.quezacolt.veilkeeper.crypto

/**
 * Argon2id parameters used to derive MasterKey = Argon2id(password, kdf_salt,
 * kdf_params), per repo CLAUDE.md Resolved Design Decision #1. Field names
 * mirror the backend's `KDFParams` struct (backend/internal/auth/kdf.go) and
 * the wire JSON keys (`memory`, `iterations`, `parallelism`) exactly, so DTOs
 * can convert 1:1 with no renaming surprises.
 */
data class KdfParams(
    val memoryKiB: Int,
    val iterations: Int,
    val parallelism: Int,
) {
    companion object {
        /**
         * Default parameters for a newly registered account: 64 MiB memory,
         * 3 iterations, 4-way parallelism -- the exact figures given as an
         * example in CLAUDE.md Resolved Design Decision #1. The client
         * generates kdf_salt itself at registration (see AuthRepository
         * doc comment for why) and sends these params alongside it; the
         * server stores them verbatim for future prelogin responses.
         */
        val DEFAULT = KdfParams(memoryKiB = 64 * 1024, iterations = 3, parallelism = 4)

        const val CURRENT_VERSION = 1
    }
}
