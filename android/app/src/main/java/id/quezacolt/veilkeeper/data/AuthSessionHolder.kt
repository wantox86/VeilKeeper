package id.quezacolt.veilkeeper.data

import id.quezacolt.veilkeeper.crypto.KdfParams
import id.quezacolt.veilkeeper.crypto.VaultCrypto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lock state surfaced to the UI (Sprint 3, SPEC-BASE.md Section 24). */
enum class VaultLockState {
    /** No session at all -- show the Login screen. */
    LOGGED_OUT,

    /** Session + unlock material present, but the VDK has been cleared from memory. */
    LOCKED,

    /** VDK present in memory -- vault screens are usable. */
    UNLOCKED,
}

/**
 * Non-secret material needed to re-derive (unwrap) the VaultDataKey locally,
 * without a network round-trip, after an auto-lock (SPEC-BASE.md Section 24).
 * None of these values are secret on their own -- `wrappedVdk` is opaque
 * ciphertext already sent to/from the server, and `kdfSalt`/`kdfParams` are
 * sent unauthenticated by `/auth/prelogin` to any caller. Keeping them here
 * (in memory only, alongside the session token) lets a password-based unlock
 * re-derive WrapKey and unwrap the *same* VDK entirely offline, matching the
 * zero-knowledge design in CLAUDE.md Decision #1 -- it is NOT a fresh login,
 * so it never touches [AuthApi].
 */
data class VdkUnwrapMaterial(
    val kdfSalt: ByteArray,
    val kdfParams: KdfParams,
    val wrappedVdk: ByteArray,
)

/**
 * In-memory holder for the current session token, unwrapped VaultDataKey,
 * and (Sprint 3) the lock state + non-secret material needed to re-unlock
 * without hitting the network.
 *
 * Still intentionally does NOT persist across process death (see Sprint 1
 * doc comment history) -- if the OS kills the process, the user returns to
 * the Login screen. The one exception is the Keystore-backed biometric VDK
 * cache ([BiometricVaultCache]), which is a separate, deliberately-persisted
 * store precisely because that's the point of biometric unlock surviving a
 * restart -- see CLAUDE.md Resolved Design Decision #3.
 */
object AuthSessionHolder {
    @Volatile
    var sessionToken: String? = null
        private set

    @Volatile
    var vaultDataKey: ByteArray? = null
        private set

    @Volatile
    var unwrapMaterial: VdkUnwrapMaterial? = null
        private set

    @Volatile
    var email: String? = null
        private set

    private val _lockState = MutableStateFlow(VaultLockState.LOGGED_OUT)
    val lockState: StateFlow<VaultLockState> = _lockState.asStateFlow()

    /** Called right after a successful login/register+unwrap. */
    fun set(sessionToken: String, vaultDataKey: ByteArray, unwrapMaterial: VdkUnwrapMaterial? = null, email: String? = null) {
        clearVdk()
        this.sessionToken = sessionToken
        this.vaultDataKey = vaultDataKey
        this.unwrapMaterial = unwrapMaterial
        this.email = email
        _lockState.value = VaultLockState.UNLOCKED
    }

    /**
     * Auto-lock (SPEC-BASE.md Section 24): clears the in-memory VDK only.
     * The session token and [unwrapMaterial] are kept so the Unlock screen
     * can restore access via password (offline) or biometric, without a
     * fresh login. No-op if already logged out.
     */
    fun lock() {
        if (_lockState.value == VaultLockState.LOGGED_OUT) return
        clearVdk()
        _lockState.value = VaultLockState.LOCKED
    }

    /** Restores the VDK (from password re-derivation or biometric decrypt) without a new session. */
    fun unlock(vaultDataKey: ByteArray) {
        check(sessionToken != null) { "cannot unlock: no active session" }
        this.vaultDataKey = vaultDataKey
        _lockState.value = VaultLockState.UNLOCKED
    }

    /** Full logout: clears everything, including unwrap material and session token. */
    fun clear() {
        clearVdk()
        sessionToken = null
        unwrapMaterial = null
        email = null
        _lockState.value = VaultLockState.LOGGED_OUT
    }

    private fun clearVdk() {
        vaultDataKey?.let { VaultCrypto.wipe(it) }
        vaultDataKey = null
    }
}
