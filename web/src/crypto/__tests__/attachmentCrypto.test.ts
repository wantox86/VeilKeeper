import { describe, expect, it } from 'vitest'
import { encryptFile, decryptFile, encryptFilename, decryptFilename } from '../attachmentCrypto'
import { bytesToBase64, base64ToBytes } from '../base64'

/**
 * Mirrors Android's `AttachmentCryptoTest.kt` scope: round-trip for file
 * bytes and filename (each an independent AES-256-GCM operation, per
 * `attachmentCrypto.ts`'s own doc comment), unique nonces, tamper/wrong-key
 * detection -- SPEC-BASE.md Section 47. Also proves the exact
 * encrypt -> base64 (wire) -> decrypt round trip `stores/vault.ts`'s
 * `uploadAttachment`/`downloadAttachment` actions perform against the real
 * base64 JSON attachment endpoints (`vaultApi.ts`).
 */
describe('attachmentCrypto', () => {
  const vdk = crypto.getRandomValues(new Uint8Array(32))

  it('round-trips file bytes through encrypt -> base64 (wire) -> decrypt', async () => {
    const plaintext = crypto.getRandomValues(new Uint8Array(4096)) // stand-in for a small image

    const encrypted = await encryptFile(vdk, plaintext)
    const wire = bytesToBase64(encrypted)
    const decrypted = await decryptFile(vdk, base64ToBytes(wire))

    expect(decrypted).toEqual(plaintext)
  })

  it('round-trips a filename through encrypt -> base64 (wire) -> decrypt', async () => {
    const filename = 'vpn-screenshot.png'

    const encrypted = await encryptFilename(vdk, filename)
    const wire = bytesToBase64(encrypted)
    const decrypted = await decryptFilename(vdk, base64ToBytes(wire))

    expect(decrypted).toBe(filename)
  })

  it('produces a different ciphertext (unique nonce) for the same file bytes encrypted twice', async () => {
    const plaintext = new Uint8Array([1, 2, 3, 4, 5])

    const first = await encryptFile(vdk, plaintext)
    const second = await encryptFile(vdk, plaintext)

    expect(bytesToBase64(first)).not.toBe(bytesToBase64(second))
    expect(await decryptFile(vdk, first)).toEqual(plaintext)
    expect(await decryptFile(vdk, second)).toEqual(plaintext)
  })

  it('produces a different ciphertext (unique nonce) for the same filename encrypted twice', async () => {
    const filename = 'same-name.jpg'

    const first = await encryptFilename(vdk, filename)
    const second = await encryptFilename(vdk, filename)

    expect(bytesToBase64(first)).not.toBe(bytesToBase64(second))
  })

  it('fails to decrypt file bytes with the wrong VDK', async () => {
    const plaintext = new Uint8Array([9, 9, 9])
    const encrypted = await encryptFile(vdk, plaintext)

    const wrongVdk = crypto.getRandomValues(new Uint8Array(32))
    await expect(decryptFile(wrongVdk, encrypted)).rejects.toThrow()
  })

  it('fails to decrypt a tampered file blob', async () => {
    const plaintext = new Uint8Array([9, 9, 9, 9])
    const encrypted = await encryptFile(vdk, plaintext)

    const tampered = new Uint8Array(encrypted)
    tampered[tampered.length - 1] ^= 0xff

    await expect(decryptFile(vdk, tampered)).rejects.toThrow()
  })

  it('fails to decrypt a filename with the wrong VDK', async () => {
    const encrypted = await encryptFilename(vdk, 'secret-plan.pdf')
    const wrongVdk = crypto.getRandomValues(new Uint8Array(32))
    await expect(decryptFilename(wrongVdk, encrypted)).rejects.toThrow()
  })
})
