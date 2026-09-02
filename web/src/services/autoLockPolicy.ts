/**
 * Pure decision functions for the inactivity/session lock, split out from
 * `idleTimer.ts`'s DOM wiring for the same testability reason Android's
 * `AutoLockPolicy` object exists (see CLAUDE.md's Web Sprint roadmap /
 * Android Sprint 3 notes) -- no timers, no `window`/`document`, just math,
 * so it's trivially unit-testable without jsdom or fake timers.
 */

/** `timeoutMs === 0` ("Immediately") means "lock as soon as the tab is hidden," not "lock instantly while visible" -- so the foreground idle timer never arms for it (there is nothing meaningful to time). */
export function shouldArmIdleTimer(timeoutMs: number): boolean {
  return timeoutMs > 0
}

/** Whether becoming hidden should lock immediately, without waiting for the tab to become visible again. */
export function shouldLockImmediatelyOnHide(timeoutMs: number): boolean {
  return timeoutMs <= 0
}

/** Called when the tab regains visibility: was it hidden long enough to have locked while away? */
export function shouldLockOnResume(hiddenElapsedMs: number, timeoutMs: number): boolean {
  return hiddenElapsedMs >= timeoutMs
}
