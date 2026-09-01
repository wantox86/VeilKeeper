import { defineStore } from 'pinia'
import { useAuthStore } from './auth'
import * as vaultApi from '../services/vaultApi'
import { ApiError } from '../services/api'
import { encryptVaultItemPayload, decryptVaultItemPayload } from '../crypto/vaultItemCrypto'
import { bytesToBase64, base64ToBytes } from '../crypto/base64'
import type { CategoryDto, VaultItemDto, VaultItemPayload } from '../types/vault'

/** A vault item with its payload already decrypted client-side -- the only shape views ever see. */
export interface DecryptedVaultItem {
  id: number
  categoryId: number
  payload: VaultItemPayload
  createdAt: string
  updatedAt: string
}

export interface VaultState {
  categories: CategoryDto[]
  /** Currently loaded item list -- scope (all items vs. one category) is whatever the last `fetchItems()` call asked for. */
  items: DecryptedVaultItem[]
  status: 'idle' | 'loading' | 'error'
  errorMessage: string | null
}

/**
 * Vault state (categories + items), following CLAUDE.md's Web Sprint 3
 * scope: Pinia store used for the shared state (categories list, current
 * item list) that multiple views (Home, Category detail, Item detail/form)
 * all read/mutate -- deliberately NOT split into separate
 * categories/items stores, that would be overengineering for what's a
 * small, closely-related state shape (SPEC-BASE.md Section 56 Rule 1).
 *
 * Every action requires an active session (`useAuthStore().sessionToken` +
 * `.vdk`) -- this store never fetches or decrypts anything on its own; it's
 * only usable from behind the router's `requiresAuth` guard.
 */
export const useVaultStore = defineStore('vault', {
  state: (): VaultState => ({
    categories: [],
    items: [],
    status: 'idle',
    errorMessage: null,
  }),

  getters: {
    uncategorizedCategory: (state): CategoryDto | null =>
      state.categories.find((c) => c.is_uncategorized) ?? null,
  },

  actions: {
    /** Throws synchronously if there's no active session -- every other action calls this first. */
    requireSession(): { token: string; vdk: Uint8Array } {
      const auth = useAuthStore()
      if (!auth.sessionToken || !auth.vdk) {
        throw new Error('No active vault session -- log in first.')
      }
      return { token: auth.sessionToken, vdk: auth.vdk }
    },

    async fetchCategories(): Promise<void> {
      const { token } = this.requireSession()
      this.status = 'loading'
      this.errorMessage = null
      try {
        this.categories = await vaultApi.listCategories(token)
        this.status = 'idle'
      } catch (err) {
        this.status = 'error'
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },

    async createCategory(name: string): Promise<CategoryDto> {
      const { token } = this.requireSession()
      this.errorMessage = null
      try {
        const category = await vaultApi.createCategory(token, name)
        this.categories.push(category)
        return category
      } catch (err) {
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },

    async renameCategory(id: number, name: string): Promise<void> {
      const { token } = this.requireSession()
      this.errorMessage = null
      try {
        const category = await vaultApi.renameCategory(token, id, name)
        const idx = this.categories.findIndex((c) => c.id === id)
        if (idx !== -1) {
          this.categories[idx] = category
        }
      } catch (err) {
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },

    /**
     * Deletes a category, following CLAUDE.md Resolved Design Decision #5
     * exactly (same behavior as Android, no Web-specific deviation): items
     * are never silently deleted. If `reassignTo` is omitted, the backend
     * moves them into the lazily-created "Uncategorized" category. Reloads
     * the category list afterward -- item_count changes on (at least) the
     * deleted-from and the reassigned-to category, and Uncategorized may
     * newly appear in the list for the first time.
     */
    async deleteCategory(id: number, reassignTo?: number): Promise<void> {
      const { token } = this.requireSession()
      this.errorMessage = null
      try {
        await vaultApi.deleteCategory(token, id, reassignTo)
        await this.fetchCategories()
      } catch (err) {
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },

    async fetchItems(categoryId?: number): Promise<void> {
      const { token, vdk } = this.requireSession()
      this.status = 'loading'
      this.errorMessage = null
      try {
        const raw = await vaultApi.listVaultItems(token, categoryId)
        this.items = await Promise.all(raw.map((it) => decryptItem(it, vdk)))
        this.status = 'idle'
      } catch (err) {
        this.status = 'error'
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },

    async fetchItem(id: number): Promise<DecryptedVaultItem> {
      const { token, vdk } = this.requireSession()
      this.errorMessage = null
      try {
        const raw = await vaultApi.getVaultItem(token, id)
        return await decryptItem(raw, vdk)
      } catch (err) {
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },

    async createItem(categoryId: number, payload: VaultItemPayload): Promise<DecryptedVaultItem> {
      const { token, vdk } = this.requireSession()
      this.errorMessage = null
      try {
        const encrypted = await encryptVaultItemPayload(vdk, payload)
        const raw = await vaultApi.createVaultItem(token, categoryId, bytesToBase64(encrypted))
        return await decryptItem(raw, vdk)
      } catch (err) {
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },

    async updateItem(
      id: number,
      payload: VaultItemPayload,
      categoryId?: number,
    ): Promise<DecryptedVaultItem> {
      const { token, vdk } = this.requireSession()
      this.errorMessage = null
      try {
        const encrypted = await encryptVaultItemPayload(vdk, payload)
        const raw = await vaultApi.updateVaultItem(token, id, bytesToBase64(encrypted), categoryId)
        return await decryptItem(raw, vdk)
      } catch (err) {
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },

    async deleteItem(id: number): Promise<void> {
      const { token } = this.requireSession()
      this.errorMessage = null
      try {
        await vaultApi.deleteVaultItem(token, id)
        this.items = this.items.filter((it) => it.id !== id)
      } catch (err) {
        this.errorMessage = describeVaultError(err)
        throw err
      }
    },
  },
})

async function decryptItem(raw: VaultItemDto, vdk: Uint8Array): Promise<DecryptedVaultItem> {
  const payload = await decryptVaultItemPayload(vdk, base64ToBytes(raw.encrypted_payload))
  return {
    id: raw.id,
    categoryId: raw.category_id,
    payload,
    createdAt: raw.created_at,
    updatedAt: raw.updated_at,
  }
}

/**
 * Maps backend errors to user-facing messages. 403/404 are handled
 * identically and deliberately vaguely ("doesn't exist or you don't have
 * access") -- the backend enforces ownership by scoping every store query to
 * the authenticated user's own rows, so a cross-user access attempt comes
 * back as a plain 404 (not found), never a 403 (see
 * `backend/internal/httpserver/vault_handlers.go` -- `store.ErrNotFound` is
 * the only sentinel these handlers ever check). This is exactly the
 * "authorization test" CLAUDE.md's Sprint 3 task calls out: this client
 * must show a clear message on that response, not crash or render an empty
 * page silently.
 */
function describeVaultError(err: unknown): string {
  if (err instanceof ApiError) {
    switch (err.status) {
      case 401:
        return 'Your session has expired. Please log in again.'
      case 403:
      case 404:
        return "This item doesn't exist, or you don't have access to it."
      default:
        return err.message
    }
  }
  return err instanceof Error ? err.message : 'An unexpected error occurred.'
}
