package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.VaultCrypto

/**
 * In-memory holder for the current session token and unwrapped
 * VaultDataKey.
 *
 * Sprint 1 scope note: this intentionally does NOT persist across process
 * death (no disk storage at all). CLAUDE.md Resolved Design Decision #3
 * ("Local device cache / biometric unlock") describes a Keystore-backed
 * encrypted-at-rest cache for the VDK -- that is explicitly Sprint 3 scope
 * ("Secure UX" phase, SPEC-BASE.md Phase 3). Building persistent session
 * storage now would be exactly the kind of premature scope creep
 * SPEC-BASE.md Section 56 Rule 3 ("small increments") warns against; for
 * Sprint 1 (Authentication only, no vault screens exist yet), keeping both
 * values in memory only, cleared on process death or explicit logout, is
 * the simplest correct behavior.
 */
object AuthSessionHolder {
    @Volatile
    var sessionToken: String? = null
        private set

    @Volatile
    var vaultDataKey: ByteArray? = null
        private set

    fun set(sessionToken: String, vaultDataKey: ByteArray) {
        clearVdk()
        this.sessionToken = sessionToken
        this.vaultDataKey = vaultDataKey
    }

    fun clear() {
        clearVdk()
        sessionToken = null
    }

    private fun clearVdk() {
        vaultDataKey?.let { VaultCrypto.wipe(it) }
        vaultDataKey = null
    }
}
