import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../api'
import * as vaultApi from '../vaultApi'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

const TOKEN = 'session-token-abc'

describe('vaultApi categories', () => {
  it('listCategories sends the bearer token and returns the parsed list', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        jsonResponse(200, [
          { id: 1, name: 'Common', is_uncategorized: false, item_count: 2, created_at: 'a', updated_at: 'a' },
        ]),
      )
    vi.stubGlobal('fetch', fetchMock)

    const result = await vaultApi.listCategories(TOKEN)

    expect(result).toHaveLength(1)
    const [, init] = fetchMock.mock.calls[0]
    expect((init.headers as Record<string, string>).Authorization).toBe(`Bearer ${TOKEN}`)
  })

  it('createCategory posts the name and returns the created category', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(201, {
        id: 5,
        name: 'Travel',
        is_uncategorized: false,
        item_count: 0,
        created_at: 'a',
        updated_at: 'a',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await vaultApi.createCategory(TOKEN, 'Travel')

    expect(result.name).toBe('Travel')
    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(init.body as string)).toEqual({ name: 'Travel' })
  })

  it('deleteCategory without reassignTo omits the query param', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await vaultApi.deleteCategory(TOKEN, 3)

    const [url] = fetchMock.mock.calls[0]
    expect(String(url)).not.toContain('reassign_to')
  })

  it('deleteCategory with reassignTo appends ?reassign_to=<id>', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 204 }))
    vi.stubGlobal('fetch', fetchMock)

    await vaultApi.deleteCategory(TOKEN, 3, 7)

    const [url] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('reassign_to=7')
  })

  it('deleteCategory throws ApiError(system_category) on a 409 (Uncategorized cannot be deleted)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        jsonResponse(409, {
          error: 'system_category',
          message: 'the Uncategorized category cannot be deleted',
        }),
      ),
    )

    await expect(vaultApi.deleteCategory(TOKEN, 1)).rejects.toMatchObject({
      status: 409,
      code: 'system_category',
    })
  })
})

describe('vaultApi vault items', () => {
  it('listVaultItems with a categoryId filters via ?category_id=', async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, []))
    vi.stubGlobal('fetch', fetchMock)

    await vaultApi.listVaultItems(TOKEN, 4)

    const [url] = fetchMock.mock.calls[0]
    expect(String(url)).toContain('category_id=4')
  })

  it('createVaultItem posts category_id and base64 encrypted_payload', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(201, {
        id: 9,
        category_id: 4,
        encrypted_payload: 'Yg==',
        created_at: 'a',
        updated_at: 'a',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const result = await vaultApi.createVaultItem(TOKEN, 4, 'Yg==')

    expect(result.id).toBe(9)
    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(init.body as string)).toEqual({ category_id: 4, encrypted_payload: 'Yg==' })
  })

  it('updateVaultItem omits category_id when not provided (leave unchanged)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(200, {
        id: 9,
        category_id: 4,
        encrypted_payload: 'Yg==',
        created_at: 'a',
        updated_at: 'b',
      }),
    )
    vi.stubGlobal('fetch', fetchMock)

    await vaultApi.updateVaultItem(TOKEN, 9, 'Yg==')

    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(init.body as string)).toEqual({ encrypted_payload: 'Yg==' })
  })

  it('getVaultItem throws ApiError(not_found) on a 404 -- e.g. another user item', async () => {
    // A fresh Response per call -- Response bodies can only be read once,
    // and reusing one across two separate rejects assertions would silently
    // fall back to "unknown_error" on the second call.
    vi.stubGlobal(
      'fetch',
      vi
        .fn()
        .mockImplementation(async () =>
          jsonResponse(404, { error: 'not_found', message: 'vault item not found' }),
        ),
    )

    await expect(vaultApi.getVaultItem(TOKEN, 999)).rejects.toBeInstanceOf(ApiError)
    await expect(vaultApi.getVaultItem(TOKEN, 999)).rejects.toMatchObject({ status: 404, code: 'not_found' })
  })

  it('deleteVaultItem resolves on 204', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 204 })))
    await expect(vaultApi.deleteVaultItem(TOKEN, 1)).resolves.toBeUndefined()
  })
})
