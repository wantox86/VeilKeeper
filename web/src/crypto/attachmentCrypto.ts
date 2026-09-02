import { encrypt as aesGcmEncrypt, decrypt as aesGcmDecrypt } from './aesGcm'

/**
 * Encrypts/decrypts attachment bytes and filenames with the VaultDataKey
 * (VDK), mirroring Android's `AttachmentCrypto.kt`
 * (`android/app/src/main/java/id/quezacolt/veilkeeper/crypto/AttachmentCrypto.kt`)
 * 1:1: a thin wrapper over `aesGcm.ts`, same reason `vaultItemCrypto.ts`
 * exists as a wrapper rather than calling `aesGcm.ts` directly from
 * `stores/vault.ts` -- a single, well-documented, independently-tested place
 * that owns "how attachment bytes get encrypted".
 *
 * The filename and the file content are encrypted as two independent
 * AES-256-GCM operations, each with its own fresh nonce (`aesGcm.ts`
 * generates one per call) -- no cryptographic requirement that they share
 * one, and keeping them independent means either could be re-encrypted
 * without touching the other.
 */
export async function encryptFile(vdk: Uint8Array, plaintext: Uint8Array): Promise<Uint8Array> {
  return aesGcmEncrypt(vdk, plaintext)
}

/** Decrypts a blob produced by {@link encryptFile}. Throws on tamper/wrong-key (see `aesGcm.ts`). */
export async function decryptFile(vdk: Uint8Array, blob: Uint8Array): Promise<Uint8Array> {
  return aesGcmDecrypt(vdk, blob)
}

/** Encrypts a filename (UTF-8) with `vdk` -- filenames are metadata that can leak information, so never sent in plaintext. */
export async function encryptFilename(vdk: Uint8Array, filename: string): Promise<Uint8Array> {
  return aesGcmEncrypt(vdk, new TextEncoder().encode(filename))
}

/** Decrypts a blob produced by {@link encryptFilename} back into the original filename string. */
export async function decryptFilename(vdk: Uint8Array, blob: Uint8Array): Promise<string> {
  return new TextDecoder().decode(await aesGcmDecrypt(vdk, blob))
}
