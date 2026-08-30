package id.quezacolt.veilkeeper

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sprint 0 placeholder test, just to prove the test task runs in CI.
 * Real ViewModel/Repository/crypto tests land with the features they cover
 * (SPEC-BASE.md Section 46), starting Sprint 1.
 */
class SanityTest {
    @Test
    fun `app package id is stable`() {
        assertEquals("id.quezacolt.veilkeeper", BuildConfig.APPLICATION_ID)
    }
}
