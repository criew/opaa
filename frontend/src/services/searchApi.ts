import type { MetadataFilterOptionsResponse } from '../types/api'
import { apiClient as client, normalizeError } from './api'

/**
 * the Füllstand and the offered values of the filterable core fields in the caller's
 * search scope, resolved with the same rules the query itself applies: chatId first, otherwise
 * the space of the chat not yet created with useKnowledge/libraryIds, otherwise nothing.
 */
export async function getMetadataFilterOptions(params: {
  chatId?: string | null
  spaceId?: string | null
  useKnowledge: boolean
  libraryIds?: string[]
}): Promise<MetadataFilterOptionsResponse> {
  try {
    const searchParams = new URLSearchParams()
    if (params.chatId) searchParams.set('chatId', params.chatId)
    if (params.spaceId) searchParams.set('spaceId', params.spaceId)
    searchParams.set('useKnowledge', String(params.useKnowledge))
    for (const libraryId of params.libraryIds ?? []) {
      searchParams.append('libraryIds', libraryId)
    }
    const { data } = await client.get<MetadataFilterOptionsResponse>(
      `/v1/search/metadata-filter-options?${searchParams.toString()}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
