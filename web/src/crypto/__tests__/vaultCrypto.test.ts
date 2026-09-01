import { describe, expect, it } from 'vitest'
import {
  deriveMasterKeyFromPassword,
  deriveAuthKey,
  deriveWrapKey,
  generateVaultDataKey,
  wrapVaultDataKey,
  unwrapVaultDataKey,
  generateKdfSalt,
  KDF_SALT_LENGTH_BYTES,
} from '../vaultCrypto'
import { DEFAULT_KDF_PARAMS } from '../kdfParams'

describe('vaultCrypto', () => {
  it('runs the full registration-time key hierarchy and unwraps the VDK on a simulated login', async () => {
    const password = new TextEncoder().encode('correct horse battery staple')
    const kdfSalt = generateKdfSalt()
    expect(kdfSalt.length).toBe(KDF_SALT_LENGTH_BYTES)

    // Registration
    const masterKey = await deriveMasterKeyFromPassword(password, kdfSalt, DEFAULT_KDF_PARAMS)
    const wrapKey = await deriveWrapKey(masterKey)
    const vdk = generateVaultDataKey()
    const wrappedVdk = await wrapVaultDataKey(vdk, wrapKey)

    // "Login" on another simulated session: re-derive from the same password/salt/params
    const masterKey2 = await deriveMasterKeyFromPassword(password, kdfSalt, DEFAULT_KDF_PARAMS)
    const wrapKey2 = await deriveWrapKey(masterKey2)
    const unwrappedVdk = await unwrapVaultDataKey(wrappedVdk, wrapKey2)

    expect(unwrappedVdk).toEqual(vdk)
  })

  it('derives different AuthKey and WrapKey from the same MasterKey (domain separation)', async () => {
    const masterKey = crypto.getRandomValues(new Uint8Array(32))

    const authKey = await deriveAuthKey(masterKey)
    const wrapKey = await deriveWrapKey(masterKey)

    expect(authKey).not.toEqual(wrapKey)
    expect(authKey.length).toBe(32)
    expect(wrapKey.length).toBe(32)
  })

  it('fails to unwrap the VDK with a WrapKey derived from a different password', async () => {
    const kdfSalt = generateKdfSalt()
    const correctPassword = new TextEncoder().encode('correct password')
    const wrongPassword = new TextEncoder().encode('wrong password')

    const masterKey = await deriveMasterKeyFromPassword(correctPassword, kdfSalt, DEFAULT_KDF_PARAMS)
    const wrapKey = await deriveWrapKey(masterKey)
    const vdk = generateVaultDataKey()
    const wrappedVdk = await wrapVaultDataKey(vdk, wrapKey)

    const wrongMasterKey = await deriveMasterKeyFromPassword(wrongPassword, kdfSalt, DEFAULT_KDF_PARAMS)
    const wrongWrapKey = await deriveWrapKey(wrongMasterKey)

    await expect(unwrapVaultDataKey(wrappedVdk, wrongWrapKey)).rejects.toThrow()
  })
})
