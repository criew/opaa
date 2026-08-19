import { http, HttpResponse } from 'msw'
import { describe, expect, it, beforeEach } from 'vitest'
import { server } from '../mocks/server'
import { useChatStore } from './chatStore'

const SPACE_ID = 'space-personal'
const EXISTING_CHAT_ID = 'chat-personal-1'
const EMPTY_CHAT_ID = 'chat-engineering-1'

function resetChatStore() {
  useChatStore.setState({
    spaceId: null,
    chatId: null,
    title: null,
    messages: [],
    isLoading: false,
    isLoadingChat: false,
    error: null,
    useKnowledge: true,
    referencedLibraryIds: [],
  })
}

describe('chatStore', () => {
  beforeEach(() => {
    resetChatStore()
  })

  it('starts with empty state', () => {
    const state = useChatStore.getState()
    expect(state.messages).toHaveLength(0)
    expect(state.isLoading).toBe(false)
    expect(state.error).toBeNull()
    expect(state.chatId).toBeNull()
    expect(state.spaceId).toBeNull()
  })

  describe('startNewChat', () => {
    it('resets to a blank, not-yet-persisted chat in the given space', () => {
      useChatStore.setState({
        chatId: 'chat-personal-1',
        messages: [{ id: '1', role: 'user', content: 'Hallo', timestamp: new Date() }],
        useKnowledge: false,
        referencedLibraryIds: ['library-a'],
      })

      useChatStore.getState().startNewChat(SPACE_ID)

      const state = useChatStore.getState()
      expect(state.spaceId).toBe(SPACE_ID)
      expect(state.chatId).toBeNull()
      expect(state.messages).toHaveLength(0)
      expect(state.useKnowledge).toBe(true)
      expect(state.referencedLibraryIds).toEqual([])
    })
  })

  describe('loadChat', () => {
    it('loads an existing chat with its message history', async () => {
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)

      const state = useChatStore.getState()
      expect(state.chatId).toBe(EXISTING_CHAT_ID)
      expect(state.spaceId).toBe(SPACE_ID)
      expect(state.title).toBe('Architektur des Projekts')
      expect(state.messages).toHaveLength(2)
      expect(state.messages[0].role).toBe('user')
      expect(state.messages[1].role).toBe('assistant')
      expect(state.isLoadingChat).toBe(false)
    })

    it('restores the chat-level useKnowledge and referencedLibraryIds settings', async () => {
      await useChatStore.getState().loadChat('chat-personal-2')

      const state = useChatStore.getState()
      expect(state.useKnowledge).toBe(false)
      expect(state.referencedLibraryIds).toEqual(['library-referat-50'])
    })

    it('sets an error when the chat cannot be found', async () => {
      await useChatStore.getState().loadChat('chat-unknown')

      const state = useChatStore.getState()
      expect(state.error).toBeTruthy()
      expect(state.isLoadingChat).toBe(false)
    })
  })

  describe('sendMessage', () => {
    it('implicitly creates a chat in the current space on the first message', async () => {
      useChatStore.getState().startNewChat(SPACE_ID)

      await useChatStore.getState().sendMessage('Erste Frage')

      const state = useChatStore.getState()
      expect(state.chatId).toBeTruthy()
      expect(state.messages).toHaveLength(2)
      expect(state.messages[0].role).toBe('user')
      expect(state.messages[1].role).toBe('assistant')
      expect(state.isLoading).toBe(false)
    })

    it('reuses the existing chat id for follow-up messages instead of creating a new chat', async () => {
      await useChatStore.getState().loadChat(EMPTY_CHAT_ID)

      await useChatStore.getState().sendMessage('Frage eins')
      const firstChatId = useChatStore.getState().chatId

      await useChatStore.getState().sendMessage('Frage zwei')
      const secondChatId = useChatStore.getState().chatId

      expect(firstChatId).toBe(EMPTY_CHAT_ID)
      expect(secondChatId).toBe(EMPTY_CHAT_ID)
    })

    it('sets an error and does not create a chat when no space is known', async () => {
      await useChatStore.getState().sendMessage('Hallo')

      const state = useChatStore.getState()
      expect(state.error).toBeTruthy()
      expect(state.chatId).toBeNull()
    })

    it('shows rate limit error when the server returns 429', async () => {
      useChatStore.getState().startNewChat(SPACE_ID)
      server.use(
        http.post('/api/v1/query', () => {
          return HttpResponse.json(
            {
              error: 'Rate limit exceeded. Please try again later.',
              status: 429,
              timestamp: new Date().toISOString(),
            },
            { status: 429 },
          )
        }),
      )

      await useChatStore.getState().sendMessage('Hello')

      const state = useChatStore.getState()
      expect(state.error).toBe('Rate limit exceeded. Please try again later.')
      expect(state.isLoading).toBe(false)
      expect(state.messages).toHaveLength(1)
      expect(state.messages[0].role).toBe('user')
    })

    it('sends useKnowledge=false with the referenced libraryIds when the switch is off', async () => {
      let capturedBody: Record<string, unknown> | undefined
      server.use(
        http.post('/api/v1/query', async ({ request }) => {
          capturedBody = (await request.json()) as Record<string, unknown>
          return HttpResponse.json({
            answer: 'Antwort',
            sources: [],
            metadata: {
              model: 'gpt-4o',
              tokenCount: 10,
              durationMs: 5,
              answeredWithoutKnowledge: true,
            },
            chatId: EMPTY_CHAT_ID,
          })
        }),
      )
      await useChatStore.getState().loadChat(EMPTY_CHAT_ID)
      useChatStore.setState({
        useKnowledge: false,
        referencedLibraryIds: ['library-a', 'library-b'],
      })

      await useChatStore.getState().sendMessage('Frage')

      expect(capturedBody?.useKnowledge).toBe(false)
      expect(capturedBody?.libraryIds).toEqual(['library-a', 'library-b'])
      expect(useChatStore.getState().messages[1].answeredWithoutKnowledge).toBe(true)
    })
  })

  describe('setUseKnowledge / addReferencedLibrary / removeReferencedLibrary', () => {
    it('updates local state without persisting when no chat exists yet', () => {
      useChatStore.getState().startNewChat(SPACE_ID)

      useChatStore.getState().setUseKnowledge(false)
      useChatStore.getState().addReferencedLibrary('library-a')

      expect(useChatStore.getState().useKnowledge).toBe(false)
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-a'])
    })

    it('persists useKnowledge via PATCH once a chat is active', async () => {
      let patchedBody: Record<string, unknown> | undefined
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ request, params }) => {
          patchedBody = (await request.json()) as Record<string, unknown>
          return HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: patchedBody.useKnowledge,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)

      useChatStore.getState().setUseKnowledge(false)
      await new Promise((resolve) => setTimeout(resolve, 0))

      expect(useChatStore.getState().useKnowledge).toBe(false)
      expect(patchedBody?.useKnowledge).toBe(false)
    })

    it('persists referencedLibraryIds via PATCH once a chat is active', async () => {
      let patchedBody: Record<string, unknown> | undefined
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ request, params }) => {
          patchedBody = (await request.json()) as Record<string, unknown>
          return HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: true,
            referencedLibraryIds: patchedBody.referencedLibraryIds,
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )
      await useChatStore.getState().loadChat(EMPTY_CHAT_ID)

      useChatStore.getState().addReferencedLibrary('library-a')
      await new Promise((resolve) => setTimeout(resolve, 0))

      expect(patchedBody?.referencedLibraryIds).toEqual(['library-a'])

      useChatStore.getState().removeReferencedLibrary('library-a')
      await new Promise((resolve) => setTimeout(resolve, 0))

      expect(patchedBody?.referencedLibraryIds).toEqual([])
    })
  })
})
