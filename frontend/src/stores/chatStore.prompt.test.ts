import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '../mocks/server'
import type { ChatDetail, QueryRequest } from '../types/api'
import { clearSettingsPersistenceCache, useChatStore } from './chatStore'

const CHAT_ID = 'chat-prompt-1'

function chatDetail(messages: ChatDetail['messages']): ChatDetail {
  return {
    id: CHAT_ID,
    spaceId: 'space-personal',
    authorId: 'mock-user-id',
    title: 'Lagebericht',
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    messages,
    noteItems: [],
    createdAt: '2026-09-24T08:00:00Z',
    updatedAt: '2026-09-24T08:00:05Z',
  }
}

describe('chatStore with a prompt (#1903)', () => {
  beforeEach(() => {
    clearSettingsPersistenceCache()
    useChatStore.setState({
      spaceId: null,
      chatId: null,
      title: null,
      messages: [],
      isLoading: false,
      isLoadingChat: false,
      error: null,
      scope: 'all',
      referencedLibraryIds: [],
      metadataFilter: null,
      noteItems: [],
      pendingSettingsUpdate: null,
    })
    server.use(http.get(`/api/v1/chats/${CHAT_ID}`, () => HttpResponse.json(chatDetail([]))))
  })

  it('sends the prompt id with the question and shows its title at the question at once', async () => {
    const bodies: QueryRequest[] = []
    server.use(
      http.post('/api/v1/query', async ({ request }) => {
        bodies.push((await request.json()) as QueryRequest)
        return HttpResponse.json({
          answer: 'Der Vorgang ist abgeschlossen.',
          sources: [],
          metadata: { model: 'gpt-4o', tokenCount: 10, durationMs: 5 },
          chatId: CHAT_ID,
          chatTitle: 'Lagebericht',
        })
      }),
    )
    await useChatStore.getState().loadChat(CHAT_ID)

    const sending = useChatStore
      .getState()
      .sendMessage('Fasse den Stand zum 24.09.2026 zusammen.', {
        id: 'prompt-zusammenfassung',
        title: 'Zusammenfassung',
      })
    expect(useChatStore.getState().messages[0]).toMatchObject({
      role: 'user',
      usedPromptTitle: 'Zusammenfassung',
    })
    await sending

    expect(bodies).toHaveLength(1)
    expect(bodies[0].usedPromptId).toBe('prompt-zusammenfassung')
  })

  it('sends no prompt id for a question without one', async () => {
    const bodies: QueryRequest[] = []
    server.use(
      http.post('/api/v1/query', async ({ request }) => {
        bodies.push((await request.json()) as QueryRequest)
        return HttpResponse.json({
          answer: 'Antwort',
          sources: [],
          metadata: { model: 'gpt-4o', tokenCount: 10, durationMs: 5 },
          chatId: CHAT_ID,
        })
      }),
    )
    await useChatStore.getState().loadChat(CHAT_ID)

    await useChatStore.getState().sendMessage('Frei formulierte Frage')

    expect(bodies[0]).not.toHaveProperty('usedPromptId')
    expect(useChatStore.getState().messages[0].usedPromptTitle).toBeUndefined()
  })

  it('reads the prompt snapshot of a persisted question back from the history', async () => {
    server.use(
      http.get(`/api/v1/chats/${CHAT_ID}`, () =>
        HttpResponse.json(
          chatDetail([
            {
              id: 'message-1',
              chatId: CHAT_ID,
              role: 'USER',
              content: 'Fasse den Stand zum 01.09.2026 zusammen.',
              usedPromptId: 'prompt-zusammenfassung',
              usedPromptTitle: 'Zusammenfassung',
              createdAt: '2026-09-24T08:00:00Z',
            },
            {
              id: 'message-2',
              chatId: CHAT_ID,
              role: 'ASSISTANT',
              content: 'Der Vorgang ist abgeschlossen.',
              createdAt: '2026-09-24T08:00:05Z',
            },
          ]),
        ),
      ),
    )

    await useChatStore.getState().loadChat(CHAT_ID)

    const [question, answer] = useChatStore.getState().messages
    expect(question.usedPromptTitle).toBe('Zusammenfassung')
    expect(answer.usedPromptTitle).toBeUndefined()
  })
})
