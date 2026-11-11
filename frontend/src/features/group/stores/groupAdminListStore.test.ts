import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GroupListQuery } from '../api/groupAdminApi'
import { useGroupAdminListStore } from './groupAdminListStore'

const api = vi.hoisted(() => ({
  names: ['Bauamt', 'Bauhof', 'Kasse'],
  countFails: false,
  countRequests: 0,
}))

vi.mock('../api/groupAdminApi', async () => {
  const actual =
    await vi.importActual<typeof import('../api/groupAdminApi')>('../api/groupAdminApi')
  return {
    ...actual,
    listGroupPage: vi.fn(async (query: GroupListQuery) => {
      if (query.size === 1) {
        api.countRequests += 1
        if (api.countFails) throw new Error('boom')
      }
      const items = api.names.filter((name) => name.startsWith(query.query ?? ''))
      return { items: [], total: items.length, page: 0, size: query.size ?? 25 }
    }),
  }
})

vi.mock('@/features/succession', async () => {
  const actual =
    await vi.importActual<typeof import('@/features/succession')>('@/features/succession')
  return { ...actual, getGroupEffects: vi.fn(async () => []) }
})

function state() {
  return useGroupAdminListStore.getState()
}

describe('groupAdminListStore: count beside the title', () => {
  beforeEach(() => {
    state().reset()
    api.countFails = false
    api.countRequests = 0
  })

  it('asks for the unfiltered total once while the filters change', async () => {
    await state().loadGroups()
    await state().setFilters({ query: 'Ba' })
    await state().setFilters({ query: 'Bau' })

    expect(api.countRequests).toBe(0)
    expect(state().total).toBe(2)
    expect(state().overallTotal).toBe(3)
  })

  it('counts again when a filtered list is reloaded, as after a write', async () => {
    await state().setFilters({ query: 'Ba' })
    expect(api.countRequests).toBe(1)

    api.names.push('Archiv')
    await state().loadGroups()
    api.names.pop()

    expect(api.countRequests).toBe(2)
    expect(state().overallTotal).toBe(4)
  })

  it('falls back to the filtered total when the count fails, and counts anew afterwards', async () => {
    api.countFails = true
    await state().setFilters({ query: 'Ba' })

    expect(state().error).toBeNull()
    expect(state().total).toBe(2)
    expect(state().overallTotal).toBe(2)

    // a figure that is only the fallback is not reused for the next filter
    api.countFails = false
    await state().setFilters({ query: 'Bau' })
    expect(api.countRequests).toBe(2)
    expect(state().overallTotal).toBe(3)
  })
})
