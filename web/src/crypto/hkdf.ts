/**
 * HKDF-SHA256 (RFC 5869), used to derive the domain-separated AuthKey and
 * WrapKey subkeys from MasterKey (CLAUDE.md Resolved Design Decision #1).
 *
 * Implemented via the native Web Crypto API (`crypto.subtle`, algorithm
 * `"HKDF"`) -- browsers support HKDF as a first-class `deriveBits`
 * algorithm, so unlike Android (which hand-rolls it over `javax.crypto.Mac`
 * because the JDK/Android stdlib has no ready-made HKDF), Web needs no
 * hand-rolled implementation here.
 *
 * IMPORTANT for cross-platform parity: RFC 5869 Section 2.2 says that when
 * no salt is provided, HKDF-Extract uses a salt of `HashLen` (32 for
 * SHA-256) zero bytes. The Web Crypto HKDF algorithm does NOT apply that
 * default itself -- an omitted/empty `salt` there is used as a genuine
 * zero-length HMAC key, which is *not* the same as a 32-byte zero-filled
 * key. Android's `Hkdf.kt` always passes an explicit 32-byte zero salt, so
 * this implementation does too, to guarantee byte-identical output for the
 * same (ikm, info, outputLength).
 */
const HASH_LENGTH_BYTES = 32

export async function deriveKey(
  ikm: Uint8Array,
  info: Uint8Array,
  outputLength: number = HASH_LENGTH_BYTES,
): Promise<Uint8Array> {
  const salt = new Uint8Array(HASH_LENGTH_BYTES) // zero-filled, see doc comment above

  const key = await crypto.subtle.importKey('raw', toArrayBuffer(ikm), 'HKDF', false, ['deriveBits'])

  const bits = await crypto.subtle.deriveBits(
    {
      name: 'HKDF',
      hash: 'SHA-256',
      salt: toArrayBuffer(salt),
      info: toArrayBuffer(info),
    },
    key,
    outputLength * 8,
  )

  return new Uint8Array(bits)
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  // Slice to guarantee a plain ArrayBuffer backing (not SharedArrayBuffer,
  // and not a view into a larger buffer), which is what crypto.subtle
  // expects/accepts across all supported browsers.
  return bytes.slice().buffer
}
