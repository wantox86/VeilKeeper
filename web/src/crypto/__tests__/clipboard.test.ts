import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { copyToClipboard } from '../clipboard'

function fakeClipboard(writeTextImpl?: (v: string) => Promise<void>) {
  return {
    writeText: vi.fn(writeTextImpl ?? (() => Promise.resolve())),
  }
}

beforeEach(() => {
  vi.useFakeTimers()
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('copyToClipboard', () => {
  it('copies the value via navigator.clipboard.writeText', async () => {
    const clipboard = fakeClipboard()
    vi.stubGlobal('navigator', { clipboard })

    const result = await copyToClipboard('s3cret', 30_000)

    expect(result.copied).toBe(true)
    expect(clipboard.writeText).toHaveBeenCalledWith('s3cret')
  })

  it('schedules a clear after the given delay when delayMs > 0', async () => {
    const clipboard = fakeClipboard()
    vi.stubGlobal('navigator', { clipboard })

    const result = await copyToClipboard('s3cret', 30_000)
    expect(result.clearScheduled).toBe(true)

    clipboard.writeText.mockClear()
    await vi.advanceTimersByTimeAsync(30_000)

    expect(clipboard.writeText).toHaveBeenCalledWith('')
  })

  it('does not schedule a clear when delayMs is 0', async () => {
    const clipboard = fakeClipboard()
    vi.stubGlobal('navigator', { clipboard })

    const result = await copyToClipboard('s3cret', 0)

    expect(result.copied).toBe(true)
    expect(result.clearScheduled).toBe(false)
  })

  it('reports unavailable when the Clipboard API does not exist (insecure context / unsupported browser)', async () => {
    vi.stubGlobal('navigator', {})

    const result = await copyToClipboard('s3cret', 30_000)

    expect(result.copied).toBe(false)
    expect(result.clearScheduled).toBe(false)
    expect(result.error).toMatch(/unavailable/i)
  })

  it('reports a failure if the initial write is rejected (permission denied)', async () => {
    const clipboard = fakeClipboard(() => Promise.reject(new Error('NotAllowedError')))
    vi.stubGlobal('navigator', { clipboard })

    const result = await copyToClipboard('s3cret', 30_000)

    expect(result.copied).toBe(false)
    expect(result.error).toMatch(/permission/i)
  })

  it('the deferred clear failing (documented focus-loss limitation) never throws or logs the value', async () => {
    const clipboard = fakeClipboard()
    vi.stubGlobal('navigator', { clipboard })
    const consoleSpy = vi.spyOn(console, 'log')
    const consoleErrorSpy = vi.spyOn(console, 'error')

    await copyToClipboard('s3cret', 1_000)
    clipboard.writeText.mockImplementationOnce(() => Promise.reject(new Error('NotAllowedError: no focus')))

    await expect(vi.advanceTimersByTimeAsync(1_000)).resolves.not.toThrow()
    expect(consoleSpy).not.toHaveBeenCalled()
    expect(consoleErrorSpy).not.toHaveBeenCalled()
  })

  it('never logs the copied value anywhere', async () => {
    const clipboard = fakeClipboard()
    vi.stubGlobal('navigator', { clipboard })
    const consoleSpy = vi.spyOn(console, 'log')

    await copyToClipboard('super-secret-value', 30_000)
    await vi.advanceTimersByTimeAsync(30_000)

    for (const call of consoleSpy.mock.calls) {
      expect(call.join(' ')).not.toContain('super-secret-value')
    }
  })
})
