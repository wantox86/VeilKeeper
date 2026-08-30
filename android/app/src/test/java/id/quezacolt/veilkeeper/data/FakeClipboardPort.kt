package id.quezacolt.veilkeeper.data

/** In-memory [ClipboardPort] fake for host-JVM unit tests -- no real Android clipboard service. */
class FakeClipboardPort : ClipboardPort {
    private var text: String? = null
    var clearCallCount = 0
        private set

    override fun setSensitiveText(label: String, value: String) {
        text = value
    }

    override fun currentText(): String? = text

    override fun clear() {
        clearCallCount++
        text = null
    }

    /** Test-only helper simulating the user copying something else in the meantime. */
    fun simulateExternalCopy(value: String) {
        text = value
    }
}
