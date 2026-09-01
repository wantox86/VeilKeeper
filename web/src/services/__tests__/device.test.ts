import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getOrCreateDeviceId } from '../device'

function fakeLocalStorage(): Storage {
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

beforeEach(() => {
  vi.stubGlobal('localStorage', fakeLocalStorage())
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('getOrCreateDeviceId', () => {
  it('generates and persists a device id on first call', () => {
    expect(localStorage.getItem('vk_device_id')).toBeNull()
    const id = getOrCreateDeviceId()
    expect(id).toMatch(/^[0-9a-f-]{36}$/)
    expect(localStorage.getItem('vk_device_id')).toBe(id)
  })

  it('returns the same id on subsequent calls', () => {
    const first = getOrCreateDeviceId()
    const second = getOrCreateDeviceId()
    expect(second).toBe(first)
  })
})
