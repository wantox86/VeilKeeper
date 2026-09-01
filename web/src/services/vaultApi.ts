import { apiFetch, parseJsonOrThrow } from './api'
import type { CategoryDto, VaultItemDto } from '../types/vault'

/**
 * Thin HTTP wrappers over `/api/v1/categories` and `/api/v1/vault/items` --
 * same split as `authApi.ts`: this layer only knows about HTTP/JSON, all
 * crypto orchestration (encrypting/decrypting `encrypted_payload`) happens
 * one level up in `stores/vault.ts`. Every call requires a bearer session
 * token (unlike `authApi.ts`'s prelogin/register/login, which are
 * unauthenticated).
 */
function authHeaders(sessionToken: string): HeadersInit {
  return { Authorization: `Bearer ${sessionToken}` }
}

function jsonAuthHeaders(sessionToken: string): HeadersInit {
  return { ...authHeaders(sessionToken), 'Content-Type': 'application/json' }
}

// --- categories ------------------------------------------------------------

export async function listCategories(sessionToken: string): Promise<CategoryDto[]> {
  const response = await apiFetch('api/v1/categories', { headers: authHeaders(sessionToken) })
  return parseJsonOrThrow<CategoryDto[]>(response)
}

export async function createCategory(sessionToken: string, name: string): Promise<CategoryDto> {
  const response = await apiFetch('api/v1/categories', {
    method: 'POST',
    headers: jsonAuthHeaders(sessionToken),
    body: JSON.stringify({ name }),
  })
  return parseJsonOrThrow<CategoryDto>(response)
}

export async function renameCategory(sessionToken: string, id: number, name: string): Promise<CategoryDto> {
  const response = await apiFetch(`api/v1/categories/${id}`, {
    method: 'PUT',
    headers: jsonAuthHeaders(sessionToken),
    body: JSON.stringify({ name }),
  })
  return parseJsonOrThrow<CategoryDto>(response)
}

/**
 * `reassignTo` mirrors the backend's `?reassign_to=<category_id>` query
 * param (CLAUDE.md Resolved Design Decision #5): omitted means "move items
 * to the lazily-created Uncategorized category" (the backend's default),
 * provided means "move items to this specific category instead."
 */
export async function deleteCategory(sessionToken: string, id: number, reassignTo?: number): Promise<void> {
  const query = reassignTo !== undefined ? `?reassign_to=${reassignTo}` : ''
  const response = await apiFetch(`api/v1/categories/${id}${query}`, {
    method: 'DELETE',
    headers: authHeaders(sessionToken),
  })
  if (!response.ok) {
    await parseJsonOrThrow(response)
  }
}

// --- vault items -------------------------------------------------------------

export async function listVaultItems(sessionToken: string, categoryId?: number): Promise<VaultItemDto[]> {
  const query = categoryId !== undefined ? `?category_id=${categoryId}` : ''
  const response = await apiFetch(`api/v1/vault/items${query}`, { headers: authHeaders(sessionToken) })
  return parseJsonOrThrow<VaultItemDto[]>(response)
}

export async function getVaultItem(sessionToken: string, id: number): Promise<VaultItemDto> {
  const response = await apiFetch(`api/v1/vault/items/${id}`, { headers: authHeaders(sessionToken) })
  return parseJsonOrThrow<VaultItemDto>(response)
}

export async function createVaultItem(
  sessionToken: string,
  categoryId: number,
  encryptedPayloadBase64: string,
): Promise<VaultItemDto> {
  const response = await apiFetch('api/v1/vault/items', {
    method: 'POST',
    headers: jsonAuthHeaders(sessionToken),
    body: JSON.stringify({ category_id: categoryId, encrypted_payload: encryptedPayloadBase64 }),
  })
  return parseJsonOrThrow<VaultItemDto>(response)
}

/** `categoryId` omitted leaves the item's category unchanged (matches the backend's `category_id: null = unchanged` contract). */
export async function updateVaultItem(
  sessionToken: string,
  id: number,
  encryptedPayloadBase64: string,
  categoryId?: number,
): Promise<VaultItemDto> {
  const body: { category_id?: number; encrypted_payload: string } = {
    encrypted_payload: encryptedPayloadBase64,
  }
  if (categoryId !== undefined) {
    body.category_id = categoryId
  }
  const response = await apiFetch(`api/v1/vault/items/${id}`, {
    method: 'PUT',
    headers: jsonAuthHeaders(sessionToken),
    body: JSON.stringify(body),
  })
  return parseJsonOrThrow<VaultItemDto>(response)
}

export async function deleteVaultItem(sessionToken: string, id: number): Promise<void> {
  const response = await apiFetch(`api/v1/vault/items/${id}`, {
    method: 'DELETE',
    headers: authHeaders(sessionToken),
  })
  if (!response.ok) {
    await parseJsonOrThrow(response)
  }
}
