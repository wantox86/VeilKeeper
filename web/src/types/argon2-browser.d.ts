/**
 * Minimal hand-written type declarations for `argon2-browser` -- the package
 * ships no `.d.ts` and there is no `@types/argon2-browser`. Only the surface
 * actually used by src/crypto/argon2.ts is declared.
 */
declare module 'argon2-browser' {
  export enum ArgonType {
    Argon2d = 0,
    Argon2i = 1,
    Argon2id = 2,
  }

  export interface Argon2HashParams {
    pass: string | Uint8Array
    salt: string | Uint8Array
    time?: number
    mem?: number
    hashLen?: number
    parallelism?: number
    type?: ArgonType
    secret?: Uint8Array
    ad?: Uint8Array
  }

  export interface Argon2HashResult {
    hash: Uint8Array
    hashHex: string
    encoded: string
  }

  export function hash(params: Argon2HashParams): Promise<Argon2HashResult>
  export function unloadRuntime(): void

  const argon2: {
    ArgonType: typeof ArgonType
    hash: typeof hash
    unloadRuntime: typeof unloadRuntime
  }
  export default argon2
}
