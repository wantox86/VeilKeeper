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
