import {
  type AutoLockTimeoutId,
  type ClipboardClearDelayId,
  AUTO_LOCK_TIMEOUT_OPTIONS,
  CLIPBOARD_CLEAR_DELAY_OPTIONS,
  DEFAULT_AUTO_LOCK_TIMEOUT_ID,
  DEFAULT_CLIPBOARD_CLEAR_DELAY_ID,
} from '../types/lock'

const AUTO_LOCK_KEY = 'vk_auto_lock_timeout'
const CLIPBOARD_DELAY_KEY = 'vk_clipboard_clear_delay'

/**
 * Persists auto-lock timeout + clipboard clear delay preferences to
 * localStorage. Both are non-secret UI preferences (an id string like
 * `"5m"`), not key material or session state -- same "fine to persist"
 * category as `services/device.ts`'s device id, explicitly NOT the same
 * category as the VDK/session token in `stores/auth.ts`, which stay
 * in-memory only. Injectable `Storage` param (defaults to real
 * `localStorage`) purely so this is unit-testable without touching jsdom's
 * global, mirroring `device.test.ts`'s fake-storage pattern.
 */
export function loadAutoLockTimeoutId(storage: Storage = localStorage): AutoLockTimeoutId {
  const raw = storage.getItem(AUTO_LOCK_KEY)
  const found = AUTO_LOCK_TIMEOUT_OPTIONS.find((o) => o.id === raw)
  return found ? found.id : DEFAULT_AUTO_LOCK_TIMEOUT_ID
}

export function saveAutoLockTimeoutId(id: AutoLockTimeoutId, storage: Storage = localStorage): void {
  storage.setItem(AUTO_LOCK_KEY, id)
}

export function loadClipboardClearDelayId(storage: Storage = localStorage): ClipboardClearDelayId {
  const raw = storage.getItem(CLIPBOARD_DELAY_KEY)
  const found = CLIPBOARD_CLEAR_DELAY_OPTIONS.find((o) => o.id === raw)
  return found ? found.id : DEFAULT_CLIPBOARD_CLEAR_DELAY_ID
}

export function saveClipboardClearDelayId(id: ClipboardClearDelayId, storage: Storage = localStorage): void {
  storage.setItem(CLIPBOARD_DELAY_KEY, id)
}
