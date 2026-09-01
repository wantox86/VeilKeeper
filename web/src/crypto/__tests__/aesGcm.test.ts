import { describe, expect, it } from 'vitest'
import { encrypt, decrypt, KEY_LENGTH_BYTES, NONCE_LENGTH_BYTES } from '../aesGcm'

function randomKey(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(KEY_LENGTH_BYTES))
}

describe('aesGcm', () => {
  it('round-trips plaintext', async () => {
    const key = randomKey()
    const plaintext = new TextEncoder().encode('the quick brown fox jumps over the lazy dog')

    const ciphertext = await encrypt(key, plaintext)
    const decrypted = await decrypt(key, ciphertext)

    expect(decrypted).toEqual(plaintext)
  })

  it('round-trips with associated data', async () => {
    const key = randomKey()
    const plaintext = new TextEncoder().encode('secret content')
    const aad = new TextEncoder().encode('item-id-123')

    const ciphertext = await encrypt(key, plaintext, aad)
    const decrypted = await decrypt(key, ciphertext, aad)

    expect(decrypted).toEqual(plaintext)
  })

  it('produces a unique nonce per call (output differs each time)', async () => {
    const key = randomKey()
    const plaintext = new TextEncoder().encode('same plaintext every time')

    const a = await encrypt(key, plaintext)
    const b = await encrypt(key, plaintext)

    expect(a).not.toEqual(b)
    expect(a.slice(0, NONCE_LENGTH_BYTES)).not.toEqual(b.slice(0, NONCE_LENGTH_BYTES))
  })

  it('wire format is nonce (12 bytes) || ciphertext+tag', async () => {
    const key = randomKey()
    const plaintext = new TextEncoder().encode('x')

    const ciphertext = await encrypt(key, plaintext)

    // 12-byte nonce + 1-byte plaintext + 16-byte GCM tag
    expect(ciphertext.length).toBe(NONCE_LENGTH_BYTES + 1 + 16)
  })

  it('rejects tampered ciphertext', async () => {
    const key = randomKey()
    const plaintext = new TextEncoder().encode('do not tamper with me')

    const ciphertext = await encrypt(key, plaintext)
    const tampered = ciphertext.slice()
    tampered[tampered.length - 1] ^= 0xff

    await expect(decrypt(key, tampered)).rejects.toThrow()
  })

  it('rejects the wrong key', async () => {
    const key = randomKey()
    const wrongKey = randomKey()
    const plaintext = new TextEncoder().encode('only the right key can open this')

    const ciphertext = await encrypt(key, plaintext)

    await expect(decrypt(wrongKey, ciphertext)).rejects.toThrow()
  })

  it('rejects keys of the wrong length', async () => {
    const badKey = new Uint8Array(16) // AES-128 length, not AES-256
    const plaintext = new TextEncoder().encode('x')

    await expect(encrypt(badKey, plaintext)).rejects.toThrow()
  })
})
