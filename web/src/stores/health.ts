import { defineStore } from 'pinia'
import { checkHealth } from '../services/health'

interface HealthState {
  status: 'idle' | 'checking' | 'ok' | 'error'
  message: string | null
}

export const useHealthStore = defineStore('health', {
  state: (): HealthState => ({
    status: 'idle',
    message: null,
  }),
  actions: {
    async check(): Promise<void> {
      this.status = 'checking'
      this.message = null
      try {
        const result = await checkHealth()
        this.status = 'ok'
        this.message = result.status
      } catch (err) {
        this.status = 'error'
        this.message = err instanceof Error ? err.message : 'Unknown error'
      }
    },
  },
})
