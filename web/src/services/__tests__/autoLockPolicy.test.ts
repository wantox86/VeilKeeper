import { describe, expect, it } from 'vitest'
import { shouldArmIdleTimer, shouldLockImmediatelyOnHide, shouldLockOnResume } from '../autoLockPolicy'

describe('shouldArmIdleTimer', () => {
  it('is false for "Immediately" (0ms) -- nothing meaningful to idle-time', () => {
    expect(shouldArmIdleTimer(0)).toBe(false)
  })

  it('is true for any positive timeout', () => {
    expect(shouldArmIdleTimer(60_000)).toBe(true)
    expect(shouldArmIdleTimer(1)).toBe(true)
  })
})

describe('shouldLockImmediatelyOnHide', () => {
  it('is true only for "Immediately" (0ms or less)', () => {
    expect(shouldLockImmediatelyOnHide(0)).toBe(true)
    expect(shouldLockImmediatelyOnHide(-1)).toBe(true)
  })

  it('is false for any positive timeout -- those wait for the elapsed check on resume', () => {
    expect(shouldLockImmediatelyOnHide(60_000)).toBe(false)
  })
})

describe('shouldLockOnResume', () => {
  it('locks when hidden elapsed time meets or exceeds the timeout', () => {
    expect(shouldLockOnResume(60_000, 60_000)).toBe(true)
    expect(shouldLockOnResume(70_000, 60_000)).toBe(true)
  })

  it('does not lock when hidden elapsed time is under the timeout', () => {
    expect(shouldLockOnResume(30_000, 60_000)).toBe(false)
  })

  it('always locks once elapsed for a 0ms ("Immediately") timeout', () => {
    expect(shouldLockOnResume(0, 0)).toBe(true)
    expect(shouldLockOnResume(1, 0)).toBe(true)
  })
})
