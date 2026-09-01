import { describe, expect, it } from 'vitest'
import { encryptVaultItemPayload, decryptVaultItemPayload } from '../vaultItemCrypto'
import { bytesToBase64, base64ToBytes } from '../base64'
import type { VaultItemPayload } from '../../types/vault'

/**
 * Full crypto-integration test at the vault-item level -- not just the raw
 * AES-GCM primitive (already covered by `aesGcm.test.ts`), but the actual
 * shape CLAUDE.md's Web Sprint 3 task requires be proven:
 * encrypt -> (simulate the wire: base64 encode/decode, exactly what
 * `vaultApi.ts` does around a real HTTP call) -> decrypt, with the resulting
 * payload byte-identical to the original.
 */
describe('vaultItemCrypto', () => {
  const vdk = crypto.getRandomValues(new Uint8Array(32))

  it('round-trips a payload through encrypt -> base64 (wire) -> decrypt', async () => {
    const payload: VaultItemPayload = {
      title: 'My bank account',
      content: [
        { type: 'text', label: 'Bank', value: 'Contoso Bank' },
        { type: 'secret', label: 'PIN', value: '1234' },
        { type: 'note', label: null, value: 'Call before 5pm' },
      ],
    }

    const encrypted = await encryptVaultItemPayload(vdk, payload)
    // Simulate exactly what crosses the wire: base64 out, base64 back in --
    // the server never sees anything but this opaque string.
    const wire = bytesToBase64(encrypted)
    const decrypted = await decryptVaultItemPayload(vdk, base64ToBytes(wire))

    expect(decrypted).toEqual(payload)
  })

  it('produces a different ciphertext (unique nonce) for the same payload encrypted twice', async () => {
    const payload: VaultItemPayload = {
      title: 'Same title',
      content: [{ type: 'text', label: null, value: 'x' }],
    }

    const first = await encryptVaultItemPayload(vdk, payload)
    const second = await encryptVaultItemPayload(vdk, payload)

    expect(bytesToBase64(first)).not.toBe(bytesToBase64(second))
    // Both must still decrypt back to the identical payload.
    expect(await decryptVaultItemPayload(vdk, first)).toEqual(payload)
    expect(await decryptVaultItemPayload(vdk, second)).toEqual(payload)
  })

  it('fails to decrypt with the wrong VDK', async () => {
    const payload: VaultItemPayload = { title: 't', content: [{ type: 'note', label: null, value: 'v' }] }
    const encrypted = await encryptVaultItemPayload(vdk, payload)

    const wrongVdk = crypto.getRandomValues(new Uint8Array(32))
    await expect(decryptVaultItemPayload(wrongVdk, encrypted)).rejects.toThrow()
  })

  it('fails to decrypt a tampered ciphertext', async () => {
    const payload: VaultItemPayload = { title: 't', content: [{ type: 'note', label: null, value: 'v' }] }
    const encrypted = await encryptVaultItemPayload(vdk, payload)

    const tampered = new Uint8Array(encrypted)
    tampered[tampered.length - 1] ^= 0xff

    await expect(decryptVaultItemPayload(vdk, tampered)).rejects.toThrow()
  })

  it('round-trips an item with zero content blocks and an empty title edge case', async () => {
    const payload: VaultItemPayload = { title: 'Empty note holder', content: [] }
    const encrypted = await encryptVaultItemPayload(vdk, payload)
    const decrypted = await decryptVaultItemPayload(vdk, encrypted)
    expect(decrypted).toEqual(payload)
  })
})
