import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, beforeEach, vi } from 'vitest'
import { server } from '../mocks/server'
import { useChatStore } from './chatStore'
import { useChatListStore } from './chatListStore'

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
    scope: 'all',
    referencedLibraryIds: [],
    pendingSettingsUpdate: null,
  })
}

describe('chatStore', () => {
  beforeEach(() => {
    resetChatStore()
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
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
        scope: 'libraries',
        referencedLibraryIds: ['library-a'],
      })

      useChatStore.getState().startNewChat(SPACE_ID)

      const state = useChatStore.getState()
      expect(state.spaceId).toBe(SPACE_ID)
      expect(state.chatId).toBeNull()
      expect(state.messages).toHaveLength(0)
      expect(state.scope).toBe('all')
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

    it('restores the chat-level scope as "libraries" with its referencedLibraryIds', async () => {
      await useChatStore.getState().loadChat('chat-personal-2')

      const state = useChatStore.getState()
      expect(state.scope).toBe('libraries')
      expect(state.referencedLibraryIds).toEqual(['library-referat-50'])
    })

    // #564 review: the "empty bar" persisted state (useKnowledge=false, no referencedLibraryIds)
    // must restore as scope "none", not silently fall back to "all" or "libraries".
    it('restores the chat-level scope as "none" for useKnowledge=false with no referencedLibraryIds', async () => {
      server.use(
        http.get('/api/v1/chats/:chatId', ({ params }) =>
          HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: false,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
        ),
      )

      await useChatStore.getState().loadChat('chat-empty-bar')

      const state = useChatStore.getState()
      expect(state.scope).toBe('none')
      expect(state.referencedLibraryIds).toEqual([])
    })

    // #564 review: a chat persisted with useKnowledge=true still carrying leftover
    // referencedLibraryIds (a legacy/inconsistent record) must show @Alles-Wissen, not a mix of
    // both - the bar has to mirror exactly one state, and the ids are meaningless while
    // useKnowledge is true (#560).
    it('discards referencedLibraryIds locally when useKnowledge=true carries a non-empty list', async () => {
      server.use(
        http.get('/api/v1/chats/:chatId', ({ params }) =>
          HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: true,
            referencedLibraryIds: ['library-stale'],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
        ),
      )

      await useChatStore.getState().loadChat('chat-stale-ids')

      const state = useChatStore.getState()
      expect(state.scope).toBe('all')
      expect(state.referencedLibraryIds).toEqual([])
    })

    it('sets an error when the chat cannot be found', async () => {
      await useChatStore.getState().loadChat('chat-unknown')

      const state = useChatStore.getState()
      expect(state.error).toBeTruthy()
      expect(state.isLoadingChat).toBe(false)
    })

    // #548 review, finding 2: a failed load used to leave the previously active chat (id, space,
    // history) in place, so the next message would silently go to a chat the user is no longer
    // looking at, even though the error alert is shown.
    it('clears the previously active chat when loading a different chat fails', async () => {
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      expect(useChatStore.getState().chatId).toBe(EXISTING_CHAT_ID)

      await useChatStore.getState().loadChat('chat-unknown')

      const state = useChatStore.getState()
      expect(state.error).toBeTruthy()
      expect(state.chatId).toBeNull()
      expect(state.spaceId).toBeNull()
      expect(state.messages).toEqual([])
      expect(state.title).toBeNull()
    })

    // #548 review, finding d: a slower-arriving response for an earlier loadChat call must not
    // overwrite a faster, later one - only the most recently requested chat may end up active.
    it('ignores a stale loadChat response that arrives after a newer one resolved', async () => {
      server.use(
        http.get('/api/v1/chats/:chatId', async ({ params }) => {
          if (params.chatId === EXISTING_CHAT_ID) {
            // Slow response for the chat requested first.
            await new Promise((resolve) => setTimeout(resolve, 30))
          }
          return HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: `Titel ${params.chatId}`,
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )

      const slowLoad = useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      const fastLoad = useChatStore.getState().loadChat(EMPTY_CHAT_ID)
      await Promise.all([slowLoad, fastLoad])

      expect(useChatStore.getState().chatId).toBe(EMPTY_CHAT_ID)
    })

    // #548 review, finding d: a synchronous startNewChat while a loadChat is still in flight must
    // not be clobbered once that stale response eventually arrives.
    it('does not let a stale loadChat response overwrite a subsequent startNewChat', async () => {
      server.use(
        http.get('/api/v1/chats/:chatId', async ({ params }) => {
          await new Promise((resolve) => setTimeout(resolve, 30))
          return HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )

      const pendingLoad = useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      useChatStore.getState().startNewChat(SPACE_ID)
      await pendingLoad

      const state = useChatStore.getState()
      expect(state.chatId).toBeNull()
      expect(state.messages).toEqual([])
    })

    // #559: startNewChat invalidates an in-flight loadChat via chatLoadSequence, but the
    // superseded loadChat handler returns before its own set() call - nobody else resets
    // isLoadingChat back to false, so ChatPage's spinner branch (`if (isLoadingChat) ...`) never
    // clears and the chat input never reappears.
    it('clears isLoadingChat when startNewChat supersedes an in-flight loadChat', async () => {
      server.use(
        http.get('/api/v1/chats/:chatId', async ({ params }) => {
          await new Promise((resolve) => setTimeout(resolve, 30))
          return HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )

      const pendingLoad = useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      expect(useChatStore.getState().isLoadingChat).toBe(true)

      useChatStore.getState().startNewChat(SPACE_ID)
      // startNewChat is synchronous - the loading flag must already be cleared right after it
      // returns, not only once the superseded loadChat's (never-applied) response arrives.
      expect(useChatStore.getState().isLoadingChat).toBe(false)

      await pendingLoad

      expect(useChatStore.getState().isLoadingChat).toBe(false)
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

    // Payload mapping for all three chip-bar states (#560): @Alles-Wissen -> useKnowledge=true (no
    // libraryIds), concrete chips -> useKnowledge=false + libraryIds, empty bar -> useKnowledge=
    // false with an empty libraryIds array, mirroring exactly what the bar shows.
    describe('maps scope to the query payload', () => {
      function captureQueryBody() {
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
                answeredWithoutKnowledge:
                  capturedBody?.useKnowledge === false &&
                  ((capturedBody?.libraryIds as string[] | undefined)?.length ?? 0) === 0,
              },
              chatId: EMPTY_CHAT_ID,
            })
          }),
        )
        return () => capturedBody
      }

      it('sends useKnowledge=true without libraryIds for scope "all"', async () => {
        const getBody = captureQueryBody()
        await useChatStore.getState().loadChat(EMPTY_CHAT_ID)
        useChatStore.setState({ scope: 'all', referencedLibraryIds: [] })

        await useChatStore.getState().sendMessage('Frage')

        expect(getBody()?.useKnowledge).toBe(true)
        expect(getBody()?.libraryIds).toBeUndefined()
      })

      it('sends useKnowledge=false with the referenced libraryIds for scope "libraries"', async () => {
        const getBody = captureQueryBody()
        await useChatStore.getState().loadChat(EMPTY_CHAT_ID)
        useChatStore.setState({
          scope: 'libraries',
          referencedLibraryIds: ['library-a', 'library-b'],
        })

        await useChatStore.getState().sendMessage('Frage')

        expect(getBody()?.useKnowledge).toBe(false)
        expect(getBody()?.libraryIds).toEqual(['library-a', 'library-b'])
        expect(useChatStore.getState().messages[1].answeredWithoutKnowledge).toBe(false)
      })

      it('sends useKnowledge=false with an empty libraryIds array for scope "none"', async () => {
        const getBody = captureQueryBody()
        await useChatStore.getState().loadChat(EMPTY_CHAT_ID)
        useChatStore.setState({ scope: 'none', referencedLibraryIds: [] })

        await useChatStore.getState().sendMessage('Frage')

        expect(getBody()?.useKnowledge).toBe(false)
        expect(getBody()?.libraryIds).toEqual([])
        expect(useChatStore.getState().messages[1].answeredWithoutKnowledge).toBe(true)
      })
    })

    // #548 review, finding 4: an implicitly created chat must show up in its space's chat list
    // right away, not only after a manual reload of the list.
    it('adds the implicitly created chat to chatListStore', async () => {
      useChatStore.getState().startNewChat(SPACE_ID)

      await useChatStore.getState().sendMessage('Erste Frage')

      const chatId = useChatStore.getState().chatId
      const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
      expect(chats?.some((chat) => chat.id === chatId)).toBe(true)
    })

    // #548 review, finding 4: every turn should bump the chat to the top of its space's list, the
    // same way the backend's own updatedAt bump would once the list is reloaded.
    it('touches the chat in chatListStore after every turn', async () => {
      useChatListStore.setState({
        chatsBySpaceId: {
          [SPACE_ID]: [
            {
              id: EMPTY_CHAT_ID,
              spaceId: SPACE_ID,
              authorId: 'mock-user-id',
              title: null,
              useKnowledge: true,
              referencedLibraryIds: [],
              status: 'PRIVATE',
              createdAt: '2020-01-01T00:00:00Z',
              updatedAt: '2020-01-01T00:00:00Z',
            },
            {
              id: 'chat-personal-2',
              spaceId: SPACE_ID,
              authorId: 'mock-user-id',
              title: 'Neuer als der Ziel-Chat',
              useKnowledge: true,
              referencedLibraryIds: [],
              status: 'PRIVATE',
              createdAt: '2027-01-01T00:00:00Z',
              updatedAt: '2027-01-01T00:00:00Z',
            },
          ],
        },
      })
      await useChatStore.getState().loadChat(EMPTY_CHAT_ID)

      await useChatStore.getState().sendMessage('Frage')

      const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
      expect(chats?.[0].id).toBe(EMPTY_CHAT_ID)
    })
  })

  // #557: the chat's title after an answer arrives - the immediate fallback QueryResponse#chatTitle
  // carries, and the delayed reload that picks up the LLM-derived title generated asynchronously
  // on the backend after a chat's very first turn.
  describe('chat title (#557)', () => {
    afterEach(() => {
      vi.useRealTimers()
    })

    it("applies the response's chatTitle to the store and chatListStore immediately", async () => {
      useChatListStore.setState({
        chatsBySpaceId: {
          'space-engineering': [
            {
              id: EMPTY_CHAT_ID,
              spaceId: 'space-engineering',
              authorId: 'mock-user-id',
              title: null,
              useKnowledge: true,
              referencedLibraryIds: [],
              status: 'PRIVATE',
              createdAt: '2020-01-01T00:00:00Z',
              updatedAt: '2020-01-01T00:00:00Z',
            },
          ],
        },
      })
      await useChatStore.getState().loadChat(EMPTY_CHAT_ID)

      await useChatStore.getState().sendMessage('Erste Frage zum Budget')

      expect(useChatStore.getState().title).toBe('Erste Frage zum Budget')
      const chats = useChatListStore.getState().chatsBySpaceId['space-engineering']
      expect(chats?.find((chat) => chat.id === EMPTY_CHAT_ID)?.title).toBe('Erste Frage zum Budget')
    })

    it('reloads the chat after the first turn and picks up the LLM-derived title once ready', async () => {
      vi.useFakeTimers()
      server.use(
        http.get('/api/v1/chats/:chatId', ({ params }) => {
          if (params.chatId !== EMPTY_CHAT_ID) {
            return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
          }
          return HttpResponse.json({
            id: EMPTY_CHAT_ID,
            spaceId: 'space-engineering',
            authorId: 'mock-user-id',
            title: 'LLM-generierter Titel',
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )
      useChatStore.setState({ chatId: EMPTY_CHAT_ID, spaceId: 'space-engineering', messages: [] })

      await useChatStore.getState().sendMessage('Erste Frage')
      expect(useChatStore.getState().title).not.toBe('LLM-generierter Titel')

      await vi.advanceTimersByTimeAsync(3000)

      expect(useChatStore.getState().title).toBe('LLM-generierter Titel')
    })

    it('does not schedule a reload for a follow-up turn', async () => {
      vi.useFakeTimers()
      let getChatCallCount = 0
      server.use(
        http.get('/api/v1/chats/:chatId', ({ params }) => {
          getChatCallCount++
          return HttpResponse.json({
            id: params.chatId,
            spaceId: 'space-engineering',
            authorId: 'mock-user-id',
            title: 'Titel',
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )
      useChatStore.setState({ chatId: EMPTY_CHAT_ID, spaceId: 'space-engineering', messages: [] })

      await useChatStore.getState().sendMessage('Erste Frage')
      await useChatStore.getState().sendMessage('Zweite Frage')
      await vi.advanceTimersByTimeAsync(3000)

      // Exactly one reload - from the first turn only, never the follow-up.
      expect(getChatCallCount).toBe(1)
    })

    it('does not apply a delayed reload once the user has navigated to a different chat', async () => {
      vi.useFakeTimers()
      server.use(
        http.get('/api/v1/chats/:chatId', ({ params }) => {
          if (params.chatId !== EMPTY_CHAT_ID) {
            return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
          }
          return HttpResponse.json({
            id: EMPTY_CHAT_ID,
            spaceId: 'space-engineering',
            authorId: 'mock-user-id',
            title: 'LLM-generierter Titel',
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )
      useChatStore.setState({ chatId: EMPTY_CHAT_ID, spaceId: 'space-engineering', messages: [] })

      await useChatStore.getState().sendMessage('Erste Frage')
      useChatStore.getState().startNewChat(SPACE_ID)
      await vi.advanceTimersByTimeAsync(3000)

      expect(useChatStore.getState().title).not.toBe('LLM-generierter Titel')
    })
  })

  describe('setScopeAll / addReferencedLibrary / removeReferencedLibrary / clearScope', () => {
    it('updates local state without persisting when no chat exists yet', () => {
      useChatStore.getState().startNewChat(SPACE_ID)

      useChatStore.getState().addReferencedLibrary('library-a')

      expect(useChatStore.getState().scope).toBe('libraries')
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-a'])
    })

    // The Ersetzungslogik at the core of #560: the first concrete chip replaces @Alles-Wissen, and
    // re-adding @Alles-Wissen replaces the concrete chips in turn.
    it('replaces @Alles-Wissen with the first concrete chip', () => {
      useChatStore.getState().startNewChat(SPACE_ID)
      expect(useChatStore.getState().scope).toBe('all')

      useChatStore.getState().addReferencedLibrary('library-a')

      expect(useChatStore.getState().scope).toBe('libraries')
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-a'])

      useChatStore.getState().addReferencedLibrary('library-b')

      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-a', 'library-b'])
    })

    it('replaces the concrete chips when @Alles-Wissen is re-added', () => {
      useChatStore.getState().startNewChat(SPACE_ID)
      useChatStore.getState().addReferencedLibrary('library-a')
      useChatStore.getState().addReferencedLibrary('library-b')

      useChatStore.getState().setScopeAll()

      expect(useChatStore.getState().scope).toBe('all')
      expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    })

    it('empties the bar (scope "none") when the last concrete chip is removed', () => {
      useChatStore.getState().startNewChat(SPACE_ID)
      useChatStore.getState().addReferencedLibrary('library-a')

      useChatStore.getState().removeReferencedLibrary('library-a')

      expect(useChatStore.getState().scope).toBe('none')
      expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    })

    it('keeps scope "libraries" when a chip is removed but others remain', () => {
      useChatStore.getState().startNewChat(SPACE_ID)
      useChatStore.getState().addReferencedLibrary('library-a')
      useChatStore.getState().addReferencedLibrary('library-b')

      useChatStore.getState().removeReferencedLibrary('library-a')

      expect(useChatStore.getState().scope).toBe('libraries')
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-b'])
    })

    it('empties the bar (scope "none") when @Alles-Wissen is removed via clearScope', () => {
      useChatStore.getState().startNewChat(SPACE_ID)
      expect(useChatStore.getState().scope).toBe('all')

      useChatStore.getState().clearScope()

      expect(useChatStore.getState().scope).toBe('none')
      expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    })

    it('persists scope "all" via PATCH once a chat is active', async () => {
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
            referencedLibraryIds: patchedBody.referencedLibraryIds ?? [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )
      await useChatStore.getState().loadChat('chat-personal-2') // starts as scope "libraries"

      useChatStore.getState().setScopeAll()
      await new Promise((resolve) => setTimeout(resolve, 0))

      expect(useChatStore.getState().scope).toBe('all')
      expect(patchedBody?.useKnowledge).toBe(true)
      expect(patchedBody?.referencedLibraryIds).toEqual([])
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
            useKnowledge: patchedBody.useKnowledge,
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

      expect(patchedBody?.useKnowledge).toBe(false)
      expect(patchedBody?.referencedLibraryIds).toEqual(['library-a'])

      useChatStore.getState().removeReferencedLibrary('library-a')
      await new Promise((resolve) => setTimeout(resolve, 0))

      expect(patchedBody?.referencedLibraryIds).toEqual([])
    })

    // #548 review, finding 3: a failed PATCH used to leave the UI showing a setting the server
    // never applied (chat settings take precedence server-side) - it must roll back and surface
    // the error instead. Carried over to the chip-only model: rolling back both fields together
    // keeps the bar an exact mirror of the persisted chat settings.
    it('rolls back the scope and shows an error when the PATCH fails', async () => {
      server.use(
        http.patch('/api/v1/chats/:chatId', () => {
          return HttpResponse.json({ error: 'Speichern fehlgeschlagen' }, { status: 500 })
        }),
      )
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      expect(useChatStore.getState().scope).toBe('all')

      useChatStore.getState().clearScope()
      // Immediately after the call, the bar already reflects the optimistic value.
      expect(useChatStore.getState().scope).toBe('none')

      await useChatStore.getState().pendingSettingsUpdate

      const state = useChatStore.getState()
      expect(state.scope).toBe('all')
      expect(state.error).toBeTruthy()
    })

    it('rolls back referencedLibraryIds and shows an error when the PATCH fails', async () => {
      server.use(
        http.patch('/api/v1/chats/:chatId', () => {
          return HttpResponse.json({ error: 'Speichern fehlgeschlagen' }, { status: 500 })
        }),
      )
      await useChatStore.getState().loadChat(EMPTY_CHAT_ID)

      useChatStore.getState().addReferencedLibrary('library-a')
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-a'])

      await useChatStore.getState().pendingSettingsUpdate

      const state = useChatStore.getState()
      expect(state.scope).toBe('all')
      expect(state.referencedLibraryIds).toEqual([])
      expect(state.error).toBeTruthy()
    })

    // #548 review, finding 3: sendMessage must await a still-in-flight settings PATCH before
    // querying, or the query could reach the server (and be answered using the chat's persisted
    // settings) before the PATCH that was meant to change them.
    it('awaits a pending settings PATCH before sending the query', async () => {
      const events: string[] = []
      server.use(
        http.patch('/api/v1/chats/:chatId', async () => {
          events.push('patch-start')
          await new Promise((resolve) => setTimeout(resolve, 30))
          events.push('patch-end')
          return HttpResponse.json({
            id: EXISTING_CHAT_ID,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: false,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
        http.post('/api/v1/query', async ({ request }) => {
          events.push('query-start')
          const body = (await request.json()) as { chatId?: string }
          return HttpResponse.json({
            answer: 'Antwort',
            sources: [],
            metadata: {
              model: 'gpt-4o',
              tokenCount: 5,
              durationMs: 1,
              answeredWithoutKnowledge: false,
            },
            chatId: body.chatId,
          })
        }),
      )
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)

      useChatStore.getState().clearScope()
      await useChatStore.getState().sendMessage('Frage')

      expect(events).toEqual(['patch-start', 'patch-end', 'query-start'])
    })
  })
})
