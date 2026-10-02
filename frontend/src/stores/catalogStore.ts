import { create } from 'zustand'
import type { AssetType, CatalogEntryResponse, CatalogVisibility } from '../types/api'
import { getCatalog, type CatalogSort } from '../services/catalogApi'
import { markAssetFavorite, unmarkAssetFavorite } from '../services/assetApi'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

export const CATALOG_PAGE_SIZE = 50
/** The bound the server sets on the search text. */
export const CATALOG_QUERY_MAX_LENGTH = 200

export interface CatalogFilter {
  /** Every type when absent. */
  type?: AssetType
  q: string
  visibility?: CatalogVisibility
  fromMyGroups?: boolean
  /** Only the caller's own favorites. */
  favorites?: boolean
  sort?: CatalogSort
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
  /**
   * Marks or unmarks one entry as the caller's favorite. The entry keeps its place until the next
   * load, so a tile does not jump away under the pointer; a failure is reported in `error`.
   */
  setFavorite: (entry: CatalogEntryResponse, favorite: boolean) => Promise<void>
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

    setFavorite: async (entry, favorite) => {
      const sessionEpoch = currentSessionEpoch()
      try {
        if (favorite) {
          await markAssetFavorite(entry.assetType, entry.assetId)
        } else {
          await unmarkAssetFavorite(entry.assetType, entry.assetId)
        }
        if (isStaleSessionEpoch(sessionEpoch)) return
        set({
          error: null,
          entries: get().entries.map((candidate) =>
            candidate.assetType === entry.assetType && candidate.assetId === entry.assetId
              ? { ...candidate, favorite }
              : candidate,
          ),
        })
      } catch (err) {
        if (isStaleSessionEpoch(sessionEpoch)) return
        set({
          error: err instanceof Error ? err.message : 'Der Favorit konnte nicht gespeichert werden',
        })
      }
    },
  }
})
