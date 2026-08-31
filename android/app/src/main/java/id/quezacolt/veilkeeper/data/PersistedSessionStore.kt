package id.quezacolt.veilkeeper.data

import android.content.Context
import id.quezacolt.veilkeeper.crypto.KdfParams
import id.quezacolt.veilkeeper.crypto.KeystoreSessionCipher
import id.quezacolt.veilkeeper.crypto.SessionCipherProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

/** Real [SettingsStorage] backed by its own [android.content.SharedPreferences] file, separate from [SharedPrefsSettingsStorage]'s. */
class SharedPrefsSessionStorage(context: Context) : SettingsStorage {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    companion object {
        private const val PREFS_NAME = "veilkeeper_session"
    }
}

/** What [PersistedSessionStore.load] returns: everything [AuthSessionHolder.restoreLocked] needs to reach state (b) below. */
data class PersistedSession(
    val sessionToken: String,
    val unwrapMaterial: VdkUnwrapMaterial,
    val email: String?,
)

@Serializable
private data class PersistedSessionDto(
    val sessionToken: String,
    val kdfSalt: String,
    val kdfMemoryKiB: Int,
    val kdfIterations: Int,
    val kdfParallelism: Int,
    val wrappedVdk: String,
    val email: String? = null,
)

/**
 * Post-launch fixes batch 2, item #1 -- root-cause fix for "swiping the app
 * from recent-apps logs the user out completely instead of just locking
 * it."
 *
 * **Root cause** (confirmed by re-reading Sprint 1/3): [AuthSessionHolder]
 * was, by design back then, in-memory only -- when Android kills the app
 * process (which a recent-apps swipe does, unlike a normal background/
 * `onStop`), the session token, [VdkUnwrapMaterial], and email were all
 * lost with it. `MainActivity`'s `NavHost` always started at the Login
 * route with no way to tell "never logged in" apart from "was logged in,
 * process just got killed" -- so the user always landed back on Login,
 * which then requires a full re-login (defeating the whole point of
 * Sprint 3's offline password/biometric unlock, which assumed the process
 * was still alive).
 *
 * **Fix**: persist exactly the non-VDK material needed to reach the
 * *locked* state after a fresh process start, encrypted at rest via
 * [SessionCipherProvider] (see [KeystoreSessionCipher]'s doc comment for
 * why this specific data is safe to persist and why the key is not
 * biometric-gated). On successful login/register+unwrap
 * ([AuthRepository.login]), this is saved; on full logout
 * ([AuthRepository.logout]), it is cleared. **The actual VaultDataKey is
 * never written here** -- only the session token and the already-non-secret
 * `kdf_salt`/`kdf_params`/`wrapped_vdk` triple. Restoring this at process
 * start (see `VeilKeeperApplication.onCreate` -> `AuthSessionHolder.restoreLocked`)
 * puts the app in the `LOCKED` state, not `UNLOCKED` -- the VDK still has
 * to be re-derived via a real password entry or a real biometric prompt
 * (gated by the *separate*, still biometric-required [id.quezacolt.veilkeeper.crypto.KeystoreVdkCipher]/
 * [BiometricVaultCache]), exactly like today's existing auto-lock unlock
 * flow. This does **not** weaken security -- it only makes the *existing*
 * offline-unlock capability (already shipped in Sprint 3 for the
 * backgrounded-but-not-killed case) also work across a full process kill,
 * which is what SPEC-BASE.md's "locked, not logged out" auto-lock
 * requirement always implied but Sprint 3 didn't get to verify (no
 * emulator was available then to catch the process-death gap).
 */
class PersistedSessionStore(
    private val storage: SettingsStorage,
    private val cipherProvider: SessionCipherProvider = KeystoreSessionCipher,
) {
    /** Encrypts and persists everything needed to restore a `LOCKED` session after a process restart. */
    fun save(sessionToken: String, material: VdkUnwrapMaterial, email: String?) {
        val dto = PersistedSessionDto(
            sessionToken = sessionToken,
            kdfSalt = material.kdfSalt.b64(),
            kdfMemoryKiB = material.kdfParams.memoryKiB,
            kdfIterations = material.kdfParams.iterations,
            kdfParallelism = material.kdfParams.parallelism,
            wrappedVdk = material.wrappedVdk.b64(),
            email = email,
        )
        val plaintext = json.encodeToString(dto).toByteArray(Charsets.UTF_8)
        val cipher = cipherProvider.encryptCipher()
        val ciphertext = cipher.doFinal(plaintext)
        val iv = cipher.iv
        storage.putString(KEY_BLOB, Base64.getEncoder().encodeToString(iv + ciphertext))
        storage.putString(KEY_IV_LEN, iv.size.toString())
    }

    /**
     * Decrypts and returns the persisted session, or `null` if there is
     * none, or if it could not be decrypted (e.g. corrupted, or the
     * underlying Keystore key is gone -- treated the same as "no session",
     * never as a crash; the stale blob is cleared so a future [load] does
     * not keep re-attempting a doomed decrypt).
     */
    fun load(): PersistedSession? {
        val blobB64 = storage.getString(KEY_BLOB)?.takeIf { it.isNotBlank() } ?: return null
        val ivLen = storage.getString(KEY_IV_LEN)?.toIntOrNull()?.takeIf { it > 0 } ?: return null

        return runCatching {
            val blob = Base64.getDecoder().decode(blobB64)
            val iv = blob.copyOfRange(0, ivLen)
            val ciphertext = blob.copyOfRange(ivLen, blob.size)
            val plaintext = cipherProvider.decryptCipher(iv).doFinal(ciphertext)
            val dto = json.decodeFromString<PersistedSessionDto>(String(plaintext, Charsets.UTF_8))
            PersistedSession(
                sessionToken = dto.sessionToken,
                unwrapMaterial = VdkUnwrapMaterial(
                    kdfSalt = dto.kdfSalt.fromB64(),
                    kdfParams = KdfParams(dto.kdfMemoryKiB, dto.kdfIterations, dto.kdfParallelism),
                    wrappedVdk = dto.wrappedVdk.fromB64(),
                ),
                email = dto.email,
            )
        }.getOrElse {
            clear()
            null
        }
    }

    /** Wipes the persisted blob and the underlying key -- called on full logout. */
    fun clear() {
        storage.putString(KEY_BLOB, "")
        storage.putString(KEY_IV_LEN, "")
        cipherProvider.deleteKey()
    }

    companion object {
        private const val KEY_BLOB = "persisted_session_blob"
        private const val KEY_IV_LEN = "persisted_session_iv_len"
        private val json = Json { ignoreUnknownKeys = true }
    }
}

private fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)
private fun String.fromB64(): ByteArray = Base64.getDecoder().decode(this)
