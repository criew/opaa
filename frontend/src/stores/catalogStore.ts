import { create } from 'zustand'
import type { AssetType, CatalogEntryResponse } from '../types/api'
import { getCatalog } from '../services/catalogApi'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

export const CATALOG_PAGE_SIZE = 50
/** The bound the server sets on the search text. */
export const CATALOG_QUERY_MAX_LENGTH = 200

export interface CatalogFilter {
  /** Every type when absent. */
  type?: AssetType
  q: string
}

interface CatalogState {
  entries: CatalogEntryResponse[]
  page: number
  totalPages: number
  totalElements: number
  /** The trimmed search text the shown entries answer; `null` before the first result. */
  loadedQuery: string | null
  isLoading: boolean
  error: string | null
  reset: () => void
  /** Loads the first page for `filter`, replacing what is shown. */
  load: (filter: CatalogFilter) => Promise<void>
  /** Appends the next page of the filter last loaded. */
  loadMore: () => Promise<void>
}

let lastFilter: CatalogFilter = { q: '' }
// Only the answer to the latest request may land; a slower, older one is dropped.
let latestRequest = 0

/**
 * The catalog as the server pages it (docs/features/spaces-and-assets.md#der-katalog). Search and
 * type filter are server parameters, so a changed filter always starts again at the first page.
 */
export const useCatalogStore = create<CatalogState>((set, get) => {
  async function fetchPage(filter: CatalogFilter, page: number) {
    const request = ++latestRequest
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const result = await getCatalog({ ...filter, page, size: CATALOG_PAGE_SIZE })
      if (request !== latestRequest || isStaleSessionEpoch(sessionEpoch)) return
      set({
        entries: page === 0 ? result.entries : [...get().entries, ...result.entries],
        page: result.page,
        totalPages: result.totalPages,
        totalElements: result.totalElements,
        loadedQuery: filter.q.trim(),
        isLoading: false,
      })
    } catch (err) {
      if (request !== latestRequest || isStaleSessionEpoch(sessionEpoch)) return
      set({
        error: err instanceof Error ? err.message : 'Der Katalog konnte nicht geladen werden',
        isLoading: false,
      })
    }
  }

  return {
    entries: [],
    page: 0,
    totalPages: 0,
    totalElements: 0,
    loadedQuery: null,
    isLoading: false,
    error: null,

    reset: () => {
      latestRequest += 1
      lastFilter = { q: '' }
      set({
        entries: [],
        page: 0,
        totalPages: 0,
        totalElements: 0,
        loadedQuery: null,
        isLoading: false,
        error: null,
      })
    },

    load: async (filter) => {
      lastFilter = filter
      await fetchPage(filter, 0)
    },

    loadMore: async () => {
      await fetchPage(lastFilter, get().page + 1)
    },
  }
})
