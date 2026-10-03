import { create } from 'zustand'
import type { AssetType, CatalogEntryResponse, CatalogPageResponse } from '../types/api'
import { getCatalog } from '../services/catalogApi'
import { appendNewEntries, pagesForMore } from '../services/catalogPaging'
import { markAssetFavorite, unmarkAssetFavorite } from '../services/assetApi'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

export const CATALOG_PAGE_SIZE = 50
/** The bound the server sets on the search text. */
export const CATALOG_QUERY_MAX_LENGTH = 200

export interface CatalogFilter {
  /** Every type when absent. */
  type?: AssetType
  q: string
  /** Only the caller's own favorites. */
  favorites?: boolean
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
// A favorite toggled since the last load; see pagesForMore.
let reorderedSinceLoad = false

/**
 * The catalog as the server pages it (docs/features/spaces-and-assets.md#der-katalog). Search and
 * type filter are server parameters, so a changed filter always starts again at the first page.
 */
export const useCatalogStore = create<CatalogState>((set, get) => {
  /**
   * Fetches the pages `from` to `through` in order. From page 0 they replace what is shown,
   * otherwise they are appended; an entry is never shown twice.
   */
  async function fetchPages(filter: CatalogFilter, from: number, through: number) {
    const request = ++latestRequest
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      let entries = from === 0 ? [] : get().entries
      let result: CatalogPageResponse | undefined
      for (let page = from; page <= through; page++) {
        result = await getCatalog({ ...filter, page, size: CATALOG_PAGE_SIZE })
        if (request !== latestRequest || isStaleSessionEpoch(sessionEpoch)) return
        entries = appendNewEntries(entries, result.entries)
        if (page + 1 >= result.totalPages) break
      }
      if (!result) return
      set({
        entries,
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
      reorderedSinceLoad = false
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
      reorderedSinceLoad = false
      await fetchPages(filter, 0, 0)
    },

    loadMore: async () => {
      const { from, through } = pagesForMore(get().page, reorderedSinceLoad)
      reorderedSinceLoad = false
      await fetchPages(lastFilter, from, through)
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
        reorderedSinceLoad = true
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
