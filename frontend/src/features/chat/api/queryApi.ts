import type { QueryRequest, QueryResponse, MetadataFilter } from '@/shared/api/types'
import { apiClient as client, normalizeError } from '@/shared/api/api'

export async function sendQuery(
  question: string,
  chatId?: string,
  useKnowledge = true,
  libraryIds?: string[],
  metadataFilter?: MetadataFilter | null,
  usedPromptId?: string,
): Promise<QueryResponse> {
  try {
    const request = queryRequest(
      question,
      chatId,
      useKnowledge,
      libraryIds,
      metadataFilter,
      usedPromptId,
    )
    const { data } = await client.post<QueryResponse>('/v1/query', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * The body of a question. libraryIds is only meaningful (and only sent) when useKnowledge is false
 * - the backend ignores it otherwise, so omitting it keeps the request honest about what it does.
 * When chatId names a chat the caller authored, useKnowledge/libraryIds are ignored server-side in
 * favour of the chat's own settings; metadataFilter follows the same rule, so it is only sent for
 * an ephemeral query.
 */
export function queryRequest(
  question: string,
  chatId?: string,
  useKnowledge = true,
  libraryIds?: string[],
  metadataFilter?: MetadataFilter | null,
  usedPromptId?: string,
): QueryRequest {
  return {
    question,
    chatId,
    useKnowledge,
    ...(useKnowledge ? {} : { libraryIds }),
    ...(metadataFilter && !isEmptyMetadataFilter(metadataFilter) ? { metadataFilter } : {}),
    ...(usedPromptId ? { usedPromptId } : {}),
  }
}

/** a filter without any condition - what the backend treats as "no filter". */
export function isEmptyMetadataFilter(filter: MetadataFilter | null | undefined): boolean {
  if (!filter) return true
  return (
    (filter.documentTypes ?? []).length === 0 &&
    !filter.documentDateFrom &&
    !filter.documentDateTo &&
    (filter.libraryFields ?? []).length === 0 &&
    (filter.formatFields ?? []).length === 0
  )
}
