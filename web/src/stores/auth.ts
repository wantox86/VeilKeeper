import { defineStore } from 'pinia'
import * as authApi from '../services/authApi'
import { getOrCreateDeviceId, WEB_DEVICE_NAME } from '../services/device'
import {
  deriveMasterKeyFromPassword,
  deriveAuthKey,
  deriveWrapKey,
  generateVaultDataKey,
  wrapVaultDataKey,
  unwrapVaultDataKey,
  generateKdfSalt,
} from '../crypto/vaultCrypto'
import { DEFAULT_KDF_PARAMS, CURRENT_KDF_VERSION } from '../crypto/kdfParams'
import { bytesToBase64, base64ToBytes } from '../crypto/base64'
import { toWireKdfParams, fromWireKdfParams } from '../types/auth'

/**
 * Auth state for the Web client, following CLAUDE.md Resolved Design
 * Decision #1 exactly (same key hierarchy/order as Android):
 *
 *   password --Argon2id--> MasterKey --HKDF--> AuthKey (sent) / WrapKey (kept)
 *   VDK = random 32 bytes (register only) / unwrap(wrapped_vdk, WrapKey) (login)
 *
 * **Deliberate Web Sprint 2 simplification, disclosed (mirrors Android
 * Sprint 1's own equivalent simplification)**: sessionToken and vdk are held
 * in Pinia state ONLY -- never written to localStorage/sessionStorage. A
 * page refresh therefore logs the user out (protected routes redirect back
 * to /login). Android's Sprint 1 `AuthSessionHolder` made the exact same
 * choice for the exact same reason (no Keystore-equivalent secure at-rest
 * cache exists yet -- that's Android Sprint 3 scope, and Web doesn't have a
 * biometric/Keystore equivalent design decision made yet either, per
 * CLAUDE.md's Web Sprint roadmap notes on Sprint 3-7). Persisting a session
 * across reloads is left for a later sprint once that design decision is
 * made deliberately, not accidentally via convenience localStorage use.
 */
export interface AuthState {
  email: string | null
  sessionToken: string | null
  vdk: Uint8Array | null
  status: 'idle' | 'loading' | 'error'
  errorMessage: string | null
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    email: null,
    sessionToken: null,
    vdk: null,
    status: 'idle',
    errorMessage: null,
  }),

  getters: {
    isAuthenticated: (state): boolean => state.sessionToken !== null,
  },

  actions: {
    /**
     * Registers a new account. Does NOT log the user in afterward -- on
     * success, the caller (RegisterView) navigates to /login. Keeping
     * register and login as two explicit steps avoids silently skipping the
     * "no password recovery" disclosure the user should see on the flow
     * that follows, and keeps this action's error handling simple (a failed
     * register never leaves a half-authenticated session state to unwind).
     */
    async register(email: string, username: string, password: string): Promise<void> {
      this.status = 'loading'
      this.errorMessage = null

      const passwordBytes = new TextEncoder().encode(password)
      let masterKey: Uint8Array | null = null
      let authKey: Uint8Array | null = null
      let wrapKey: Uint8Array | null = null
      let vdk: Uint8Array | null = null

      try {
        const kdfSalt = generateKdfSalt()
        masterKey = await deriveMasterKeyFromPassword(passwordBytes, kdfSalt, DEFAULT_KDF_PARAMS)
        authKey = await deriveAuthKey(masterKey)
        wrapKey = await deriveWrapKey(masterKey)
        vdk = generateVaultDataKey()
        const wrappedVdk = await wrapVaultDataKey(vdk, wrapKey)

        await authApi.register({
          email,
          username,
          auth_key: bytesToBase64(authKey),
          kdf_salt: bytesToBase64(kdfSalt),
          kdf_params: toWireKdfParams(DEFAULT_KDF_PARAMS),
          kdf_version: CURRENT_KDF_VERSION,
          wrapped_vdk: bytesToBase64(wrappedVdk),
        })

        this.status = 'idle'
      } catch (err) {
        this.status = 'error'
        this.errorMessage = describeError(err)
        throw err
      } finally {
        // Best-effort zeroing of everything key-shaped that touched this
        // action's stack -- doesn't guarantee the JS engine hasn't kept a
        // copy elsewhere (a disclosed, unavoidable JS-vs-native limitation,
        // same caveat Android's own crypto doc comments note for password
        // Strings vs CharArrays), but costs nothing and helps in the common
        // case.
        passwordBytes.fill(0)
        masterKey?.fill(0)
        authKey?.fill(0)
        wrapKey?.fill(0)
        vdk?.fill(0)
      }
    },

    /**
     * Logs in: prelogin -> derive MasterKey/AuthKey -> login -> derive
     * WrapKey -> unwrap VDK. On success, session_token/email/vdk land in
     * this store's state (in-memory only, see class doc comment above).
     */
    async login(email: string, password: string): Promise<void> {
      this.status = 'loading'
      this.errorMessage = null

      const passwordBytes = new TextEncoder().encode(password)
      let masterKey: Uint8Array | null = null
      let authKey: Uint8Array | null = null
      let wrapKey: Uint8Array | null = null

      try {
        const pre = await authApi.prelogin(email)
        const kdfSalt = base64ToBytes(pre.kdf_salt)
        const kdfParams = fromWireKdfParams(pre.kdf_params)

        masterKey = await deriveMasterKeyFromPassword(passwordBytes, kdfSalt, kdfParams)
        authKey = await deriveAuthKey(masterKey)

        const result = await authApi.login({
          email,
          auth_key: bytesToBase64(authKey),
          device_identifier: getOrCreateDeviceId(),
          device_name: WEB_DEVICE_NAME,
        })

        wrapKey = await deriveWrapKey(masterKey)
        const vdk = await unwrapVaultDataKey(base64ToBytes(result.wrapped_vdk), wrapKey)

        this.sessionToken = result.session_token
        this.email = email
        this.vdk = vdk
        this.status = 'idle'
      } catch (err) {
        this.status = 'error'
        this.errorMessage = describeError(err)
        throw err
      } finally {
        passwordBytes.fill(0)
        masterKey?.fill(0)
        authKey?.fill(0)
        wrapKey?.fill(0)
      }
    },

    /**
     * Clears local session state first (so the UI reflects "logged out"
     * immediately and unconditionally), then makes a best-effort call to
     * revoke the session server-side. A network failure here must not trap
     * the user in a logged-in-looking state -- matches the backend's own
     * "logout is idempotent" contract.
     */
    async logout(): Promise<void> {
      const token = this.sessionToken

      this.vdk?.fill(0)
      this.sessionToken = null
      this.email = null
      this.vdk = null
      this.status = 'idle'
      this.errorMessage = null

      if (token) {
        try {
          await authApi.logout(token)
        } catch {
          // Best-effort; local state is already cleared, see doc comment.
        }
      }
    },
  },
})

function describeError(err: unknown): string {
  if (err instanceof authApi.ApiError) {
    switch (err.code) {
      case 'invalid_credentials':
        return 'Incorrect email or password.'
      case 'too_many_attempts':
        return 'Too many failed attempts. Please try again later.'
      case 'email_taken':
        return 'An account with this email already exists.'
      default:
        return err.message
    }
  }
  return err instanceof Error ? err.message : 'An unexpected error occurred.'
}
