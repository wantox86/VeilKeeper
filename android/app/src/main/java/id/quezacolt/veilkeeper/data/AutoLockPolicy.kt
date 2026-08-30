package id.quezacolt.veilkeeper.data

/**
 * Pure decision logic for SPEC-BASE.md Section 24 ("App goes to background" +
 * "Configurable timeout"), split out from [AutoLockManager] so it is
 * trivially unit-testable without any Android lifecycle machinery.
 */
object AutoLockPolicy {
    /**
     * @param backgroundedAtMillis when the app last went to background (from [AutoLockManager]'s clock).
     * @param nowMillis when the app returned to the foreground.
     */
    fun shouldLock(timeout: AutoLockTimeout, backgroundedAtMillis: Long, nowMillis: Long): Boolean {
        if (timeout == AutoLockTimeout.IMMEDIATE) return true
        return (nowMillis - backgroundedAtMillis) >= timeout.millis
    }
}
