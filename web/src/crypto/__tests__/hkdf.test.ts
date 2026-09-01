import { describe, expect, it } from 'vitest'
import { deriveKey } from '../hkdf'

function hexToBytes(hex: string): Uint8Array {
  const out = new Uint8Array(hex.length / 2)
  for (let i = 0; i < out.length; i++) {
    out[i] = parseInt(hex.substring(i * 2, i * 2 + 2), 16)
  }
  return out
}

describe('hkdf', () => {
  /**
   * RFC 5869 Appendix A.3 ("Test Case 3" for HKDF-SHA256): zero-length salt
   * and zero-length info. Per RFC 5869 Section 2.2, when no salt is
   * provided it defaults to a string of HashLen zero bytes -- exactly what
   * `deriveKey` always does internally -- so this is a direct, exact-match
   * verification against the published test vector.
   *
   * This is the SAME test vector used by Android's `HkdfTest.kt` -- the
   * point of this test is to prove Web's HKDF and Android's hand-rolled
   * HKDF implement the same algorithm and would derive byte-identical
   * AuthKey/WrapKey from the same MasterKey.
   */
  it('matches RFC 5869 SHA-256 test case 3 (zero-length salt and info)', async () => {
    const ikm = new Uint8Array(22).fill(0x0b)
    const info = new Uint8Array(0)

    const okm = await deriveKey(ikm, info, 42)

    const expected = hexToBytes(
      '8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8',
    )
    expect(okm).toEqual(expected)
  })

  it('is deterministic for the same inputs', async () => {
    const ikm = new TextEncoder().encode('some-master-key-material-32bytes')
    const info = new TextEncoder().encode('veilkeeper:auth:v1')

    const a = await deriveKey(ikm, info, 32)
    const b = await deriveKey(ikm, info, 32)

    expect(a).toEqual(b)
  })

  it('domain separation produces different keys for different info', async () => {
    const ikm = new TextEncoder().encode('some-master-key-material-32bytes')

    const authKey = await deriveKey(ikm, new TextEncoder().encode('veilkeeper:auth:v1'), 32)
    const wrapKey = await deriveKey(ikm, new TextEncoder().encode('veilkeeper:wrap:v1'), 32)

    expect(authKey).not.toEqual(wrapKey)
  })

  it('supports output longer than one hash block', async () => {
    const ikm = new TextEncoder().encode('ikm')
    const info = new TextEncoder().encode('info')

    const okm = await deriveKey(ikm, info, 100)

    expect(okm.length).toBe(100)
  })
})
