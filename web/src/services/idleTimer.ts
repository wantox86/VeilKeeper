import { shouldArmIdleTimer, shouldLockImmediatelyOnHide, shouldLockOnResume } from './autoLockPolicy'

const ACTIVITY_EVENTS = ['mousemove', 'mousedown', 'keydown', 'touchstart', 'scroll'] as const

export interface InactivityWatcherOptions {
  /** Auto-lock timeout in ms; 0 means "Immediately" (lock on background, see autoLockPolicy.ts). */
  timeoutMs: number
  onLock: () => void
  /** Injectable clock, purely for deterministic tests -- defaults to the real one. */
  now?: () => number
  /** Injectable target, defaults to the real window/document -- lets tests use jsdom without touching real globals. */
  target?: {
    addEventListener: typeof window.addEventListener
    removeEventListener: typeof window.removeEventListener
    setTimeout: typeof window.setTimeout
    clearTimeout: typeof window.clearTimeout
  }
  doc?: Pick<Document, 'addEventListener' | 'removeEventListener'> & {
    visibilityState: DocumentVisibilityState
  }
}

/**
 * Web Session Lock (SPEC-BASE.md Section 32) inactivity detection --
 * mirrors Android's `AutoLockManager` shape (a `DefaultLifecycleObserver`
 * reacting to background/foreground + a screen-off receiver) adapted to
 * Web's two available signals:
 *
 *  - **Browser inactivity**: no mouse/keyboard/touch/scroll activity for
 *    `timeoutMs` while the tab is visible -- a foreground `setTimeout` reset
 *    on every activity event, the direct Web analogue of Android's
 *    screen-on countdown.
 *  - **Tab hidden / application background**: `visibilitychange`. Mirrors
 *    Android's two triggers together -- `timeoutMs === 0` ("Immediately")
 *    locks the instant the tab is hidden (like Android's screen-off
 *    receiver, which locks unconditionally regardless of the configured
 *    timeout); any other timeout instead records the hide time and, on
 *    becoming visible again, locks if the elapsed hidden time already
 *    exceeded the timeout (mirrors `AutoLockManager`'s "record background
 *    timestamp, check elapsed on next foreground resume" logic exactly).
 *
 * No `setInterval` while hidden: background tabs throttle/suspend timers
 * unpredictably in every major browser, so this deliberately does the
 * elapsed-time math on resume instead of trying to keep a timer ticking
 * while backgrounded (same reasoning Android's own comment gives for using
 * a timestamp-based check instead of a running countdown across
 * background/foreground transitions).
 */
export function createInactivityWatcher(options: InactivityWatcherOptions) {
  const now = options.now ?? (() => Date.now())
  const target = options.target ?? window
  const doc = options.doc ?? document

  let timeoutMs = options.timeoutMs
  let timerId: ReturnType<typeof setTimeout> | undefined
  let hiddenAt: number | null = null
  let started = false

  function clearTimer(): void {
    if (timerId !== undefined) {
      target.clearTimeout(timerId)
      timerId = undefined
    }
  }

  function armIdleTimer(): void {
    clearTimer()
    if (!shouldArmIdleTimer(timeoutMs)) return
    timerId = target.setTimeout(options.onLock, timeoutMs)
  }

  function handleActivity(): void {
    if (!started) return
    if (doc.visibilityState === 'visible') {
      armIdleTimer()
    }
  }

  function handleVisibilityChange(): void {
    if (!started) return
    if (doc.visibilityState === 'hidden') {
      hiddenAt = now()
      clearTimer()
      if (shouldLockImmediatelyOnHide(timeoutMs)) {
        options.onLock()
      }
      return
    }

    // Becoming visible again.
    if (hiddenAt !== null) {
      const elapsed = now() - hiddenAt
      hiddenAt = null
      if (shouldLockOnResume(elapsed, timeoutMs)) {
        options.onLock()
        return
      }
    }
    armIdleTimer()
  }

  function start(): void {
    if (started) return
    started = true
    for (const event of ACTIVITY_EVENTS) {
      target.addEventListener(event, handleActivity, { passive: true })
    }
    doc.addEventListener('visibilitychange', handleVisibilityChange)
    armIdleTimer()
  }

  function stop(): void {
    if (!started) return
    started = false
    for (const event of ACTIVITY_EVENTS) {
      target.removeEventListener(event, handleActivity)
    }
    doc.removeEventListener('visibilitychange', handleVisibilityChange)
    clearTimer()
    hiddenAt = null
  }

  /** Called when the user changes the Settings timeout while the watcher is already running. */
  function setTimeoutMs(next: number): void {
    timeoutMs = next
    if (started && doc.visibilityState === 'visible') {
      armIdleTimer()
    }
  }

  return { start, stop, setTimeoutMs }
}

export type InactivityWatcher = ReturnType<typeof createInactivityWatcher>
