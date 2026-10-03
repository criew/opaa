import { renderHook, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CatalogEntryResponse, CatalogPageResponse } from '../../types/api'
import { useAssetCatalogEntry } from './useAssetCatalogEntry'

const { mockGetCatalog } = vi.hoisted(() => ({
  mockGetCatalog: vi.fn<(query: { ids?: string[] }) => Promise<CatalogPageResponse>>(),
}))

vi.mock('../../services/catalogApi', () => ({ getCatalog: mockGetCatalog }))

function entryOf(assetId: string, favorite: boolean): CatalogEntryResponse {
  return {
    assetType: 'PROMPT_LIBRARY',
    assetId,
    name: assetId,
    ownerType: 'USER',
    ownerId: 'owner-1',
    origin: 'LOCAL',
    visibility: 'RESTRICTED',
    myRole: 'VIEWER',
    status: 'READY',
    updatedAt: '2026-10-03T10:00:00Z',
    itemCount: 1,
    spaceCount: 2,
    favorite,
  }
}

function pageOf(entry: CatalogEntryResponse): CatalogPageResponse {
  return { entries: [entry], page: 0, size: 1, totalElements: 1, totalPages: 1 }
}

describe('useAssetCatalogEntry', () => {
  beforeEach(() => {
    mockGetCatalog.mockReset()
  })

  it('forgets the previous asset’s entry while the next one is loading', async () => {
    let answerSecond: (page: CatalogPageResponse) => void = () => {}
    mockGetCatalog.mockImplementation(({ ids }) =>
      ids?.[0] === 'first'
        ? Promise.resolve(pageOf(entryOf('first', true)))
        : new Promise((resolve) => {
            answerSecond = resolve
          }),
    )
    const { result, rerender } = renderHook(
      ({ id }) => useAssetCatalogEntry('PROMPT_LIBRARY', id),
      { initialProps: { id: 'first' } },
    )
    await waitFor(() => expect(result.current.entry?.favorite).toBe(true))

    rerender({ id: 'second' })
    expect(result.current.entry).toBeUndefined()

    answerSecond(pageOf(entryOf('second', false)))
    await waitFor(() => expect(result.current.entry?.assetId).toBe('second'))
    expect(result.current.entry?.favorite).toBe(false)
  })
})
