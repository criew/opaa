import { getResponse, http, HttpResponse, type RequestHandler } from 'msw'
import { getRandomMockResponse, mockErrorResponse } from './queryFixtures'
import { mockChatDetails, mockChatArchive } from './chatFixtures'
import { mockSpaceAssetAssociations } from '../../asset/mocks/assetFixtures'
import type { QueryRequest } from '@/shared/api/types'

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

/**
 * Whether the mock stream sends the answer word by word before its final event. On in the browser;
 * the test setup turns it off, so that a test finding the answer text finds the final message, not
 * a provisional one that is replaced a moment later. A test of the provisional view turns it on.
 */
let mockAnswerDeltas = true

export function setMockAnswerDeltas(enabled: boolean): void {
  mockAnswerDeltas = enabled
}

/**
 * Where the mock stream finds the answer of POST /api/v1/query: by default a request over the
 * network, which the service worker answers in the browser. The test server answers it in-process
 * from its current handlers instead - a request sent from inside a handler is not reliably
 * intercepted under Node and may reach the real network.
 */
let currentHandlers: (() => RequestHandler[]) | null = null

export function answerQueriesFrom(handlers: () => RequestHandler[]): void {
  currentHandlers = handlers
}

async function answerOf(request: Request): Promise<Response> {
  if (currentHandlers === null) return fetch(request)
  return (
    (await getResponse(currentHandlers(), request)) ??
    HttpResponse.json({ error: 'No mock for POST /api/v1/query' }, { status: 500 })
  )
}

/** The answer of POST /api/v1/query as the event stream of POST /api/v1/query/stream. */
function eventStreamOf(response: { answer?: string }): ReadableStream<Uint8Array> {
  const encoder = new TextEncoder()
  const frame = (event: Record<string, unknown>) =>
    encoder.encode(`event: ${String(event.type)}\ndata: ${JSON.stringify(event)}\n\n`)
  const parts = mockAnswerDeltas ? ((response.answer ?? '').match(/\S+\s*/g) ?? []) : []
  return new ReadableStream({
    start(controller) {
      controller.enqueue(frame({ type: 'started' }))
      for (const part of parts) controller.enqueue(frame({ type: 'delta', text: part }))
      controller.enqueue(frame({ type: 'final', response }))
      controller.close()
    },
  })
}

export const queryHandlers = [
  // Mirrors QueryStreamController: the same turn as POST /api/v1/query below - or the handler a
  // test put in its place - with its refusals as plain statuses and its answer as events.
  http.post('/api/v1/query/stream', async ({ request }) => {
    const answered = await answerOf(
      new Request(new URL('/api/v1/query', request.url), {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: await request.text(),
      }),
    )
    if (!answered.ok) {
      return new HttpResponse(await answered.text(), {
        status: answered.status,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return new HttpResponse(eventStreamOf((await answered.json()) as { answer?: string }), {
      headers: { 'Content-Type': 'text/event-stream' },
    })
  }),
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
          noSpaceContext: false,
          answerTruncated: false,
          answerEmpty: false,
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
      // Mirrors QueryResponse#noteItems: the note state that went into *this* answer -
      // never one the condensation of this very turn would produce, and null for an ephemeral
      // query that has no persisted chat at all.
      noteItems: mockChatDetails[chatId]?.noteItems ?? null,
    })
  }),
]
