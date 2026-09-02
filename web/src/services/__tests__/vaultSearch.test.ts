import { describe, expect, it } from 'vitest'
import { matchesQuery, filterItems } from '../vaultSearch'
import type { DecryptedVaultItem } from '../../stores/vault'

function item(overrides: Partial<DecryptedVaultItem>): DecryptedVaultItem {
  return {
    id: 1,
    categoryId: 1,
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    payload: { title: 'Untitled', content: [] },
    ...overrides,
  }
}

describe('vaultSearch', () => {
  describe('matchesQuery', () => {
    it('matches on title, case-insensitively', () => {
      const it1 = item({ payload: { title: 'Home Router Admin', content: [] } })
      expect(matchesQuery(it1, 'router')).toBe(true)
      expect(matchesQuery(it1, 'ROUTER')).toBe(true)
      expect(matchesQuery(it1, 'nope')).toBe(false)
    })

    it('matches on a content block label', () => {
      const it1 = item({
        payload: {
          title: 'Bank Account',
          content: [{ type: 'text', label: 'Account Number', value: '1234' }],
        },
      })
      expect(matchesQuery(it1, 'account number')).toBe(true)
    })

    it('matches on a content block value (notes/text)', () => {
      const it1 = item({
        payload: {
          title: 'Wifi',
          content: [{ type: 'note', label: null, value: 'Guest network password is on the fridge' }],
        },
      })
      expect(matchesQuery(it1, 'fridge')).toBe(true)
    })

    it('matches on a secret block label and value -- matching does not display the secret', () => {
      const it1 = item({
        payload: {
          title: 'Email',
          content: [{ type: 'secret', label: 'Recovery code', value: 'sup3rSecr3t' }],
        },
      })
      expect(matchesQuery(it1, 'recovery code')).toBe(true)
      expect(matchesQuery(it1, 'sup3rsecr3t')).toBe(true)
    })

    it('handles a null label without throwing', () => {
      const it1 = item({
        payload: { title: 'X', content: [{ type: 'text', label: null, value: 'value here' }] },
      })
      expect(() => matchesQuery(it1, 'anything')).not.toThrow()
      expect(matchesQuery(it1, 'value here')).toBe(true)
    })

    it('an empty or whitespace-only query matches everything', () => {
      const it1 = item({ payload: { title: 'Anything', content: [] } })
      expect(matchesQuery(it1, '')).toBe(true)
      expect(matchesQuery(it1, '   ')).toBe(true)
    })

    it('does not match on a substring that only exists across title+value boundary', () => {
      const it1 = item({
        payload: { title: 'Foo', content: [{ type: 'text', label: null, value: 'Bar' }] },
      })
      expect(matchesQuery(it1, 'oobar')).toBe(false)
    })
  })

  describe('filterItems', () => {
    it('returns all items for a blank query', () => {
      const items = [item({ id: 1 }), item({ id: 2 })]
      expect(filterItems(items, '')).toEqual(items)
    })

    it('filters to only items matching title, label, or value', () => {
      const routerItem = item({ id: 1, payload: { title: 'Router Admin', content: [] } })
      const emailItem = item({
        id: 2,
        payload: {
          title: 'Email',
          content: [{ type: 'secret', label: 'App password', value: 'xyz' }],
        },
      })
      const noteItem = item({
        id: 3,
        payload: { title: 'Misc', content: [{ type: 'note', label: null, value: 'router reset steps' }] },
      })
      const items = [routerItem, emailItem, noteItem]

      expect(filterItems(items, 'router')).toEqual([routerItem, noteItem])
      expect(filterItems(items, 'app password')).toEqual([emailItem])
      expect(filterItems(items, 'nonexistent')).toEqual([])
    })
  })
})
