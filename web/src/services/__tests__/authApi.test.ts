import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, prelogin, register, login, logout } from '../authApi'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('authApi', () => {
  it('prelogin sends email and returns the parsed KDF params', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, {
        kdf_salt: 'c2FsdA==',
        kdf_params: { memory: 65536, iterations: 3, parallelism: 4 },
        kdf_version: 1,
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await prelogin('user@example.com')

    expect(result.kdf_salt).toBe('c2FsdA==')
    expect(result.kdf_params.memory).toBe(65536)
    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(init.body as string)).toEqual({ email: 'user@example.com' })
  })

  it('register posts the full registration payload and returns user_id/email', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(201, { user_id: 1, email: 'user@example.com' }))
    vi.stubGlobal('fetch', fetchMock)

    const result = await register({
      email: 'user@example.com',
      username: 'someone',
      auth_key: 'YQ==',
      kdf_salt: 'c2FsdA==',
      kdf_params: { memory: 65536, iterations: 3, parallelism: 4 },
      kdf_version: 1,
      wrapped_vdk: 'dmRr',
      invite_code: 'family2026',
    })

    expect(result).toEqual({ user_id: 1, email: 'user@example.com' })
  })

  it('register throws ApiError with the backend error code on 409 email_taken', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(409, { error: 'email_taken', message: 'an account with this email already exists' }),
        ),
    )

    await expect(
      register({
        email: 'dup@example.com',
        username: 'someone',
        auth_key: 'YQ==',
        kdf_salt: 'c2FsdA==',
        kdf_params: { memory: 65536, iterations: 3, parallelism: 4 },
        kdf_version: 1,
        wrapped_vdk: 'dmRr',
        invite_code: 'family2026',
      }),
    ).rejects.toMatchObject({ status: 409, code: 'email_taken' })
  })

  it('register throws ApiError with the backend error code on 403 invalid_invite_code', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(jsonResponse(403, { error: 'invalid_invite_code', message: 'invalid invite code' })),
    )

    await expect(
      register({
        email: 'user@example.com',
        username: 'someone',
        auth_key: 'YQ==',
        kdf_salt: 'c2FsdA==',
        kdf_params: { memory: 65536, iterations: 3, parallelism: 4 },
        kdf_version: 1,
        wrapped_vdk: 'dmRr',
        invite_code: 'wrong-code',
      }),
    ).rejects.toMatchObject({ status: 403, code: 'invalid_invite_code' })
  })

  it('login throws ApiError(invalid_credentials) on 401', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(401, { error: 'invalid_credentials', message: 'invalid email or auth key' }),
        ),
    )

    await expect(
      login({
        email: 'user@example.com',
        auth_key: 'YQ==',
        device_identifier: 'device-1',
        device_name: 'Test',
      }),
    ).rejects.toBeInstanceOf(ApiError)
  })

  it('login returns the full session payload on success', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(200, {
          session_token: 'tok',
          expires_at: '2030-01-01T00:00:00Z',
          wrapped_vdk: 'dmRr',
          kdf_salt: 'c2FsdA==',
          kdf_params: { memory: 65536, iterations: 3, parallelism: 4 },
          kdf_version: 1,
        }),
      ),
    )

    const result = await login({
      email: 'user@example.com',
      auth_key: 'YQ==',
      device_identifier: 'device-1',
      device_name: 'Test',
    })
    expect(result.session_token).toBe('tok')
  })

  it('logout sends the bearer token and resolves on 204', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(logout('my-session-token')).resolves.toBeUndefined()

    const [, init] = fetchMock.mock.calls[0]
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer my-session-token')
  })

  it('logout throws ApiError on a non-2xx response', async () => {
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockResolvedValue(
          jsonResponse(401, { error: 'unauthorized', message: 'missing or malformed Authorization header' }),
        ),
    )

    await expect(logout('bad-token')).rejects.toMatchObject({ status: 401, code: 'unauthorized' })
  })
})
