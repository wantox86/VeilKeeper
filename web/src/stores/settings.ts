import { defineStore } from 'pinia'
import {
  loadAutoLockTimeoutId,
  saveAutoLockTimeoutId,
  loadClipboardClearDelayId,
  saveClipboardClearDelayId,
} from '../services/settingsStorage'
import {
  findAutoLockOption,
  findClipboardDelayOption,
  type AutoLockTimeoutId,
  type ClipboardClearDelayId,
} from '../types/lock'

export interface SettingsState {
  autoLockTimeoutId: AutoLockTimeoutId
  clipboardClearDelayId: ClipboardClearDelayId
}

/**
 * Settings screen scope (task item 5, mirrors Android's Settings screen
 * scope, not its exact UI): auto-lock timeout + clipboard clear delay only,
 * deliberately minimal per SPEC-BASE.md Section 56 Rule 1 -- same
 * "nothing else, no theme/profile settings" restraint Android's own
 * `SettingsScreen.kt` doc comment calls out. No biometric toggle here (no
 * Web equivalent exists).
 */
export const useSettingsStore = defineStore('settings', {
  state: (): SettingsState => ({
    autoLockTimeoutId: loadAutoLockTimeoutId(),
    clipboardClearDelayId: loadClipboardClearDelayId(),
  }),

  getters: {
    autoLockTimeoutMs: (state): number => findAutoLockOption(state.autoLockTimeoutId).ms,
    clipboardClearDelayMs: (state): number => findClipboardDelayOption(state.clipboardClearDelayId).ms,
  },

  actions: {
    setAutoLockTimeout(id: AutoLockTimeoutId): void {
      this.autoLockTimeoutId = id
      saveAutoLockTimeoutId(id)
    },
    setClipboardClearDelay(id: ClipboardClearDelayId): void {
      this.clipboardClearDelayId = id
      saveClipboardClearDelayId(id)
    },
  },
})
