import { describe, expect, it } from 'vitest'
import argon2 from 'argon2-browser'
import { deriveMasterKey } from '../argon2'
import { DEFAULT_KDF_PARAMS } from '../kdfParams'

describe('argon2id', () => {
  /**
   * Official RFC 9106 Section 5.3 Argon2id test vector (p=4, T=32,
   * m=32 KiB, t=3, v=0x13, fixed password/salt/secret/associated-data
   * patterns). Confirms `argon2-browser` is a correct, spec-compliant
   * Argon2id implementation here in the actual app build -- not just
   * "trust the spike," re-verified as part of Sprint 1 itself.
   */
  it('matches the official RFC 9106 Argon2id test vector', async () => {
    const pass = new Uint8Array(32).fill(1)
    const salt = new Uint8Array(16).fill(2)
    const secret = new Uint8Array(8).fill(3)
    const ad = new Uint8Array(12).fill(4)

    const result = await argon2.hash({
      pass,
      salt,
      secret,
      ad,
      time: 3,
      mem: 32,
      parallelism: 4,
      hashLen: 32,
      type: argon2.ArgonType.Argon2id,
    })

    expect(result.hashHex).toBe('0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659')
  })

  /**
   * CRITICAL cross-platform test: reproduces the exact scenario from the
   * `spike/kmp-web-crypto` feasibility research (`crypto-spike/README.md`,
   * `poc/verify-argon2-wasm.cjs`), using VeilKeeper's actual production
   * `KdfParams.DEFAULT` (64 MiB / t=3 / p=4 -- see
   * `android/.../crypto/KdfParams.kt`). The spike proved this exact input
   * produces a hash byte-identical to `argon2-cffi` (a native binding to
   * the same unmodified P-H-C reference C implementation that Android's
   * `argon2kt` also binds to natively) AND matches the official RFC 9106
   * vector above. Asserting the same expected hex here means Sprint 1's
   * actual `deriveMasterKey()` -- not just the spike's throwaway script --
   * reproduces that same byte-identical output, which is the strongest
   * verification available without a physical Android device in this
   * sandbox to run `Argon2idMasterKeyDeriverInstrumentedTest` side-by-side.
   */
  it('reproduces the spike-verified byte-identical hash for production KdfParams.DEFAULT', async () => {
    const password = new TextEncoder().encode('correct horse battery staple')
    const salt = new Uint8Array([0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15])

    expect(DEFAULT_KDF_PARAMS).toEqual({ memoryKiB: 64 * 1024, iterations: 3, parallelism: 4 })

    const masterKey = await deriveMasterKey(password, salt, DEFAULT_KDF_PARAMS)
    const hex = Array.from(masterKey)
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('')

    expect(hex).toBe('853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e')
  })

  it('is deterministic for the same password/salt/params', async () => {
    const password = new TextEncoder().encode('same password every time')
    const salt = new Uint8Array(16).fill(7)

    const a = await deriveMasterKey(password, salt, DEFAULT_KDF_PARAMS)
    const b = await deriveMasterKey(password, salt, DEFAULT_KDF_PARAMS)

    expect(a).toEqual(b)
  })

  it('produces 32-byte output', async () => {
    const password = new TextEncoder().encode('another password')
    const salt = new Uint8Array(16).fill(9)

    const masterKey = await deriveMasterKey(password, salt, DEFAULT_KDF_PARAMS)

    expect(masterKey.length).toBe(32)
  })
})
