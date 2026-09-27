import axios from 'axios'
import type {
  ChatCreateRequest,
  ChatBulkAction,
  ChatBulkActionResult,
  ChatDetail,
  ChatSummary,
  ChatSummaryPage,
  ChatSearchRequest,
  ChatSearchResponse,
  ChatUpdateRequest,
} from '../types/api'
import { isErrorResponse } from '../types/api'
import { apiClient as client, normalizeError } from './api'

export async function listSpaceChats(spaceId: string): Promise<ChatSummary[]> {
  try {
    const { data } = await client.get<ChatSummary[]>(`/v1/spaces/${spaceId}/chats`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createChat(
  spaceId: string,
  request?: ChatCreateRequest,
): Promise<ChatDetail> {
  try {
    const { data } = await client.post<ChatDetail>(`/v1/spaces/${spaceId}/chats`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getChat(chatId: string): Promise<ChatDetail> {
  try {
    const { data } = await client.get<ChatDetail>(`/v1/chats/${chatId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateChat(chatId: string, request: ChatUpdateRequest): Promise<ChatDetail> {
  try {
    const { data } = await client.patch<ChatDetail>(`/v1/chats/${chatId}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteChat(chatId: string): Promise<void> {
  try {
    await client.delete(`/v1/chats/${chatId}`)
  } catch (err) {
    normalizeError(err)
  }
}

/** Pins the chat for the current person only; the chat itself (and its updatedAt) is unchanged. */
export async function pinChat(chatId: string): Promise<ChatSummary> {
  try {
    const { data } = await client.put<ChatSummary>(`/v1/chats/${chatId}/pin`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function unpinChat(chatId: string): Promise<void> {
  try {
    await client.delete(`/v1/chats/${chatId}/pin`)
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Moves the chat into the current person's chat archive - a personal filing that unpins it and
 * leaves the chat itself unchanged. Not to be confused with an archived space.
 */
export async function archiveChat(chatId: string): Promise<ChatSummary> {
  try {
    const { data } = await client.put<ChatSummary>(`/v1/chats/${chatId}/archive`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Brings the chat back from the current person's chat archive, unpinned. */
export async function unarchiveChat(chatId: string): Promise<ChatSummary> {
  try {
    const { data } = await client.delete<ChatSummary>(`/v1/chats/${chatId}/archive`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** One page of the current person's chat archive in a space, most recently archived first. */
export async function listArchivedSpaceChats(
  spaceId: string,
  page: number,
  size: number,
): Promise<ChatSummaryPage> {
  try {
    const { data } = await client.get<ChatSummaryPage>(`/v1/spaces/${spaceId}/chats/archived`, {
      params: { page, size },
    })
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The chat search refused a request for its rate limit; the wait comes from `Retry-After`. */
export class ChatSearchRateLimitedError extends Error {
  readonly retryAfterSeconds: number | null

  constructor(retryAfterSeconds: number | null) {
    super('TOO_MANY_REQUESTS')
    this.name = 'ChatSearchRateLimitedError'
    this.retryAfterSeconds = retryAfterSeconds
  }
}

const CHAT_SEARCH_FAILED = 'Die Chatsuche ist fehlgeschlagen. Bitte später erneut versuchen.'

/**
 * Full-text search over the person's own chats of a space, archive included. The term travels in
 * the body only, never in the URL. A refusal arrives as the backend's German message, a rate
 * limit as {@link ChatSearchRateLimitedError}, anything else as one generic German message.
 */
export async function searchSpaceChats(
  spaceId: string,
  request: ChatSearchRequest,
  signal?: AbortSignal,
): Promise<ChatSearchResponse> {
  try {
    const { data } = await client.post<ChatSearchResponse>(
      `/v1/spaces/${spaceId}/chats/search`,
      request,
      { signal },
    )
    return data
  } catch (err) {
    if (axios.isCancel(err)) throw err
    if (axios.isAxiosError(err) && err.response?.status === 429) {
      const header = err.response.headers['retry-after']
      const seconds = typeof header === 'string' ? Number.parseInt(header, 10) : Number.NaN
      throw new ChatSearchRateLimitedError(Number.isFinite(seconds) ? seconds : null)
    }
    const data = axios.isAxiosError(err) ? err.response?.data : undefined
    throw new Error(isErrorResponse(data) ? data.error : CHAT_SEARCH_FAILED, { cause: err })
  }
}

/**
 * Applies one action to several of the person's own chats of a space. Ids the server does not
 * count as the person's own are skipped silently; the result names the chats actually affected.
 */
export async function applyChatBulkAction(
  spaceId: string,
  action: ChatBulkAction,
  chatIds: string[],
): Promise<ChatBulkActionResult> {
  try {
    const { data } = await client.post<ChatBulkActionResult>(
      `/v1/spaces/${spaceId}/chats/bulk-actions`,
      { action, chatIds },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Removes one point of a chat's Gesprächsnotiz (#1488). Immediate and without a confirmation step;
 * the point is not blocked for the future - the condensation may create it again if the person
 * states the same thing again (docs/features/conversation-memory.md, "Zustand und Bedienung").
 */
export async function deleteChatNoteItem(chatId: string, itemId: string): Promise<void> {
  try {
    await client.delete(`/v1/chats/${chatId}/note-items/${itemId}`)
  } catch (err) {
    normalizeError(err)
  }
}
