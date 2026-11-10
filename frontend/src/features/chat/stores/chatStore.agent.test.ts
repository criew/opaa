import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { server } from '@/test/server'
import type { ChatDetail, QueryRequest } from '@/shared/api/types'
import { clearSettingsPersistenceCache } from './chatSettingsPersistence'
import { useChatStore } from './chatStore'

const CHAT_ID = 'chat-agent-1'

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

describe('chatStore with an agent', () => {
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

  it('sends the agent id with the question, a prompt alongside, and names the agent at once', async () => {
    const bodies: QueryRequest[] = []
    server.use(
      http.post('/api/v1/query', async ({ request }) => {
        bodies.push((await request.json()) as QueryRequest)
        return HttpResponse.json({
          answer: 'Die Frist beträgt vier Wochen.',
          sources: [],
          metadata: { model: 'gpt-4o', tokenCount: 10, durationMs: 5 },
          chatId: CHAT_ID,
          agentRun: {
            runId: 'run-1',
            agentId: 'agent-recherche',
            agentName: 'Recherche',
            endState: 'COMPLETED',
            steps: [],
          },
        })
      }),
    )
    await useChatStore.getState().loadChat(CHAT_ID)

    const sending = useChatStore
      .getState()
      .sendMessage('Welche Frist gilt?', { id: 'prompt-frist', title: 'Frist' }, [], {
        id: 'agent-recherche',
        name: 'Recherche',
      })
    expect(useChatStore.getState().messages[0]).toMatchObject({
      role: 'user',
      agentName: 'Recherche',
      usedPromptTitle: 'Frist',
    })
    await sending

    expect(bodies[0].agentId).toBe('agent-recherche')
    expect(bodies[0].usedPromptId).toBe('prompt-frist')
  })

  it.each([
    ['AGENT_NOT_AVAILABLE', false],
    ['AGENT_WORKSPACE_FULL', true],
  ])('hands a question refused with %s back, keeping the agent: %s', async (code, keep) => {
    server.use(
      http.post('/api/v1/query', () =>
        HttpResponse.json(
          { error: 'Abgelehnt.', code },
          { status: code === 'AGENT_WORKSPACE_FULL' ? 429 : 409 },
        ),
      ),
    )
    await useChatStore.getState().loadChat(CHAT_ID)

    const refused = await useChatStore.getState().sendMessage('Welche Frist gilt?', undefined, [], {
      id: 'agent-recherche',
      name: 'Recherche',
    })

    expect(refused).toMatchObject({ restoreDraft: 'Welche Frist gilt?', keepAgent: keep })
  })

  it('sends no agent id for a question without one', async () => {
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

    await useChatStore.getState().sendMessage('Frage ohne Agent')

    expect(bodies[0]).not.toHaveProperty('agentId')
    expect(useChatStore.getState().messages[0]).not.toHaveProperty('agentName')
  })

  it('names the agent at a loaded question from the run of its answer', async () => {
    server.use(
      http.get(`/api/v1/chats/${CHAT_ID}`, () =>
        HttpResponse.json(
          chatDetail([
            {
              id: 'm1',
              role: 'USER',
              content: 'Welche Frist gilt?',
              generatedByAi: false,
              createdAt: '2026-09-24T08:00:01Z',
            },
            {
              id: 'm2',
              role: 'ASSISTANT',
              content: 'Vier Wochen.',
              generatedByAi: true,
              createdAt: '2026-09-24T08:00:02Z',
              agentRun: {
                runId: 'run-1',
                agentId: 'agent-recherche',
                agentName: 'Recherche',
                endState: 'COMPLETED',
                steps: [],
              },
            },
            {
              id: 'm3',
              role: 'USER',
              content: 'Und ohne Agent?',
              generatedByAi: false,
              createdAt: '2026-09-24T08:00:03Z',
            },
            {
              id: 'm4',
              role: 'ASSISTANT',
              content: 'Ebenso.',
              generatedByAi: true,
              createdAt: '2026-09-24T08:00:04Z',
            },
          ] as ChatDetail['messages']),
        ),
      ),
    )

    await useChatStore.getState().loadChat(CHAT_ID)

    const messages = useChatStore.getState().messages
    expect(messages[0].agentName).toBe('Recherche')
    expect(messages[2].agentName).toBeUndefined()
  })
})
