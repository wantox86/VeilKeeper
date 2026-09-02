import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useVaultStore } from '../vault'
import { useAuthStore } from '../auth'
import * as vaultApi from '../../services/vaultApi'
import { ApiError } from '../../services/api'
import { encryptVaultItemPayload } from '../../crypto/vaultItemCrypto'
import { bytesToBase64 } from '../../crypto/base64'
import type { CategoryDto, VaultItemDto } from '../../types/vault'

const VDK = crypto.getRandomValues(new Uint8Array(32))

function activateSession(): void {
  const auth = useAuthStore()
  auth.$patch({ sessionToken: 'session-abc', email: 'user@example.com', vdk: VDK })
}

function category(overrides: Partial<CategoryDto> = {}): CategoryDto {
  return {
    id: 1,
    name: 'Common',
    is_uncategorized: false,
    item_count: 0,
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
    ...overrides,
  }
}

async function encryptedItem(id: number, categoryId: number, title: string): Promise<VaultItemDto> {
  const encrypted = await encryptVaultItemPayload(VDK, {
    title,
    content: [{ type: 'text', label: null, value: 'v' }],
  })
  return {
    id,
    category_id: categoryId,
    encrypted_payload: bytesToBase64(encrypted),
    created_at: '2026-01-01T00:00:00Z',
    updated_at: '2026-01-01T00:00:00Z',
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('useVaultStore', () => {
  it('requireSession throws when there is no active session (no accidental unauthenticated calls)', () => {
    const vault = useVaultStore()
    expect(() => vault.requireSession()).toThrow(/No active vault session/)
  })

  it('fetchCategories populates categories using the session token', async () => {
    activateSession()
    vi.spyOn(vaultApi, 'listCategories').mockResolvedValue([category()])

    const vault = useVaultStore()
    await vault.fetchCategories()

    expect(vault.categories).toHaveLength(1)
    expect(vaultApi.listCategories).toHaveBeenCalledWith('session-abc')
  })

  it('fetchItems decrypts every item with the session VDK', async () => {
    activateSession()
    const raw = await encryptedItem(10, 1, 'My secret note')
    vi.spyOn(vaultApi, 'listVaultItems').mockResolvedValue([raw])

    const vault = useVaultStore()
    await vault.fetchItems(1)

    expect(vault.items).toHaveLength(1)
    expect(vault.items[0].payload.title).toBe('My secret note') // proves real decrypt happened, not just passthrough
  })

  it('createItem encrypts client-side before sending, and the server call never receives plaintext', async () => {
    activateSession()
    const createSpy = vi
      .spyOn(vaultApi, 'createVaultItem')
      .mockImplementation(async (_token, categoryId, payloadB64) => ({
        id: 20,
        category_id: categoryId,
        encrypted_payload: payloadB64,
        created_at: '2026-01-01T00:00:00Z',
        updated_at: '2026-01-01T00:00:00Z',
      }))

    const vault = useVaultStore()
    const result = await vault.createItem(1, {
      title: 'Wi-Fi password',
      content: [{ type: 'secret', label: 'PSK', value: 'hunter2' }],
    })

    expect(result.payload.title).toBe('Wi-Fi password') // round-tripped back through decrypt correctly
    const sentPayload = createSpy.mock.calls[0][2]
    expect(sentPayload).not.toContain('hunter2') // never sent plaintext -- base64 ciphertext only
    expect(sentPayload).not.toContain('Wi-Fi password')
  })

  it('deleteCategory calls the API with reassignTo and refreshes the category list', async () => {
    activateSession()
    vi.spyOn(vaultApi, 'deleteCategory').mockResolvedValue(undefined)
    vi.spyOn(vaultApi, 'listCategories').mockResolvedValue([
      category({ id: 2, name: 'Uncategorized', is_uncategorized: true }),
    ])

    const vault = useVaultStore()
    await vault.deleteCategory(1, 3)

    expect(vaultApi.deleteCategory).toHaveBeenCalledWith('session-abc', 1, 3)
    expect(vault.categories.some((c) => c.is_uncategorized)).toBe(true)
  })

  it('deleteCategory omits reassignTo when not provided (backend defaults to Uncategorized)', async () => {
    activateSession()
    vi.spyOn(vaultApi, 'deleteCategory').mockResolvedValue(undefined)
    vi.spyOn(vaultApi, 'listCategories').mockResolvedValue([])

    const vault = useVaultStore()
    await vault.deleteCategory(1)

    expect(vaultApi.deleteCategory).toHaveBeenCalledWith('session-abc', 1, undefined)
  })

  it("surfaces a clear message on 404 -- the authorization-test shape (another user's item)", async () => {
    activateSession()
    vi.spyOn(vaultApi, 'getVaultItem').mockRejectedValue(
      new ApiError(404, 'not_found', 'vault item not found'),
    )

    const vault = useVaultStore()
    await expect(vault.fetchItem(999)).rejects.toBeInstanceOf(ApiError)
    expect(vault.errorMessage).toBe("This item doesn't exist, or you don't have access to it.")
  })

  it('surfaces the same clear message on a 403 (defense in depth, even though the backend currently only returns 404)', async () => {
    activateSession()
    vi.spyOn(vaultApi, 'getVaultItem').mockRejectedValue(new ApiError(403, 'forbidden', 'forbidden'))

    const vault = useVaultStore()
    await expect(vault.fetchItem(1)).rejects.toBeInstanceOf(ApiError)
    expect(vault.errorMessage).toBe("This item doesn't exist, or you don't have access to it.")
  })

  it('deleteItem removes the item from local state on success', async () => {
    activateSession()
    const raw = await encryptedItem(1, 1, 'To delete')
    vi.spyOn(vaultApi, 'listVaultItems').mockResolvedValue([raw])
    vi.spyOn(vaultApi, 'deleteVaultItem').mockResolvedValue(undefined)

    const vault = useVaultStore()
    await vault.fetchItems()
    expect(vault.items).toHaveLength(1)

    await vault.deleteItem(1)
    expect(vault.items).toHaveLength(0)
  })

  describe('attachments (Web Sprint 6)', () => {
    it('uploadAttachment encrypts bytes+filename client-side and returns the new attachment id -- the server call never receives plaintext', async () => {
      activateSession()
      const uploadSpy = vi.spyOn(vaultApi, 'uploadAttachment').mockImplementation(async (_token, itemId) => ({
        id: 55,
        vault_item_id: itemId,
        encrypted_filename: 'ignored',
        mime_type: 'image/jpeg',
        size: 4,
        created_at: '2026-01-01T00:00:00Z',
      }))

      const vault = useVaultStore()
      const plaintext = new TextEncoder().encode('fake-jpeg-bytes')
      const attachmentId = await vault.uploadAttachment(10, plaintext, 'image/jpeg', 'vpn-screenshot.jpg')

      expect(attachmentId).toBe(55)
      const [, itemId, encryptedFilenameB64, mimeType, encryptedDataB64] = uploadSpy.mock.calls[0]
      expect(itemId).toBe(10)
      expect(mimeType).toBe('image/jpeg')
      // Never the plaintext filename or file bytes -- base64 ciphertext only.
      expect(encryptedFilenameB64).not.toContain('vpn-screenshot')
      expect(atob(encryptedDataB64)).not.toContain('fake-jpeg-bytes')
    })

    it('downloadAttachment decrypts the server response back to the original bytes and filename', async () => {
      activateSession()
      const vault = useVaultStore()

      // Round-trip through the real crypto module to build a realistic
      // server response, then verify downloadAttachment decrypts it back.
      const { encryptFile, encryptFilename } = await import('../../crypto/attachmentCrypto')
      const { bytesToBase64 } = await import('../../crypto/base64')
      const originalBytes = crypto.getRandomValues(new Uint8Array(32))
      const encryptedData = await encryptFile(VDK, originalBytes)
      const encryptedFilename = await encryptFilename(VDK, 'router-admin.png')

      vi.spyOn(vaultApi, 'getAttachment').mockResolvedValue({
        id: 55,
        vault_item_id: 10,
        encrypted_filename: bytesToBase64(encryptedFilename),
        mime_type: 'image/png',
        size: encryptedData.length,
        encrypted_data: bytesToBase64(encryptedData),
        created_at: '2026-01-01T00:00:00Z',
      })

      const result = await vault.downloadAttachment(10, 55)

      expect(result.filename).toBe('router-admin.png')
      expect(result.mimeType).toBe('image/png')
      expect(new Uint8Array(await result.blob.arrayBuffer())).toEqual(originalBytes)
    })

    it('deleteAttachment calls the API with itemId and attachmentId', async () => {
      activateSession()
      const deleteSpy = vi.spyOn(vaultApi, 'deleteAttachment').mockResolvedValue(undefined)

      const vault = useVaultStore()
      await vault.deleteAttachment(10, 55)

      expect(deleteSpy).toHaveBeenCalledWith('session-abc', 10, 55)
    })

    it('surfaces a clear error message and rethrows when upload fails (e.g. session expired mid-flow)', async () => {
      activateSession()
      vi.spyOn(vaultApi, 'uploadAttachment').mockRejectedValue(new ApiError(401, 'unauthorized', 'expired'))

      const vault = useVaultStore()
      await expect(
        vault.uploadAttachment(10, new Uint8Array([1]), 'image/png', 'x.png'),
      ).rejects.toBeInstanceOf(ApiError)
      expect(vault.errorMessage).toBe('Your session has expired. Please log in again.')
    })
  })
})
