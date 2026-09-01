/**
 * Base API client. Base URL is configurable via the `VITE_API_BASE_URL` env
 * var (see `.env.example`) -- never hardcode the live backend URL here,
 * mirroring the pattern Android uses for its `apiBaseUrl` Gradle property
 * (`android/app/build.gradle.kts`).
 */
const DEFAULT_BASE_URL = 'http://localhost:18091/'

function resolveBaseUrl(): string {
  const configured = import.meta.env.VITE_API_BASE_URL as string | undefined
  const base = configured && configured.trim().length > 0 ? configured : DEFAULT_BASE_URL
  return base.endsWith('/') ? base : `${base}/`
}

export function apiUrl(path: string): string {
  const relativePath = path.startsWith('/') ? path.slice(1) : path
  return new URL(relativePath, resolveBaseUrl()).toString()
}

export async function apiFetch(path: string, init?: RequestInit): Promise<Response> {
  return fetch(apiUrl(path), init)
}

/**
 * Shared error type for every backend call (auth and vault alike) --
 * originally lived only in `authApi.ts` (Web Sprint 2); pulled up here in
 * Sprint 3 so `vaultApi.ts` can reuse it instead of duplicating the same
 * class. `authApi.ts` re-exports it so existing imports keep working.
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

interface ApiErrorBody {
  error?: string
  message?: string
}

/**
 * Parses a JSON response body, throwing `ApiError` on any non-2xx status.
 * Never assumes a body is present/parseable -- a network-layer failure or an
 * unexpected non-JSON error page must still surface a sane `ApiError` rather
 * than an unrelated JSON-parse exception.
 */
export async function parseJsonOrThrow<T>(response: Response): Promise<T> {
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
