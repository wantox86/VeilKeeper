package id.quezacolt.veilkeeper.data

import android.content.Context
import java.util.UUID

/**
 * A stable, random, non-secret per-install identifier sent as
 * `device_identifier` on login (SPEC-BASE.md Section 31 `devices` table).
 * Not sensitive (it's just an opaque label the server uses to distinguish
 * this install from others for the same account), so plain
 * [android.content.SharedPreferences] is sufficient -- no Keystore/encrypted
 * storage needed, unlike the session token/VDK (see [AuthSessionHolder]).
 */
object DeviceIdentity {
    private const val PREFS_NAME = "veilkeeper_device"
    private const val KEY_DEVICE_ID = "device_identifier"

    fun getOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }
}
