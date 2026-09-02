import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createInactivityWatcher } from '../idleTimer'

interface FakeDoc {
  addEventListener: (type: string, listener: EventListenerOrEventListenerObject) => void
  removeEventListener: (type: string, listener: EventListenerOrEventListenerObject) => void
  visibilityState: DocumentVisibilityState
  fireVisibilityChange(next: DocumentVisibilityState): void
}

function makeFakeTarget() {
  const et = new EventTarget()
  return {
    addEventListener: (type: string, listener: EventListenerOrEventListenerObject, opts?: unknown) =>
      et.addEventListener(type, listener, opts as AddEventListenerOptions),
    removeEventListener: (type: string, listener: EventListenerOrEventListenerObject) =>
      et.removeEventListener(type, listener),
    setTimeout: (...args: Parameters<typeof setTimeout>) => setTimeout(...args),
    clearTimeout: (...args: Parameters<typeof clearTimeout>) => clearTimeout(...args),
    fire: (type: string) => et.dispatchEvent(new Event(type)),
  } as unknown as typeof window & { fire: (type: string) => void }
}

function makeFakeDoc(initial: DocumentVisibilityState = 'visible'): FakeDoc {
  const et = new EventTarget()
  let state = initial
  return {
    addEventListener: (type, listener) => et.addEventListener(type, listener),
    removeEventListener: (type, listener) => et.removeEventListener(type, listener),
    get visibilityState() {
      return state
    },
    set visibilityState(v) {
      state = v
    },
    fireVisibilityChange(next) {
      state = next
      et.dispatchEvent(new Event('visibilitychange'))
    },
  }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('createInactivityWatcher', () => {
  it('locks after timeoutMs of no activity while the tab stays visible', () => {
    const target = makeFakeTarget()
    const doc = makeFakeDoc('visible')
    const onLock = vi.fn()

    const watcher = createInactivityWatcher({ timeoutMs: 60_000, onLock, target, doc })
    watcher.start()

    vi.advanceTimersByTime(59_999)
    expect(onLock).not.toHaveBeenCalled()

    vi.advanceTimersByTime(1)
    expect(onLock).toHaveBeenCalledTimes(1)
  })

  it('activity resets the idle timer', () => {
    const target = makeFakeTarget()
    const doc = makeFakeDoc('visible')
    const onLock = vi.fn()

    const watcher = createInactivityWatcher({ timeoutMs: 60_000, onLock, target, doc })
    watcher.start()

    vi.advanceTimersByTime(50_000)
    target.fire('mousemove')
    vi.advanceTimersByTime(50_000)
    expect(onLock).not.toHaveBeenCalled() // only 50s since the reset, not 100s

    vi.advanceTimersByTime(10_000)
    expect(onLock).toHaveBeenCalledTimes(1)
  })

  it('"Immediately" (timeoutMs=0) locks the instant the tab is hidden, never on foreground idle alone', () => {
    const target = makeFakeTarget()
    const doc = makeFakeDoc('visible')
    const onLock = vi.fn()

    const watcher = createInactivityWatcher({ timeoutMs: 0, onLock, target, doc })
    watcher.start()

    vi.advanceTimersByTime(10 * 60_000) // sitting idle but visible -- must never lock on its own
    expect(onLock).not.toHaveBeenCalled()

    doc.fireVisibilityChange('hidden')
    expect(onLock).toHaveBeenCalledTimes(1)
  })

  it('a positive timeout does not lock on hide alone, but does once elapsed hidden time exceeds it on resume', () => {
    let now = 1_000_000
    const target = makeFakeTarget()
    const doc = makeFakeDoc('visible')
    const onLock = vi.fn()

    const watcher = createInactivityWatcher({
      timeoutMs: 60_000,
      onLock,
      target,
      doc,
      now: () => now,
    })
    watcher.start()

    doc.fireVisibilityChange('hidden')
    expect(onLock).not.toHaveBeenCalled() // hiding itself never locks for a non-zero timeout

    now += 30_000 // back before the timeout elapsed
    doc.fireVisibilityChange('visible')
    expect(onLock).not.toHaveBeenCalled()

    doc.fireVisibilityChange('hidden')
    now += 90_000 // now well past the timeout
    doc.fireVisibilityChange('visible')
    expect(onLock).toHaveBeenCalledTimes(1)
  })

  it('stop() removes listeners and cancels the pending timer -- no lock fires afterward', () => {
    const target = makeFakeTarget()
    const doc = makeFakeDoc('visible')
    const onLock = vi.fn()

    const watcher = createInactivityWatcher({ timeoutMs: 60_000, onLock, target, doc })
    watcher.start()
    watcher.stop()

    vi.advanceTimersByTime(120_000)
    target.fire('mousemove')
    doc.fireVisibilityChange('hidden')

    expect(onLock).not.toHaveBeenCalled()
  })

  it('setTimeoutMs re-arms the idle timer with the new duration while visible', () => {
    const target = makeFakeTarget()
    const doc = makeFakeDoc('visible')
    const onLock = vi.fn()

    const watcher = createInactivityWatcher({ timeoutMs: 60_000, onLock, target, doc })
    watcher.start()

    watcher.setTimeoutMs(10_000)
    vi.advanceTimersByTime(10_000)
    expect(onLock).toHaveBeenCalledTimes(1)
  })
})
