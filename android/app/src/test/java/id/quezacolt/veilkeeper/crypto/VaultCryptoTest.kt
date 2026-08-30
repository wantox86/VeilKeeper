package id.quezacolt.veilkeeper.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Exercises the full CLAUDE.md Resolved Design Decision #1 key hierarchy
 * end-to-end (using [FakeMasterKeyDeriver] in place of real Argon2id -- see
 * its doc comment):
 *
 * password -> MasterKey -> {AuthKey, WrapKey}; VDK generated + wrapped with
 * WrapKey; unwrapped VDK must match the original.
 */
class VaultCryptoTest {

    private val vaultCrypto = VaultCrypto(FakeMasterKeyDeriver())

    @Test
    fun `full registration-then-login key hierarchy round trips`() {
        val password = "correct horse battery staple".toByteArray()
        val salt = vaultCrypto.generateKdfSalt()
        val params = KdfParams.DEFAULT

        // "Registration"
        val masterKeyAtRegister = vaultCrypto.deriveMasterKey(password, salt, params)
        val wrapKeyAtRegister = vaultCrypto.deriveWrapKey(masterKeyAtRegister)
        val vdk = vaultCrypto.generateVaultDataKey()
        val wrappedVdk = vaultCrypto.wrapVaultDataKey(vdk, wrapKeyAtRegister)

        // "Login" on the same or a different device: same password + same
        // salt/params (as would be returned by /auth/prelogin) must
        // reconstruct the same WrapKey and successfully unwrap the VDK --
        // this is the "any device just works" property from CLAUDE.md.
        val masterKeyAtLogin = vaultCrypto.deriveMasterKey(password, salt, params)
        val wrapKeyAtLogin = vaultCrypto.deriveWrapKey(masterKeyAtLogin)
        val unwrappedVdk = vaultCrypto.unwrapVaultDataKey(wrappedVdk, wrapKeyAtLogin)

        assertArrayEquals(vdk, unwrappedVdk)
    }

    @Test
    fun `AuthKey and WrapKey are domain-separated`() {
        val masterKey = vaultCrypto.deriveMasterKey("pw".toByteArray(), vaultCrypto.generateKdfSalt(), KdfParams.DEFAULT)

        val authKey = vaultCrypto.deriveAuthKey(masterKey)
        val wrapKey = vaultCrypto.deriveWrapKey(masterKey)

        assertFalse(authKey.contentEquals(wrapKey))
    }

    @Test
    fun `different passwords yield different MasterKeys for the same salt`() {
        val salt = vaultCrypto.generateKdfSalt()

        val keyA = vaultCrypto.deriveMasterKey("password-one".toByteArray(), salt, KdfParams.DEFAULT)
        val keyB = vaultCrypto.deriveMasterKey("password-two".toByteArray(), salt, KdfParams.DEFAULT)

        assertFalse(keyA.contentEquals(keyB))
    }

    @Test
    fun `wrong WrapKey fails to unwrap (simulates a wrong password at login)`() {
        val salt = vaultCrypto.generateKdfSalt()
        val correctMasterKey = vaultCrypto.deriveMasterKey("right-password".toByteArray(), salt, KdfParams.DEFAULT)
        val wrongMasterKey = vaultCrypto.deriveMasterKey("wrong-password".toByteArray(), salt, KdfParams.DEFAULT)

        val vdk = vaultCrypto.generateVaultDataKey()
        val wrapped = vaultCrypto.wrapVaultDataKey(vdk, vaultCrypto.deriveWrapKey(correctMasterKey))

        org.junit.Assert.assertThrows(java.security.GeneralSecurityException::class.java) {
            vaultCrypto.unwrapVaultDataKey(wrapped, vaultCrypto.deriveWrapKey(wrongMasterKey))
        }
    }

    @Test
    fun `generated kdf salts and VDKs are unique`() {
        val salts = (1..20).map { vaultCrypto.generateKdfSalt().toList() }.toSet()
        val vdks = (1..20).map { vaultCrypto.generateVaultDataKey().toList() }.toSet()

        assertEquals(20, salts.size)
        assertEquals(20, vdks.size)
    }

    @Test
    fun `kdf salt has the documented length`() {
        assertEquals(VaultCrypto.KDF_SALT_LENGTH_BYTES, vaultCrypto.generateKdfSalt().size)
    }

    @Test
    fun `wipe zeroes out sensitive byte arrays`() {
        val secret = "sensitive-data".toByteArray()
        VaultCrypto.wipe(secret)
        assertArrayEquals(ByteArray(secret.size), secret)
    }
}
