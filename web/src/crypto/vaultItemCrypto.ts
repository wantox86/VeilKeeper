import { encrypt as aesGcmEncrypt, decrypt as aesGcmDecrypt } from './aesGcm'
import type { VaultItemPayload } from '../types/vault'

/**
 * Encrypts/decrypts a `VaultItemPayload` end-to-end with the VaultDataKey
 * (VDK), mirroring Android's `VaultItemCrypto.kt` (`object VaultItemCrypto`)
 * 1:1: serialize to UTF-8 JSON, then AES-256-GCM encrypt with a fresh random
 * nonce per call (`aesGcm.ts`'s `encrypt` already does this). The server
 * never sees `VaultItemPayload` -- only the base64 of this function's output
 * (`vaultApi.ts` handles the base64 <-> bytes conversion at the HTTP
 * boundary, same split Android has between `VaultItemCrypto` and its
 * repository layer).
 */
export async function encryptVaultItemPayload(
  vdk: Uint8Array,
  payload: VaultItemPayload,
): Promise<Uint8Array> {
  const plaintext = new TextEncoder().encode(JSON.stringify(payload))
  return aesGcmEncrypt(vdk, plaintext)
}

/**
 * Decrypts a blob produced by `encryptVaultItemPayload` (or by Android's
 * `VaultItemCrypto.encrypt` -- same wire format) and parses it back into a
 * `VaultItemPayload`. Throws on tamper/wrong-key (from `aesGcm.ts`) or
 * malformed JSON -- callers must not swallow that distinction.
 */
export async function decryptVaultItemPayload(vdk: Uint8Array, blob: Uint8Array): Promise<VaultItemPayload> {
  const plaintext = await aesGcmDecrypt(vdk, blob)
  return JSON.parse(new TextDecoder().decode(plaintext)) as VaultItemPayload
}
