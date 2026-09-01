import { describe, expect, it } from 'vitest'
import { bytesToBase64, base64ToBytes } from '../base64'

describe('base64', () => {
  it('round-trips arbitrary bytes', () => {
    const original = crypto.getRandomValues(new Uint8Array(32))
    const encoded = bytesToBase64(original)
    const decoded = base64ToBytes(encoded)
    expect(decoded).toEqual(original)
  })

  it('round-trips an empty array', () => {
    const original = new Uint8Array(0)
    expect(base64ToBytes(bytesToBase64(original))).toEqual(original)
  })

  it('produces standard base64 (no URL-safe substitutions)', () => {
    // Bytes chosen so the base64 alphabet's '+' / '/' characters actually
    // appear, distinguishing standard from URL-safe encoding.
    const bytes = new Uint8Array([0xfb, 0xff, 0xbf])
    const encoded = bytesToBase64(bytes)
    expect(encoded).toBe('+/+/')
  })
})
