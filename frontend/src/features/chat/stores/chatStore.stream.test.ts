import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import type { ChatDetail } from '@/shared/api/types'
import { clearSettingsPersistenceCache } from './chatSettingsPersistence'
import { useChatStore } from './chatStore'

const CHAT_ID = 'chat-stream-1'

function chatDetail(): ChatDetail {
  return {
    id: CHAT_ID,
    spaceId: 'space-personal',
    authorId: 'mock-user-id',
    title: 'Fristen',
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    messages: [],
    noteItems: [],
    createdAt: '2026-10-31T08:00:00Z',
    updatedAt: '2026-10-31T08:00:05Z',
  }
}

const encoder = new TextEncoder()
const frame = (payload: Record<string, unknown>) =>
  encoder.encode(`event: ${String(payload.type)}\ndata: ${JSON.stringify(payload)}\n\n`)

/** A stream that sends `first` at once and the rest only once `release` is called. */
function heldStream(first: Record<string, unknown>[], rest: Record<string, unknown>[]) {
  let release: () => void = () => {}
  const handler = http.post('/api/v1/query/stream', () => {
    return new HttpResponse(
      new ReadableStream({
        async start(controller) {
          for (const payload of first) controller.enqueue(frame(payload))
          await new Promise<void>((resolve) => {
            release = resolve
          })
          for (const payload of rest) controller.enqueue(frame(payload))
          controller.close()
        },
      }),
      { headers: { 'Content-Type': 'text/event-stream' } },
    )
  })
  return { handler, release: () => release() }
}

const FINAL = {
  type: 'final',
  response: {
    answer: 'Die Frist beträgt einen Monat.',
    generatedByAi: true,
    sources: [],
    privateSourcesInContext: false,
    metadata: { model: 'gpt-4o', tokenCount: 3, durationMs: 1 },
    chatId: CHAT_ID,
    chatTitle: 'Fristen',
  },
}

describe('chatStore with a streamed answer', () => {
  beforeEach(async () => {
    clearSettingsPersistenceCache()
    useChatStore.getState().reset()
    server.use(http.get(`/api/v1/chats/${CHAT_ID}`, () => HttpResponse.json(chatDetail())))
    await useChatStore.getState().loadChat(CHAT_ID)
  })

  it('shows the provisional answer while it is written and replaces it with the final one', async () => {
    const stream = heldStream([{ type: 'started' }, { type: 'delta', text: 'Die Frist ' }], [FINAL])
    server.use(stream.handler)

    const sent = useChatStore.getState().sendMessage('Wie lang ist die Frist?')
    await expect.poll(() => useChatStore.getState().streamingAnswer).toBe('Die Frist ')
    expect(useChatStore.getState().isLoading).toBe(true)
    expect(useChatStore.getState().messages.map((m) => m.role)).toEqual(['user'])

    stream.release()
    await sent

    const state = useChatStore.getState()
    expect(state.streamingAnswer).toBeNull()
    expect(state.isLoading).toBe(false)
    expect(state.messages.map((m) => [m.role, m.content])).toEqual([
      ['user', 'Wie lang ist die Frist?'],
      ['assistant', 'Die Frist beträgt einen Monat.'],
    ])
  })

  it('cancelling ends the request and hands the question back to the input', async () => {
    const stream = heldStream([{ type: 'started' }, { type: 'delta', text: 'Die Frist ' }], [FINAL])
    server.use(stream.handler)

    const sent = useChatStore.getState().sendMessage('Wie lang ist die Frist?')
    await expect.poll(() => useChatStore.getState().streamingAnswer).toBe('Die Frist ')
    useChatStore.getState().cancelAnswer()
    const outcome = await sent

    expect(outcome).toMatchObject({ restoreDraft: 'Wie lang ist die Frist?' })
    const state = useChatStore.getState()
    expect(state.messages).toEqual([])
    expect(state.streamingAnswer).toBeNull()
    expect(state.isLoading).toBe(false)
    expect(state.error).toBeNull()
    // What the server would still have sent reaches nothing any more.
    stream.release()
    await new Promise((resolve) => setTimeout(resolve, 20))
    expect(useChatStore.getState().messages).toEqual([])
  })

  it('hands the question back when the instance stopped while it answered', async () => {
    server.use(
      http.post('/api/v1/query/stream', () => {
        return new HttpResponse(
          new ReadableStream({
            start(controller) {
              controller.enqueue(frame({ type: 'started' }))
              controller.enqueue(
                frame({
                  type: 'error',
                  error: {
                    error: 'Die Antwort wurde abgebrochen, weil der Dienst neu startet.',
                    status: 503,
                    code: 'SHUTTING_DOWN',
                    timestamp: '2026-10-31T09:00:00Z',
                  },
                }),
              )
              controller.close()
            },
          }),
          { headers: { 'Content-Type': 'text/event-stream' } },
        )
      }),
    )

    const outcome = await useChatStore.getState().sendMessage('Wie lang ist die Frist?')

    expect(outcome).toMatchObject({ restoreDraft: 'Wie lang ist die Frist?' })
    expect(useChatStore.getState().messages).toEqual([])
    expect(useChatStore.getState().error).toContain('weil der Dienst neu startet')
  })
})
