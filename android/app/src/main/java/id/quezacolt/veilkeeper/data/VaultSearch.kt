package id.quezacolt.veilkeeper.data

/**
 * Global search (SPEC-BASE.md Section 16 / Phase 4).
 *
 * Pure, client-side matching over already-decrypted [DecryptedVaultItem]s --
 * title, every content block's label, and every content block's value
 * (covers both "text content" and "notes" from Section 16's list, since
 * both are just [id.quezacolt.veilkeeper.crypto.ContentBlockDto]s with
 * different `type`s). No network call, no server round-trip: the items
 * passed in here are already the fully-decrypted list the caller fetched
 * for its own screen (see [id.quezacolt.veilkeeper.ui.home.HomeViewModel]),
 * so searching is just an in-memory filter over data that was already
 * sitting in memory for the unlocked session -- no additional fetch, and
 * nothing is ever persisted to disk (CLAUDE.md Resolved Design Decision #4,
 * "in-memory only for the unlocked session" option).
 *
 * Tags are explicitly skipped: as of Sprint 3 there is no tag concept
 * anywhere in [id.quezacolt.veilkeeper.crypto.VaultItemPayload] or the
 * backend schema, so "Tags if implemented" (Section 16) is a documented
 * no-op here, not an oversight -- see CLAUDE.md Sprint 4 entry.
 *
 * Secret blocks' label AND value are included in matching. This does not
 * create any new plaintext disclosure: the item is already decrypted in
 * memory regardless of search, and matching a query against a secret's
 * value never displays that value anywhere -- the secret still renders
 * hidden-by-default in every screen, exactly as it did before Sprint 4.
 */
object VaultSearch {

    fun matches(item: DecryptedVaultItem, query: String): Boolean {
        val q = query.trim()
        if (q.isBlank()) return true
        if (item.title.contains(q, ignoreCase = true)) return true
        return item.content.any { block ->
            (block.label?.contains(q, ignoreCase = true) == true) ||
                block.value.contains(q, ignoreCase = true)
        }
    }

    fun filter(items: List<DecryptedVaultItem>, query: String): List<DecryptedVaultItem> =
        if (query.isBlank()) items else items.filter { matches(it, query) }
}
