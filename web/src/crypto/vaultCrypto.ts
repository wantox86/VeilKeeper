import { deriveMasterKey } from './argon2'
import { deriveKey as hkdfDeriveKey } from './hkdf'
import { encrypt as aesGcmEncrypt, decrypt as aesGcmDecrypt, KEY_LENGTH_BYTES } from './aesGcm'
import type { KdfParams } from './kdfParams'

/**
 * Orchestrates the full client-side key hierarchy from CLAUDE.md Resolved
 * Design Decision #1, mirroring Android's `VaultCrypto.kt` 1:1 (same info
 * strings, same salt length, same VDK length):
 *
 * ```
 * password + kdf_salt + kdf_params
 *   --Argon2id-->  MasterKey (never transmitted)
 *   --HKDF(info="veilkeeper:auth:v1")-->  AuthKey   (sent to server instead of password)
 *   --HKDF(info="veilkeeper:wrap:v1")-->  WrapKey   (never transmitted)
 *
 * VaultDataKey (VDK) = random 32 bytes, generated once at registration
 * wrapped_vdk = AES-256-GCM_encrypt(VDK, key=WrapKey)
 * ```
 *
 * Sprint 1 scope note: nothing calls this yet (no Login/Register UI until
 * Sprint 2) -- it exists now so the crypto foundation is fully testable and
 * so later sprints can wire it up without re-deriving the key hierarchy.
 */
const AUTH_KEY_INFO = new TextEncoder().encode('veilkeeper:auth:v1')
const WRAP_KEY_INFO = new TextEncoder().encode('veilkeeper:wrap:v1')
export const KDF_SALT_LENGTH_BYTES = 16

export async function deriveMasterKeyFromPassword(
  password: Uint8Array,
  kdfSalt: Uint8Array,
  params: KdfParams,
): Promise<Uint8Array> {
  return deriveMasterKey(password, kdfSalt, params)
}

export function deriveAuthKey(masterKey: Uint8Array): Promise<Uint8Array> {
  return hkdfDeriveKey(masterKey, AUTH_KEY_INFO)
}

export function deriveWrapKey(masterKey: Uint8Array): Promise<Uint8Array> {
  return hkdfDeriveKey(masterKey, WRAP_KEY_INFO)
}

/** Generates a fresh, random VaultDataKey (registration only -- never re-derived). */
export function generateVaultDataKey(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(KEY_LENGTH_BYTES))
}

export function wrapVaultDataKey(vdk: Uint8Array, wrapKey: Uint8Array): Promise<Uint8Array> {
  return aesGcmEncrypt(wrapKey, vdk)
}

export function unwrapVaultDataKey(wrappedVdk: Uint8Array, wrapKey: Uint8Array): Promise<Uint8Array> {
  return aesGcmDecrypt(wrapKey, wrappedVdk)
}

/** Generates a fresh random kdf_salt for a new registration. */
export function generateKdfSalt(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(KDF_SALT_LENGTH_BYTES))
}
