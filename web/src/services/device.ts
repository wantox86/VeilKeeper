const DEVICE_ID_KEY = 'vk_device_id'

/**
 * Returns a stable, per-browser random identifier used as the backend's
 * `device_identifier` on login. This is NOT secret (just a random UUID,
 * carries no key material or credential), so persisting it in localStorage
 * is fine -- unlike the session token / VaultDataKey, which are deliberately
 * kept in-memory-only in the Pinia auth store (see `stores/auth.ts`) and
 * never touch localStorage.
 *
 * Without this, every login would mint a brand new "device" row
 * server-side (see `store.UpsertDevice`) instead of reusing the same one
 * for repeat logins from the same browser.
 */
export function getOrCreateDeviceId(): string {
  let id = localStorage.getItem(DEVICE_ID_KEY)
  if (!id) {
    id = crypto.randomUUID()
    localStorage.setItem(DEVICE_ID_KEY, id)
  }
  return id
}

export const WEB_DEVICE_NAME = 'VeilKeeper Web'
