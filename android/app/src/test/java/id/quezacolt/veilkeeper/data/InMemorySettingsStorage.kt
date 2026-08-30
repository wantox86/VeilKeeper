package id.quezacolt.veilkeeper.data

/** In-memory [SettingsStorage] fake for host-JVM unit tests -- no real [android.content.SharedPreferences]. */
class InMemorySettingsStorage : SettingsStorage {
    private val values = mutableMapOf<String, String>()

    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String) {
        values[key] = value
    }
}
