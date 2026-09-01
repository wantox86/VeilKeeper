/**
 * `argon2-browser`'s compiled Emscripten glue (`dist/argon2.js`) decides how
 * to load its `.wasm` binary purely by checking `typeof fetch === 'function'`
 * in one code path (`instantiateAsync`), NOT by checking whether it's
 * actually running in a browser. Node 18+ ships a global `fetch`, so under
 * Vitest's `node` test environment the library tries `fetch(absoluteFsPath)`
 * and Node's `fetch` (undici) throws `TypeError: Failed to parse URL` on a
 * plain filesystem path (not a `file://` URL).
 *
 * The `crypto-spike/poc/verify-argon2-wasm.cjs` script (see
 * `spike/kmp-web-crypto` branch) hit this exact issue and worked around it
 * the same way: deleting `global.fetch` so the library falls back to its
 * Node-native path (`fs.readFileSync` via `ENVIRONMENT_IS_NODE`). This has
 * zero effect on the real browser build -- `src/crypto/argon2.ts` never
 * touches `fetch` directly, and this file only runs under Vitest.
 */
if (typeof process !== 'undefined' && process.versions?.node) {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  delete (globalThis as any).fetch
}
