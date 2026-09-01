import argon2 from 'argon2-browser'
import type { KdfParams } from './kdfParams'

/**
 * Argon2id key derivation for the Web client, via `argon2-browser` (a WASM
 * build of the unmodified P-H-C reference C implementation -- the same
 * lineage Android's `argon2kt` binds to natively). This is the one crypto
 * primitive Web Crypto API doesn't provide (HKDF/AES-GCM below use
 * `crypto.subtle` directly) -- see `spike/kmp-web-crypto` branch,
 * `crypto-spike/README.md`, for the feasibility research and byte-identical
 * proof this choice is based on. Do not swap this for a different
 * implementation without re-verifying against the same test vectors (see
 * `__tests__/argon2.test.ts`).
 *
 * Output length is fixed at 32 bytes to match `AesGcm.KEY_LENGTH_BYTES` /
 * Android's `Argon2idMasterKeyDeriver` (MasterKey feeds HKDF and is
 * conceptually an AES-256 key's worth of entropy).
 */
const MASTER_KEY_LENGTH_BYTES = 32

/**
 * In a browser, `argon2-browser` fetches its `.wasm` binary from
 * `window.argon2WasmPath` (default: a `node_modules/...` path that doesn't
 * exist in a built app). `public/argon2.wasm` is served at the app's root,
 * so point it there. This is a no-op under Node (Vitest's default `node`
 * test environment): `argon2-browser` detects `process.versions.node` and
 * loads the wasm binary straight off disk instead, ignoring this path
 * entirely -- see its `lib/argon2.js` `loadModule()`.
 */
function configureWasmPathForBrowser(): void {
  if (typeof window !== 'undefined') {
    ;(window as unknown as { argon2WasmPath?: string }).argon2WasmPath = new URL(
      'argon2.wasm',
      window.location.origin,
    ).toString()
  }
}

/**
 * Derives MasterKey = Argon2id(password, kdfSalt, params), 32 bytes.
 * Mirrors `VaultCrypto.deriveMasterKey` / `Argon2idMasterKeyDeriver` on
 * Android exactly (same algorithm, same param semantics, same output
 * length, same Argon2 version 0x13 -- `argon2-browser` hardcodes 0x13
 * internally, matching `Argon2Version.V13` on Android).
 */
export async function deriveMasterKey(
  password: Uint8Array,
  kdfSalt: Uint8Array,
  params: KdfParams,
): Promise<Uint8Array> {
  configureWasmPathForBrowser()

  const result = await argon2.hash({
    pass: password,
    salt: kdfSalt,
    time: params.iterations,
    mem: params.memoryKiB,
    parallelism: params.parallelism,
    hashLen: MASTER_KEY_LENGTH_BYTES,
    type: argon2.ArgonType.Argon2id,
  })

  return result.hash
}
