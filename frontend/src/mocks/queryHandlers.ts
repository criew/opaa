import { http, HttpResponse } from 'msw'
import { getRandomMockResponse, mockErrorResponse } from './queryFixtures'
import { mockChatDetails, mockChatArchive } from './chatFixtures'
import { mockSpaceAssetAssociations } from './assetFixtures'
import type { QueryRequest } from '../types/api'

/**
 * Mirrors ChatRepository#deriveTitleFromFirstQuestionIfAbsent/#applyGeneratedTitleIfGenerated
 *: if the chat exists and has no title yet, derives one (mock stand-in for the real LLM
 * title) and persists it on the mock chat; an existing title - whether user-set or already
 * derived - is never overwritten. Returns null for a chatId with no matching mock chat (an
 * ephemeral query).
 */
function applyMockChatTitle(chatId: string, question: string): string | null {
  const chat = mockChatDetails[chatId]
  if (!chat) return null
  if (chat.title) return chat.title
  const generated = question.trim().split(/\s+/).slice(0, 6).join(' ')
  chat.title = generated
  chat.updatedAt = new Date().toISOString()
  return generated
}

export const queryHandlers = [
  http.post('/api/v1/query', async ({ request }) => {
    const body = (await request.json()) as QueryRequest
    if (!body.question || body.question.trim() === '') {
      return HttpResponse.json(
        { ...mockErrorResponse, timestamp: new Date().toISOString() },
        { status: 400 },
      )
    }
    const chatId = body.chatId ?? crypto.randomUUID()
    // Mirrors ChatService#appendTurn: a title is only ever derived once - never overwriting
    // one already present, whether that is a CUSTOM title the user set or a title a previous turn
    // already derived.
    const chatTitle = applyMockChatTitle(chatId, body.question)
    // Mirrors ChatService#appendTurn: the person's own message brings the chat back from their
    // chat archive.
    delete mockChatArchive[chatId]
    // Mirrors QueryService: a chat whose space has no knowledge associated searches nothing and
    // says so, whatever its chip bar shows.
    const spaceId = mockChatDetails[chatId]?.spaceId
    if (spaceId && !mockSpaceAssetAssociations[spaceId]?.hasKnowledge) {
      return HttpResponse.json({
        answer: 'Dazu liegt mir in diesem Space kein Wissen vor.',
        sources: [],
        metadata: {
          model: 'gpt-4o',
          tokenCount: 42,
          durationMs: 120,
          answeredWithoutKnowledge: false,
          noKnowledgeAssignedToSpace: true,
          noKnowledgeAvailableInSpace: false,
        },
        chatId,
        chatTitle,
      })
    }
    // Mirrors QueryService: useKnowledge=false with no (or only unreadable) libraryIds
    // performs no retrieval - without this branch, mock/dev mode could never show the "answered
    // without knowledge" hint that  added to the chat UI.
    if (body.useKnowledge === false && (!body.libraryIds || body.libraryIds.length === 0)) {
      return HttpResponse.json({
        answer: 'Dazu liegt mir kein Wissen aus den referenzierten Bibliotheken vor.',
        sources: [],
        metadata: {
          model: 'gpt-4o',
          tokenCount: 42,
          durationMs: 120,
          answeredWithoutKnowledge: true,
        },
        chatId,
        chatTitle,
      })
    }
    const mockResponse = getRandomMockResponse()
    return HttpResponse.json({
      ...mockResponse,
      chatId,
      chatTitle,
      // Mirrors QueryResponse#noteItems (#1487): the note state that went into *this* answer -
      // never one the condensation of this very turn would produce, and null for an ephemeral
      // query that has no persisted chat at all.
      noteItems: mockChatDetails[chatId]?.noteItems ?? null,
    })
  }),
]
