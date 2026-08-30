package id.quezacolt.veilkeeper.data

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/**
 * Thin wrapper around [android.content.ClipboardManager] so [ClipboardSecurity]
 * is unit-testable on the host JVM (same "port" pattern as
 * [id.quezacolt.veilkeeper.crypto.MasterKeyDeriver] / [SettingsStorage]).
 */
interface ClipboardPort {
    fun setSensitiveText(label: String, value: String)
    fun currentText(): String?
    fun clear()
}

/**
 * Real implementation. On Android 13+ (API 33), marks the clip
 * `EXTRA_IS_SENSITIVE` so the system clipboard preview UI blurs/masks it
 * (SPEC-BASE.md Section 23's "where platform capabilities permit") and uses
 * the native [ClipboardManager.clearPrimaryClip] to clear. Below API 33,
 * clearing falls back to overwriting with an empty clip (no native "clear"
 * API exists pre-Tiramisu).
 */
class AndroidClipboardPort(context: Context) : ClipboardPort {
    private val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun setSensitiveText(label: String, value: String) {
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        manager.setPrimaryClip(clip)
    }

    override fun currentText(): String? = manager.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()

    override fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.clearPrimaryClip()
        } else {
            manager.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }
}
