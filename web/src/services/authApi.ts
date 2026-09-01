import { apiFetch, ApiError, parseJsonOrThrow } from './api'
import type {
  LoginRequest,
  LoginResponse,
  PreloginResponse,
  RegisterRequest,
  RegisterResponse,
} from '../types/auth'

/**
 * Thin wrappers over `POST /api/v1/auth/{prelogin,register,login,logout}` --
 * the same endpoints Android's Sprint 1 `AuthApi`/`AuthRepository` call.
 * This layer only knows about HTTP/JSON; all crypto orchestration happens
 * one level up, in `stores/auth.ts` (mirrors Android's
 * Repository-does-crypto-orchestration / Api-is-dumb-HTTP split).
 *
 * `ApiError` re-exported for backward compatibility -- it now lives in
 * `api.ts` (Web Sprint 3) so `vaultApi.ts` can share it too.
 */
export { ApiError }

function postJSON(path: string, body: unknown): Promise<Response> {
  return apiFetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

export async function prelogin(email: string): Promise<PreloginResponse> {
  const response = await postJSON('api/v1/auth/prelogin', { email })
  return parseJsonOrThrow<PreloginResponse>(response)
}

export async function register(req: RegisterRequest): Promise<RegisterResponse> {
  const response = await postJSON('api/v1/auth/register', req)
  return parseJsonOrThrow<RegisterResponse>(response)
}

export async function login(req: LoginRequest): Promise<LoginResponse> {
  const response = await postJSON('api/v1/auth/login', req)
  return parseJsonOrThrow<LoginResponse>(response)
}

/**
 * Idempotent per the backend's own contract -- revoking an already-revoked
 * or nonexistent session still returns 204. Callers should treat this as
 * best-effort: local session state should be cleared regardless of whether
 * this call succeeds (see `stores/auth.ts#logout`).
 */
export async function logout(sessionToken: string): Promise<void> {
  const response = await apiFetch('api/v1/auth/logout', {
    method: 'POST',
    headers: { Authorization: `Bearer ${sessionToken}` },
  })
  if (!response.ok) {
    await parseJsonOrThrow(response)
  }
}
