package id.quezacolt.veilkeeper.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * SPEC-BASE.md Section 23 "Clipboard Security": copies a secret to the
 * clipboard and automatically clears it after [clearAfterMillis]. Never
 * logs the copied value (it only ever passes through [ClipboardPort] and a
 * local variable here) -- SPEC-BASE.md Section 56 Rule 6 / CLAUDE.md's
 * "no plaintext secret/VDK/key material in logs" applies to clipboard
 * content too.
 *
 * The clear is skipped if the clipboard content changed since the copy
 * (e.g. the user copied something else in the meantime) -- comparing
 * current clipboard text against what we just set, not overwriting blindly.
 */
class ClipboardSecurity(
    private val clipboard: ClipboardPort,
    private val scope: CoroutineScope,
) {
    /** Guards against a stale delayed-clear firing after a newer copy superseded it. */
    @Volatile
    private var currentToken: Any? = null

    fun copyAndScheduleClear(label: String, value: String, clearAfterMillis: Long) {
        clipboard.setSensitiveText(label, value)
        val token = Any()
        currentToken = token

        if (clearAfterMillis > 0) {
            scope.launch {
                delay(clearAfterMillis)
                // Only clear if (a) no newer copy superseded this one, and
                // (b) the clipboard still holds exactly what we put there.
                if (currentToken === token && clipboard.currentText() == value) {
                    clipboard.clear()
                }
            }
        }
    }
}
