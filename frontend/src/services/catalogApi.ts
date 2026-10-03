import type { AssetType, CatalogPageResponse } from '../types/api'
import { apiClient, normalizeError } from './api'

export interface CatalogQuery {
  /** Only this asset type; every type when absent. */
  type?: AssetType
  /** Part of the name or the description; the server matches it literally. */
  q?: string
  /** Only the caller's own favorites. */
  favorites?: boolean
  page: number
  size: number
}

/**
 * The catalog across every asset type (docs/features/spaces-and-assets.md#der-katalog): what the
 * caller may read, favorites first, then by name. Search, filter and paging run on the server.
 */
export async function getCatalog(query: CatalogQuery): Promise<CatalogPageResponse> {
  try {
    const { data } = await apiClient.get<CatalogPageResponse>('/v1/catalog', {
      params: {
        type: query.type,
        q: query.q?.trim() ? query.q.trim() : undefined,
        favorites: query.favorites ? true : undefined,
        page: query.page,
        size: query.size,
      },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}
