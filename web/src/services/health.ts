import { apiFetch } from './api'

export interface HealthStatus {
  status: string
}

/** Calls the backend's `GET /health` (pure liveness, no auth). */
export async function checkHealth(): Promise<HealthStatus> {
  const response = await apiFetch('health')
  if (!response.ok) {
    throw new Error(`Health check failed: HTTP ${response.status}`)
  }
  return (await response.json()) as HealthStatus
}
