package id.quezacolt.veilkeeper.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the REAL Argon2id implementation (Argon2Kt's native binding),
 * as opposed to [FakeMasterKeyDeriver] which every host-JVM unit test in
 * this module uses instead.
 *
 * This must run as an instrumented test (on a device/emulator), NOT a local
 * unit test: Argon2Kt bundles native `.so` libraries built for Android
 * target ABIs, which a plain host JVM (what `testDebugUnitTest` runs on)
 * cannot load -- see [Argon2idMasterKeyDeriver]'s doc comment for the full
 * explanation.
 *
 * KNOWN GAP (disclosed, not silently skipped): this repo's CI
 * (.github/workflows/android.yml) only runs `testDebugUnitTest`, not
 * instrumented tests (no emulator step is configured), and no
 * device/emulator was available in the Sprint 1 implementation environment
 * either. This test is included for correctness/documentation and should be
 * run manually (`./gradlew connectedAndroidTest`) against a real device or
 * emulator before this crypto path ships to end users, but that verification
 * could not be performed as part of this sprint's automated checks.
 */
@RunWith(AndroidJUnit4::class)
class Argon2idMasterKeyDeriverInstrumentedTest {

    private val deriver = Argon2idMasterKeyDeriver()

    @Test
    fun deriveMasterKey_isDeterministicForSameInputs() {
        val salt = ByteArray(16) { it.toByte() }
        val params = KdfParams.DEFAULT

        val a = deriver.deriveMasterKey("correct horse battery staple".toByteArray(), salt, params)
        val b = deriver.deriveMasterKey("correct horse battery staple".toByteArray(), salt, params)

        assertArrayEquals(a, b)
        assertEquals(32, a.size)
    }

    @Test
    fun deriveMasterKey_differsForDifferentPasswords() {
        val salt = ByteArray(16) { it.toByte() }
        val params = KdfParams.DEFAULT

        val a = deriver.deriveMasterKey("password-one".toByteArray(), salt, params)
        val b = deriver.deriveMasterKey("password-two".toByteArray(), salt, params)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun deriveMasterKey_differsForDifferentSalts() {
        val params = KdfParams.DEFAULT

        val a = deriver.deriveMasterKey("same-password".toByteArray(), ByteArray(16) { 1 }, params)
        val b = deriver.deriveMasterKey("same-password".toByteArray(), ByteArray(16) { 2 }, params)

        assertFalse(a.contentEquals(b))
    }

    @Test
    fun fullVaultCryptoPipeline_withRealArgon2id_roundTrips() {
        val vaultCrypto = VaultCrypto(deriver)
        val salt = vaultCrypto.generateKdfSalt()
        val password = "a-real-user-password".toByteArray()

        val masterKey = vaultCrypto.deriveMasterKey(password, salt, KdfParams.DEFAULT)
        val wrapKey = vaultCrypto.deriveWrapKey(masterKey)
        val vdk = vaultCrypto.generateVaultDataKey()
        val wrapped = vaultCrypto.wrapVaultDataKey(vdk, wrapKey)

        // Simulate a fresh login on another device: re-derive from scratch.
        val masterKey2 = vaultCrypto.deriveMasterKey(password, salt, KdfParams.DEFAULT)
        val wrapKey2 = vaultCrypto.deriveWrapKey(masterKey2)
        val unwrapped = vaultCrypto.unwrapVaultDataKey(wrapped, wrapKey2)

        assertArrayEquals(vdk, unwrapped)
    }
}
