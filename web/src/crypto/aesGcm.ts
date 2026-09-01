/**
 * AES-256-GCM authenticated encryption, used to wrap/unwrap the
 * VaultDataKey (CLAUDE.md Resolved Design Decision #1) and vault item
 * payloads. Backed entirely by the native Web Crypto API
 * (`crypto.subtle`), mirroring Android's `AesGcm.kt` (`javax.crypto.Cipher`)
 * -- same wire format, same nonce/tag sizes, no extra dependency needed.
 *
 * Wire format: `nonce (12 bytes) || ciphertext (includes the 16-byte GCM
 * tag)` -- Web Crypto's `encrypt()` already appends the tag to the
 * ciphertext output (same as `javax.crypto.Cipher.doFinal` on Android), so
 * no extra concatenation logic is needed there.
 */
export const KEY_LENGTH_BYTES = 32 // AES-256
export const NONCE_LENGTH_BYTES = 12 // NIST SP 800-38D recommended GCM nonce size
const TAG_LENGTH_BITS = 128

/**
 * Encrypts `plaintext` with `key` (must be `KEY_LENGTH_BYTES` bytes),
 * returning `nonce || ciphertext+tag`. A fresh random nonce is generated
 * via `crypto.getRandomValues` on every call.
 */
export async function encrypt(
  key: Uint8Array,
  plaintext: Uint8Array,
  associatedData?: Uint8Array,
): Promise<Uint8Array> {
  requireKeyLength(key)

  const nonce = crypto.getRandomValues(new Uint8Array(NONCE_LENGTH_BYTES))
  const cryptoKey = await crypto.subtle.importKey('raw', toArrayBuffer(key), 'AES-GCM', false, ['encrypt'])

  const ciphertext = await crypto.subtle.encrypt(
    {
      name: 'AES-GCM',
      iv: toArrayBuffer(nonce),
      tagLength: TAG_LENGTH_BITS,
      // Only include additionalData when actually provided: Node's
      // crypto.subtle (used under Vitest) silently tolerates
      // `additionalData: undefined`, but real browser WebCrypto
      // implementations (verified against Chromium) reject it with
      // "additionalData: Not a BufferSource" if the key is present at all,
      // even with an undefined value -- so the key itself must be omitted,
      // not just its value left undefined.
      ...(associatedData ? { additionalData: toArrayBuffer(associatedData) } : {}),
    },
    cryptoKey,
    toArrayBuffer(plaintext),
  )

  const output = new Uint8Array(nonce.length + ciphertext.byteLength)
  output.set(nonce, 0)
  output.set(new Uint8Array(ciphertext), nonce.length)
  return output
}

/**
 * Decrypts a blob produced by `encrypt`. Throws (a `DOMException` /
 * `OperationError`) if the ciphertext was tampered with or the wrong key is
 * used -- callers must not swallow that distinction, same contract as
 * Android's `AesGcm.decrypt`.
 */
export async function decrypt(
  key: Uint8Array,
  nonceAndCiphertext: Uint8Array,
  associatedData?: Uint8Array,
): Promise<Uint8Array> {
  requireKeyLength(key)
  if (nonceAndCiphertext.length <= NONCE_LENGTH_BYTES) {
    throw new Error('AesGcm: input too short to contain a nonce')
  }

  const nonce = nonceAndCiphertext.slice(0, NONCE_LENGTH_BYTES)
  const ciphertext = nonceAndCiphertext.slice(NONCE_LENGTH_BYTES)

  const cryptoKey = await crypto.subtle.importKey('raw', toArrayBuffer(key), 'AES-GCM', false, ['decrypt'])

  const plaintext = await crypto.subtle.decrypt(
    {
      name: 'AES-GCM',
      iv: toArrayBuffer(nonce),
      tagLength: TAG_LENGTH_BITS,
      // See the matching comment in encrypt() above -- must omit the key
      // entirely when there's no AAD, not just leave its value undefined.
      ...(associatedData ? { additionalData: toArrayBuffer(associatedData) } : {}),
    },
    cryptoKey,
    toArrayBuffer(ciphertext),
  )

  return new Uint8Array(plaintext)
}

function requireKeyLength(key: Uint8Array): void {
  if (key.length !== KEY_LENGTH_BYTES) {
    throw new Error(`AesGcm: key must be ${KEY_LENGTH_BYTES} bytes, got ${key.length}`)
  }
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.slice().buffer
}
