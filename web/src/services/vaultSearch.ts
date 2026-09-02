import type { DecryptedVaultItem } from '../stores/vault'

/**
 * Web Sprint 5 -- Global search, mirrors Android Sprint 4's
 * `data/VaultSearch.kt` field-for-field (title, every content block's label,
 * every content block's value -- covers "text content" and "notes" from
 * SPEC-BASE.md Section 16, since both are just `ContentBlock`s with
 * different `type`s).
 *
 * Pure, client-side matching over already-decrypted `DecryptedVaultItem`s.
 * No network call, no server round-trip, and (CLAUDE.md Resolved Design
 * Decision #4, "in-memory only for the unlocked session" option -- the
 * option Android Sprint 4 already picked) nothing is ever persisted to disk.
 * Callers pass in the same `vault.items` list the Home view already fetched
 * for its own rendering (see `DashboardView.vue`) -- searching is just an
 * in-memory filter over data that was already sitting in memory, not an
 * additional fetch.
 *
 * Tags are deliberately skipped: as of this sprint there is no tag concept
 * anywhere in `VaultItemPayload`/`ContentBlock` (web/src/types/vault.ts) or
 * the backend schema -- same as Android Sprint 4's own documented no-op, see
 * CLAUDE.md.
 *
 * Secret blocks' label AND value are included in matching. This does not
 * create any new plaintext disclosure: the item is already decrypted in
 * memory regardless of search, and matching a query against a secret's
 * value never displays that value anywhere -- the secret still renders
 * hidden-by-default in every view, exactly as before this sprint.
 */
export function matchesQuery(item: DecryptedVaultItem, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  if (item.payload.title.toLowerCase().includes(q)) return true
  return item.payload.content.some(
    (block) => (block.label?.toLowerCase().includes(q) ?? false) || block.value.toLowerCase().includes(q),
  )
}

export function filterItems(items: DecryptedVaultItem[], query: string): DecryptedVaultItem[] {
  const q = query.trim()
  if (!q) return items
  return items.filter((item) => matchesQuery(item, q))
}
