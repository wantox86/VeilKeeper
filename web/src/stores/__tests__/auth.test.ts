import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../auth'
import * as authApi from '../../services/authApi'
import {
  deriveMasterKeyFromPassword,
  deriveWrapKey,
  wrapVaultDataKey,
  generateVaultDataKey,
} from '../../crypto/vaultCrypto'
import { DEFAULT_KDF_PARAMS, CURRENT_KDF_VERSION } from '../../crypto/kdfParams'
import { bytesToBase64 } from '../../crypto/base64'

function fakeLocalStorage(): Storage {
  const store = new Map<string, string>()
  return {
    getItem: (key: string) => store.get(key) ?? null,
    setItem: (key: string, value: string) => void store.set(key, value),
    removeItem: (key: string) => void store.delete(key),
    clear: () => store.clear(),
    key: () => null,
    get length() {
      return store.size
    },
  } as Storage
}

const PASSWORD = 'correct horse battery staple'
const KDF_SALT = crypto.getRandomValues(new Uint8Array(16))

beforeEach(() => {
  setActivePinia(createPinia())
  vi.stubGlobal('localStorage', fakeLocalStorage())
})

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('useAuthStore', () => {
  it('starts unauthenticated', () => {
    const store = useAuthStore()
    expect(store.isAuthenticated).toBe(false)
    expect(store.email).toBeNull()
    expect(store.sessionToken).toBeNull()
  })

  it('register() succeeds without mutating auth state (no auto-login)', async () => {
    vi.spyOn(authApi, 'register').mockResolvedValue({ user_id: 1, email: 'user@example.com' })

    const store = useAuthStore()
    await store.register('user@example.com', 'someone', PASSWORD, 'family2026')

    expect(store.status).toBe('idle')
    expect(store.errorMessage).toBeNull()
    expect(store.isAuthenticated).toBe(false) // register never logs the user in

    const call = vi.mocked(authApi.register).mock.calls[0][0]
    expect(call.email).toBe('user@example.com')
    expect(call.kdf_version).toBe(CURRENT_KDF_VERSION)
    expect(call.kdf_params.memory).toBe(DEFAULT_KDF_PARAMS.memoryKiB)
    expect(call.invite_code).toBe('family2026')
  })

  it('register() surfaces a friendly message on email_taken (409)', async () => {
    vi.spyOn(authApi, 'register').mockRejectedValue(
      new authApi.ApiError(409, 'email_taken', 'an account with this email already exists'),
    )

    const store = useAuthStore()
    await expect(
      store.register('dup@example.com', 'someone', PASSWORD, 'family2026'),
    ).rejects.toBeInstanceOf(authApi.ApiError)

    expect(store.status).toBe('error')
    expect(store.errorMessage).toBe('An account with this email already exists.')
  })

  it('register() surfaces a friendly message on invalid_invite_code (403)', async () => {
    vi.spyOn(authApi, 'register').mockRejectedValue(
      new authApi.ApiError(403, 'invalid_invite_code', 'invalid invite code'),
    )

    const store = useAuthStore()
    await expect(
      store.register('user@example.com', 'someone', PASSWORD, 'wrong-code'),
    ).rejects.toBeInstanceOf(authApi.ApiError)

    expect(store.status).toBe('error')
    expect(store.errorMessage).toBe('Invalid invite code.')
  })

  it('register() surfaces a friendly message on registration_closed (403)', async () => {
    vi.spyOn(authApi, 'register').mockRejectedValue(
      new authApi.ApiError(403, 'registration_closed', 'registration is currently closed'),
    )

    const store = useAuthStore()
    await expect(
      store.register('user@example.com', 'someone', PASSWORD, 'anything'),
    ).rejects.toBeInstanceOf(authApi.ApiError)

    expect(store.status).toBe('error')
    expect(store.errorMessage).toBe('Registration is currently closed.')
  })

  it('login() derives the correct AuthKey, unwraps the VDK, and populates session state', async () => {
    // Simulate what the server would have stored at registration time: wrap
    // a known VDK with the WrapKey derived from this exact password/salt.
    const masterKey = await deriveMasterKeyFromPassword(
      new TextEncoder().encode(PASSWORD),
      KDF_SALT,
      DEFAULT_KDF_PARAMS,
    )
    const wrapKey = await deriveWrapKey(masterKey)
    const vdk = generateVaultDataKey()
    const wrappedVdk = await wrapVaultDataKey(vdk, wrapKey)

    vi.spyOn(authApi, 'prelogin').mockResolvedValue({
      kdf_salt: bytesToBase64(KDF_SALT),
      kdf_params: {
        memory: DEFAULT_KDF_PARAMS.memoryKiB,
        iterations: DEFAULT_KDF_PARAMS.iterations,
        parallelism: DEFAULT_KDF_PARAMS.parallelism,
      },
      kdf_version: CURRENT_KDF_VERSION,
    })
    vi.spyOn(authApi, 'login').mockResolvedValue({
      session_token: 'session-abc',
      expires_at: '2030-01-01T00:00:00Z',
      wrapped_vdk: bytesToBase64(wrappedVdk),
      kdf_salt: bytesToBase64(KDF_SALT),
      kdf_params: {
        memory: DEFAULT_KDF_PARAMS.memoryKiB,
        iterations: DEFAULT_KDF_PARAMS.iterations,
        parallelism: DEFAULT_KDF_PARAMS.parallelism,
      },
      kdf_version: CURRENT_KDF_VERSION,
    })

    const store = useAuthStore()
    await store.login('user@example.com', PASSWORD)

    expect(store.isAuthenticated).toBe(true)
    expect(store.sessionToken).toBe('session-abc')
    expect(store.email).toBe('user@example.com')
    expect(store.vdk).toEqual(vdk) // proves unwrap actually used the right key hierarchy

    // login() must send the AuthKey (HKDF-derived), never anything
    // password-shaped.
    const loginCall = vi.mocked(authApi.login).mock.calls[0][0]
    expect(loginCall.auth_key).not.toContain(PASSWORD)
  }, 20000)

  it('login() surfaces a generic message on invalid_credentials (401) and leaves state unauthenticated', async () => {
    vi.spyOn(authApi, 'prelogin').mockResolvedValue({
      kdf_salt: bytesToBase64(KDF_SALT),
      kdf_params: {
        memory: DEFAULT_KDF_PARAMS.memoryKiB,
        iterations: DEFAULT_KDF_PARAMS.iterations,
        parallelism: DEFAULT_KDF_PARAMS.parallelism,
      },
      kdf_version: CURRENT_KDF_VERSION,
    })
    vi.spyOn(authApi, 'login').mockRejectedValue(
      new authApi.ApiError(401, 'invalid_credentials', 'invalid email or auth key'),
    )

    const store = useAuthStore()
    await expect(store.login('user@example.com', 'wrong password')).rejects.toBeInstanceOf(authApi.ApiError)

    expect(store.isAuthenticated).toBe(false)
    expect(store.errorMessage).toBe('Incorrect email or password.')
  }, 20000)

  it('logout() clears local state even if the server call fails, and is a no-op with no active session', async () => {
    const logoutSpy = vi.spyOn(authApi, 'logout').mockRejectedValue(new Error('network down'))

    const store = useAuthStore()
    store.$patch({ sessionToken: 'session-abc', email: 'user@example.com', vdk: new Uint8Array(32) })

    await store.logout()

    expect(store.isAuthenticated).toBe(false)
    expect(store.email).toBeNull()
    expect(store.vdk).toBeNull()
    expect(logoutSpy).toHaveBeenCalledWith('session-abc')

    logoutSpy.mockClear()
    await store.logout() // no session token this time
    expect(logoutSpy).not.toHaveBeenCalled()
  })

  describe('lock state machine (Web Session Lock, SPEC-BASE.md Section 32)', () => {
    async function loginFixture() {
      const masterKey = await deriveMasterKeyFromPassword(
        new TextEncoder().encode(PASSWORD),
        KDF_SALT,
        DEFAULT_KDF_PARAMS,
      )
      const wrapKey = await deriveWrapKey(masterKey)
      const vdk = generateVaultDataKey()
      const wrappedVdk = await wrapVaultDataKey(vdk, wrapKey)
      const kdfParamsWire = {
        memory: DEFAULT_KDF_PARAMS.memoryKiB,
        iterations: DEFAULT_KDF_PARAMS.iterations,
        parallelism: DEFAULT_KDF_PARAMS.parallelism,
      }

      vi.spyOn(authApi, 'prelogin').mockResolvedValue({
        kdf_salt: bytesToBase64(KDF_SALT),
        kdf_params: kdfParamsWire,
        kdf_version: CURRENT_KDF_VERSION,
      })
      vi.spyOn(authApi, 'login').mockResolvedValue({
        session_token: 'session-abc',
        expires_at: '2030-01-01T00:00:00Z',
        wrapped_vdk: bytesToBase64(wrappedVdk),
        kdf_salt: bytesToBase64(KDF_SALT),
        kdf_params: kdfParamsWire,
        kdf_version: CURRENT_KDF_VERSION,
      })

      const store = useAuthStore()
      await store.login('user@example.com', PASSWORD)
      return { store, vdk }
    }

    it('login() sets lockState to unlocked and stores unwrapMaterial', async () => {
      const { store } = await loginFixture()
      expect(store.lockState).toBe('unlocked')
      expect(store.unwrapMaterial).not.toBeNull()
    }, 20000)

    it('lock() clears the VDK but keeps the session token, email, and unwrapMaterial', async () => {
      const { store } = await loginFixture()
      const materialBefore = store.unwrapMaterial

      store.lock()

      expect(store.lockState).toBe('locked')
      expect(store.vdk).toBeNull()
      expect(store.sessionToken).toBe('session-abc')
      expect(store.email).toBe('user@example.com')
      expect(store.unwrapMaterial).toEqual(materialBefore)
      expect(store.isAuthenticated).toBe(true) // still counts as "has a session"
    }, 20000)

    it('lock() is a no-op when not currently unlocked', () => {
      const store = useAuthStore()
      expect(store.lockState).toBe('logged_out')
      store.lock()
      expect(store.lockState).toBe('logged_out')
    })

    it('unlockWithPassword() with the correct password restores the exact same VDK, no network call', async () => {
      const { store, vdk } = await loginFixture()
      store.lock()

      vi.mocked(authApi.login).mockClear()
      vi.mocked(authApi.prelogin).mockClear()

      await store.unlockWithPassword(PASSWORD)

      expect(store.lockState).toBe('unlocked')
      expect(store.vdk).toEqual(vdk)
      expect(authApi.login).not.toHaveBeenCalled()
      expect(authApi.prelogin).not.toHaveBeenCalled()
    }, 20000)

    it('unlockWithPassword() with the wrong password fails, stays locked, keeps the session', async () => {
      const { store } = await loginFixture()
      store.lock()

      await expect(store.unlockWithPassword('totally wrong password')).rejects.toThrow('Incorrect password.')

      expect(store.lockState).toBe('locked')
      expect(store.vdk).toBeNull()
      expect(store.errorMessage).toBe('Incorrect password.')
      expect(store.sessionToken).toBe('session-abc') // not logged out
    }, 20000)

    it('unlockWithPassword() throws if there is no locked session to unlock', async () => {
      const store = useAuthStore()
      await expect(store.unlockWithPassword(PASSWORD)).rejects.toThrow('No locked session to unlock.')
    })

    it('logout() from a locked state clears unwrapMaterial and resets lockState to logged_out', async () => {
      const { store } = await loginFixture()
      store.lock()

      await store.logout()

      expect(store.lockState).toBe('logged_out')
      expect(store.unwrapMaterial).toBeNull()
      expect(store.isAuthenticated).toBe(false)
    }, 20000)
  })
})
