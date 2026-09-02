/**
 * Web Sprint 4 (Secure UX) -- shared option lists for auto-lock timeout and
 * clipboard auto-clear delay. Auto-lock options are the same four the task
 * asked for, matching Android's Settings screen for cross-platform UX
 * consistency (SPEC-BASE.md Section 30's own example list). Clipboard delay
 * options mirror Android Sprint 3's own choices (15/30/60s).
 */

export type AutoLockTimeoutId = 'immediately' | '1m' | '5m' | '15m'

export interface AutoLockTimeoutOption {
  id: AutoLockTimeoutId
  label: string
  /** 0 means "lock as soon as the tab is backgrounded/hidden", not "lock instantly while visible." */
  ms: number
}

export const AUTO_LOCK_TIMEOUT_OPTIONS: AutoLockTimeoutOption[] = [
  { id: 'immediately', label: 'Immediately', ms: 0 },
  { id: '1m', label: '1 minute', ms: 60_000 },
  { id: '5m', label: '5 minutes', ms: 5 * 60_000 },
  { id: '15m', label: '15 minutes', ms: 15 * 60_000 },
]

/**
 * Default: 5 minutes, deliberately NOT "Immediately" despite Android's own
 * default now being Immediate (see CLAUDE.md "Post-launch fixes batch 2",
 * item 4). That change was driven by an Android-specific process-kill bug
 * that doesn't apply here. On Web, "Immediately" fires on every
 * `visibilitychange` to hidden -- which happens far more often for benign
 * reasons (alt-tab to a password manager, opening devtools, switching to
 * paste a copied value) than Android's "app backgrounded" signal. Defaulting
 * to Immediately here would make the app annoying to use out of the box;
 * 5 minutes is offered as a safer-but-usable default and is still fully
 * user-configurable down to Immediately.
 */
export const DEFAULT_AUTO_LOCK_TIMEOUT_ID: AutoLockTimeoutId = '5m'

export type ClipboardClearDelayId = '15s' | '30s' | '60s'

export interface ClipboardClearDelayOption {
  id: ClipboardClearDelayId
  label: string
  ms: number
}

export const CLIPBOARD_CLEAR_DELAY_OPTIONS: ClipboardClearDelayOption[] = [
  { id: '15s', label: '15 seconds', ms: 15_000 },
  { id: '30s', label: '30 seconds', ms: 30_000 },
  { id: '60s', label: '60 seconds', ms: 60_000 },
]

export const DEFAULT_CLIPBOARD_CLEAR_DELAY_ID: ClipboardClearDelayId = '30s'

export function findAutoLockOption(id: AutoLockTimeoutId): AutoLockTimeoutOption {
  return AUTO_LOCK_TIMEOUT_OPTIONS.find((o) => o.id === id) ?? AUTO_LOCK_TIMEOUT_OPTIONS[2]
}

export function findClipboardDelayOption(id: ClipboardClearDelayId): ClipboardClearDelayOption {
  return CLIPBOARD_CLEAR_DELAY_OPTIONS.find((o) => o.id === id) ?? CLIPBOARD_CLEAR_DELAY_OPTIONS[1]
}
