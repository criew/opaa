import type {
  QueryRequest,
  QueryResponse,
  MetadataFilter,
  MetadataFilterOptionsResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

export async function sendQuery(
  question: string,
  chatId?: string,
  useKnowledge = true,
  libraryIds?: string[],
  metadataFilter?: MetadataFilter | null,
  usedPromptId?: string,
): Promise<QueryResponse> {
  try {
    // libraryIds is only meaningful (and only sent) when useKnowledge is false - the backend
    // ignores it otherwise, so omitting it keeps the request honest about what it does.
    // chatId is the persisted-chat/in-memory-cache key; when it names a chat the caller
    // authored, useKnowledge/libraryIds below are ignored server-side in favour of the chat's own
    // settings - the UI itself does not create persisted chats yet, that lands with the UI
    // overhaul, . metadataFilter follows the same rule: a persisted chat's own sticky
    // filter applies, so it is only sent for an ephemeral query.
    const request: QueryRequest = {
      question,
      chatId,
      useKnowledge,
      ...(useKnowledge ? {} : { libraryIds }),
      ...(metadataFilter && !isEmptyMetadataFilter(metadataFilter) ? { metadataFilter } : {}),
      ...(usedPromptId ? { usedPromptId } : {}),
    }
    const { data } = await client.post<QueryResponse>('/v1/query', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** a filter without any condition - what the backend treats as "no filter". */
export function isEmptyMetadataFilter(filter: MetadataFilter | null | undefined): boolean {
  if (!filter) return true
  return (
    (filter.documentTypes ?? []).length === 0 && !filter.documentDateFrom && !filter.documentDateTo
  )
}

/**
 * the Füllstand and the offered values of the filterable core fields in the caller's
 * search scope, resolved with the same rules the query itself applies (chatId first, otherwise
 * useKnowledge/libraryIds).
 */
export async function getMetadataFilterOptions(params: {
  chatId?: string | null
  useKnowledge: boolean
  libraryIds?: string[]
}): Promise<MetadataFilterOptionsResponse> {
  try {
    const searchParams = new URLSearchParams()
    if (params.chatId) searchParams.set('chatId', params.chatId)
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
