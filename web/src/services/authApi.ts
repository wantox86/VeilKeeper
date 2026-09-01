import { apiFetch } from './api'
import type {
  ApiErrorBody,
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
 */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, code: string, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

async function parseJsonOrThrow<T>(response: Response): Promise<T> {
  // Never assume a body is present/parseable -- a network-layer failure or
  // an unexpected non-JSON error page must still surface a sane ApiError
  // rather than an unrelated JSON-parse exception.
  const body = await response.json().catch(() => null)

  if (!response.ok) {
    const errorBody = body as ApiErrorBody | null
    throw new ApiError(
      response.status,
      errorBody?.error ?? 'unknown_error',
      errorBody?.message ?? `Request failed with HTTP ${response.status}`,
    )
  }

  return body as T
}

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
