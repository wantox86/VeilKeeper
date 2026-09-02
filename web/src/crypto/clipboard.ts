/**
 * Clipboard Security (SPEC-BASE.md Section 29), Web adaptation.
 *
 * **Researched, real limitation of the browser Clipboard API -- documented
 * here rather than assumed away** (this is the ambiguity the task asked to
 * resolve without silently overpromising):
 *
 *  - `navigator.clipboard.writeText()` requires a secure context (HTTPS or
 *    localhost) and, per the W3C Clipboard API spec, that the document have
 *    focus at call time. The *first* write (the actual copy) always happens
 *    synchronously inside a real user gesture (a click handler), so it's
 *    reliable.
 *  - The *auto-clear*, however, fires later from a `setTimeout` -- by the
 *    time it runs, the user may have switched tabs/apps/windows. If the
 *    document has lost focus, `writeText()` rejects (`NotAllowedError`) and
 *    the clipboard is silently left with the copied value. There is no
 *    browser API to force-clear the system clipboard without focus, and no
 *    portable way to detect "the user came back" and retry reliably across
 *    Chromium/Firefox/Safari.
 *  - Unlike Android's `ClipboardSecurity` (which can compare current
 *    clipboard content against what it copied before clearing, skipping the
 *    clear if the user copied something else in the meantime), doing the
 *    same on Web would require `navigator.clipboard.readText()` -- a
 *    *separate*, more sensitive permission that most browsers gate behind
 *    its own prompt/permission-policy and that would mean reading
 *    (possibly unrelated, possibly sensitive) clipboard content just to
 *    decide whether to clear it. That's a worse privacy trade than just
 *    unconditionally overwriting -- skipped deliberately, not an oversight.
 *
 * **Conclusion, and what this module actually promises**: auto-clear is
 * best-effort and reliable *only while the tab stays focused/foreground*
 * for the configured delay. This is disclosed to the user in the Settings
 * UI (see `SettingsView.vue`) and in `web/README.md` -- never presented as
 * a guarantee.
 *
 * Never logs the copied value, the clipboard content, or the error detail
 * anywhere (console, network, storage) -- only a generic boolean/string
 * outcome is ever returned to the caller.
 */
export interface ClipboardCopyResult {
  copied: boolean
  /** True if an auto-clear timer was armed (best-effort, see doc comment above). */
  clearScheduled: boolean
  error?: string
}

function getClipboard(): Clipboard | null {
  if (typeof navigator === 'undefined' || !navigator.clipboard || !navigator.clipboard.writeText) {
    return null
  }
  return navigator.clipboard
}

export async function copyToClipboard(value: string, clearDelayMs: number): Promise<ClipboardCopyResult> {
  const clipboard = getClipboard()
  if (!clipboard) {
    return {
      copied: false,
      clearScheduled: false,
      error: 'Clipboard access is unavailable in this browser (requires HTTPS or localhost).',
    }
  }

  try {
    await clipboard.writeText(value)
  } catch {
    return { copied: false, clearScheduled: false, error: 'Copy failed -- clipboard permission was denied.' }
  }

  let clearScheduled = false
  if (clearDelayMs > 0) {
    clearScheduled = true
    setTimeout(() => {
      // Best-effort only -- see the module doc comment above. Deliberately
      // no logging of the outcome/error here (could hint at clipboard
      // state) and no retry loop (would need clipboard-read permission to
      // detect "did it actually clear," which is out of scope per the same
      // doc comment).
      clipboard.writeText('').catch(() => {
        /* swallowed intentionally */
      })
    }, clearDelayMs)
  }

  return { copied: true, clearScheduled }
}
