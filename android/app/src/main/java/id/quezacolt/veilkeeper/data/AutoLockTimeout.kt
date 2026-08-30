package id.quezacolt.veilkeeper.data

/**
 * SPEC-BASE.md Section 24 example settings. "Exact options may evolve" per
 * the spec, but these four cover the given example exactly -- no need to
 * invent more.
 */
enum class AutoLockTimeout(val millis: Long, val label: String) {
    IMMEDIATE(0L, "Immediately"),
    ONE_MINUTE(60_000L, "1 minute"),
    FIVE_MINUTES(5 * 60_000L, "5 minutes"),
    FIFTEEN_MINUTES(15 * 60_000L, "15 minutes"),
    ;

    companion object {
        val DEFAULT = FIVE_MINUTES

        fun fromName(name: String?): AutoLockTimeout = entries.find { it.name == name } ?: DEFAULT
    }
}

/**
 * SPEC-BASE.md Section 23 "configurable short period" for clipboard
 * auto-clear. Kept as a small fixed set (like [AutoLockTimeout]) rather than
 * a free-form input -- consistent with the rest of this Settings screen and
 * Section 56 Rule 1 (don't overbuild).
 */
enum class ClipboardClearDelay(val millis: Long, val label: String) {
    FIFTEEN_SECONDS(15_000L, "15 seconds"),
    THIRTY_SECONDS(30_000L, "30 seconds"),
    SIXTY_SECONDS(60_000L, "60 seconds"),
    ;

    companion object {
        val DEFAULT = THIRTY_SECONDS

        fun fromName(name: String?): ClipboardClearDelay = entries.find { it.name == name } ?: DEFAULT
    }
}
