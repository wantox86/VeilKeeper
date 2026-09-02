import { describe, expect, it } from 'vitest'
import {
  loadAutoLockTimeoutId,
  saveAutoLockTimeoutId,
  loadClipboardClearDelayId,
  saveClipboardClearDelayId,
} from '../settingsStorage'
import { DEFAULT_AUTO_LOCK_TIMEOUT_ID, DEFAULT_CLIPBOARD_CLEAR_DELAY_ID } from '../../types/lock'

function fakeStorage(): Storage {
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

describe('settingsStorage', () => {
  it('defaults to DEFAULT_AUTO_LOCK_TIMEOUT_ID when nothing is stored', () => {
    expect(loadAutoLockTimeoutId(fakeStorage())).toBe(DEFAULT_AUTO_LOCK_TIMEOUT_ID)
  })

  it('round-trips a saved auto-lock timeout id', () => {
    const storage = fakeStorage()
    saveAutoLockTimeoutId('immediately', storage)
    expect(loadAutoLockTimeoutId(storage)).toBe('immediately')
  })

  it('falls back to the default for a corrupted/unknown stored auto-lock id', () => {
    const storage = fakeStorage()
    storage.setItem('vk_auto_lock_timeout', 'nonsense')
    expect(loadAutoLockTimeoutId(storage)).toBe(DEFAULT_AUTO_LOCK_TIMEOUT_ID)
  })

  it('defaults to DEFAULT_CLIPBOARD_CLEAR_DELAY_ID when nothing is stored', () => {
    expect(loadClipboardClearDelayId(fakeStorage())).toBe(DEFAULT_CLIPBOARD_CLEAR_DELAY_ID)
  })

  it('round-trips a saved clipboard clear delay id', () => {
    const storage = fakeStorage()
    saveClipboardClearDelayId('60s', storage)
    expect(loadClipboardClearDelayId(storage)).toBe('60s')
  })

  it('falls back to the default for a corrupted/unknown stored clipboard delay id', () => {
    const storage = fakeStorage()
    storage.setItem('vk_clipboard_clear_delay', 'nonsense')
    expect(loadClipboardClearDelayId(storage)).toBe(DEFAULT_CLIPBOARD_CLEAR_DELAY_ID)
  })
})
