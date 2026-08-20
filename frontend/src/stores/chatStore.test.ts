import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, beforeEach, vi } from 'vitest'
import { server } from '../mocks/server'
import { clearSettingsPersistenceCache, dropChatSettingsCache, useChatStore } from './chatStore'
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
    // settingsUpdateChains/confirmedSettingsByChatId are module state, not store state (#573) -
    // they survive resetChatStore() above and would otherwise leak between test cases.
    clearSettingsPersistenceCache()
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
    // #565: a settings PATCH for the chat that was active when the change was made must not roll
    // back a *different* chat's state once the user has since navigated away and its slow,
    // failing response finally arrives.
    it('does not roll back a different chat once a stale settings PATCH from a previous chat fails', async () => {
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ params, request }) => {
          const body = (await request.json()) as Record<string, unknown>
          if (params.chatId === EXISTING_CHAT_ID) {
            // Slow, failing PATCH for the chat that was active when the change was made.
            await new Promise((resolve) => setTimeout(resolve, 30))
            return HttpResponse.json({ error: 'Speichern fehlgeschlagen' }, { status: 500 })
          }
          return HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: body.useKnowledge,
            referencedLibraryIds: body.referencedLibraryIds ?? [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      expect(useChatStore.getState().scope).toBe('all')

      // Action on chat A - optimistic update applied, PATCH in flight (slow, will fail).
      useChatStore.getState().clearScope()
      const stalePatch = useChatStore.getState().pendingSettingsUpdate

      // User navigates to a different chat before the stale PATCH settles, and changes its
      // settings too.
      await useChatStore.getState().loadChat(EMPTY_CHAT_ID)
      useChatStore.getState().addReferencedLibrary('library-new-chat')
      await useChatStore.getState().pendingSettingsUpdate

      expect(useChatStore.getState().scope).toBe('libraries')
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-new-chat'])

      // The stale failure from chat A, now inactive, arrives.
      await stalePatch

      const state = useChatStore.getState()
      expect(state.chatId).toBe(EMPTY_CHAT_ID)
      expect(state.scope).toBe('libraries')
      expect(state.referencedLibraryIds).toEqual(['library-new-chat'])
    })

    // #565 review: isolates the chatId guard from the sequence guard above - no further settings
    // change happens on the newly active chat, so settingsUpdateSequence still points at the
    // stale request and cannot itself block the rollback. Only the chatId comparison can. Removing
    // just that guard (keeping the sequence guard) turns this test red.
    it('does not inherit an error or rolled-back state from a stale PATCH failure after simply navigating to a different chat', async () => {
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ params }) => {
          if (params.chatId === EXISTING_CHAT_ID) {
            await new Promise((resolve) => setTimeout(resolve, 30))
            return HttpResponse.json({ error: 'Speichern fehlgeschlagen' }, { status: 500 })
          }
          throw new Error(`unexpected PATCH for ${String(params.chatId)}`)
        }),
      )
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      expect(useChatStore.getState().scope).toBe('all')

      useChatStore.getState().clearScope() // scope -> 'none', PATCH in flight (slow, will fail)
      const stalePatch = useChatStore.getState().pendingSettingsUpdate

      // User simply navigates away - to a chat with a different scope of its own - without any
      // further settings change. Using a chat whose scope differs from EXISTING_CHAT_ID's own
      // pre-change value ('all') makes the scope assertion below actually exercise the guard too,
      // not just the error assertion (#570 review, third round).
      await useChatStore.getState().loadChat('chat-personal-2')
      expect(useChatStore.getState().scope).toBe('libraries') // chat-personal-2's own, unrelated scope
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-referat-50'])

      // The stale failure from the chat that's no longer active arrives.
      await stalePatch

      const state = useChatStore.getState()
      expect(state.chatId).toBe('chat-personal-2')
      expect(state.scope).toBe('libraries')
      expect(state.referencedLibraryIds).toEqual(['library-referat-50'])
      expect(state.error).toBeNull()
    })

    // #573: a settings PATCH that finally succeeds after the user navigated away and back to the
    // *same* chat must not leave the chip bar showing the stale server value a concurrent loadChat
    // read while the PATCH was still in flight - the late success is, at the moment it lands, the
    // more authoritative source and must win. The chatId guard alone cannot catch this case (unlike
    // the "navigate to a *different* chat" tests above): navigating back to the same chat makes
    // get().chatId === requestChatId true again, so only a sequence guard mirroring the failure
    // handler's can tell this success apart from one that has since been superseded.
    it('applies a late-succeeding PATCH result even after loadChat re-read the still-old server value for the same chat in between', async () => {
      let resolveExistingChatPatch: (() => void) | undefined
      server.use(
        http.get('/api/v1/chats/:chatId', ({ params }) =>
          HttpResponse.json({
            id: params.chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            // Every GET for this chat - including the one loadChat fires from B back to A below -
            // keeps returning the pre-change settings: the server has not applied the still-in-
            // flight PATCH yet.
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          }),
        ),
        http.patch('/api/v1/chats/:chatId', async ({ request, params }) => {
          const body = (await request.json()) as Record<string, unknown>
          if (params.chatId !== EXISTING_CHAT_ID) {
            return HttpResponse.json({
              id: String(params.chatId),
              spaceId: SPACE_ID,
              authorId: 'mock-user-id',
              title: null,
              useKnowledge: body.useKnowledge,
              referencedLibraryIds: body.referencedLibraryIds ?? [],
              status: 'PRIVATE',
              messages: [],
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
            })
          }
          // Deliberately held open until this test resolves it itself - deterministic, unlike a
          // fixed delay racing the loadChat round-trips below.
          await new Promise<void>((resolve) => {
            resolveExistingChatPatch = resolve
          })
          return HttpResponse.json({
            id: EXISTING_CHAT_ID,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: body.useKnowledge,
            referencedLibraryIds: body.referencedLibraryIds ?? [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )

      await useChatStore.getState().loadChat(EXISTING_CHAT_ID) // chat A, scope 'all'
      expect(useChatStore.getState().scope).toBe('all')

      useChatStore.getState().clearScope() // scope -> 'none' optimistically, PATCH held open
      const stalePatch = useChatStore.getState().pendingSettingsUpdate

      await useChatStore.getState().loadChat(EMPTY_CHAT_ID) // navigate away to chat B
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID) // navigate back to chat A
      // The server still has not applied the in-flight PATCH - loadChat's snapshot is stale.
      expect(useChatStore.getState().scope).toBe('all')

      resolveExistingChatPatch?.()
      await stalePatch

      const state = useChatStore.getState()
      expect(state.chatId).toBe(EXISTING_CHAT_ID)
      expect(state.scope).toBe('none')
      expect(state.referencedLibraryIds).toEqual([])
    })

    // #618 review: mirrors "does not inherit an error or rolled-back state..." above, but for the
    // success handler's chatId guard - a settings PATCH for chat A that only ends up succeeding
    // after the user has since navigated to chat B (with no further settings change of their own)
    // must not overwrite B's own settings with A's. No further settings change happens on B, so
    // settingsUpdateSequence still points at A's own request and cannot itself block this -
    // removing just the chatId guard (keeping the sequence guard) turns this test red.
    it("does not overwrite a different chat's settings with a stale settings PATCH that only succeeds after simply navigating away", async () => {
      let resolveExistingChatPatch: (() => void) | undefined
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ params }) => {
          if (params.chatId !== EXISTING_CHAT_ID) {
            throw new Error(`unexpected PATCH for ${String(params.chatId)}`)
          }
          // Deliberately held open until this test resolves it itself - deterministic, unlike a
          // fixed delay racing the loadChat round-trip below.
          await new Promise<void>((resolve) => {
            resolveExistingChatPatch = resolve
          })
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
      )
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      expect(useChatStore.getState().scope).toBe('all')

      useChatStore.getState().clearScope() // scope -> 'none', PATCH in flight (slow, will succeed)
      const stalePatch = useChatStore.getState().pendingSettingsUpdate

      // User simply navigates away - to a chat with a different scope of its own - without any
      // further settings change.
      await useChatStore.getState().loadChat('chat-personal-2')
      expect(useChatStore.getState().scope).toBe('libraries')
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-referat-50'])

      // The stale success from the chat that's no longer active arrives.
      resolveExistingChatPatch?.()
      await stalePatch

      const state = useChatStore.getState()
      expect(state.chatId).toBe('chat-personal-2')
      expect(state.scope).toBe('libraries')
      expect(state.referencedLibraryIds).toEqual(['library-referat-50'])
    })

    // #618 review: mirrors "lets the last requested settings change win over an earlier,
    // slower-failing PATCH on the same chat" below, but for the success handler's sequence guard -
    // here the *earlier* action's PATCH also succeeds, just later than the newer action's own
    // optimistic update. The chatId never changes in this test, so removing just the sequence guard
    // (keeping the chatId guard) turns this test red.
    it('does not let an earlier, slower-succeeding settings PATCH on the same chat overwrite a newer, already-applied change', async () => {
      let callIndex = 0
      server.use(
        http.patch('/api/v1/chats/:chatId', async () => {
          callIndex++
          const isFirstCall = callIndex === 1
          // Action 1's PATCH settles first (it is, after all, first in the per-chat queue), but
          // action 2's own PATCH - queued behind it - is slower still, leaving a window after
          // action 1 has settled but before action 2 has, in which action 1's late-but-successful
          // response must not be allowed to revert the chip bar.
          await new Promise((resolve) => setTimeout(resolve, isFirstCall ? 20 : 40))
          return HttpResponse.json({
            id: EXISTING_CHAT_ID,
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
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      expect(useChatStore.getState().scope).toBe('all')

      useChatStore.getState().clearScope() // action 1 (older): 'all' -> 'none'
      useChatStore.getState().setScopeAll() // action 2 (last action): 'none' -> 'all'

      // Action 1 has settled (~20ms) but action 2, queued behind it and only starting once action 1
      // settles, has not (~20ms + 40ms).
      await new Promise((resolve) => setTimeout(resolve, 35))

      // Action 1's late success must not revert the chip bar back to its own ('none') value - only
      // action 2, the most recently requested change, may still update local state once its own
      // response lands.
      expect(useChatStore.getState().scope).toBe('all')

      await useChatStore.getState().pendingSettingsUpdate

      const state = useChatStore.getState()
      expect(state.scope).toBe('all')
      expect(state.error).toBeNull()
    })

    // #565 review, finding 1: two rapid scope changes on the same chat used to fire two PATCHes in
    // parallel - the network, not the order the user clicked in, decided which request the server
    // saw last. This test catches the *overlap* itself (not just its eventual symptom): the second
    // request's handler must never start executing while the first one for the same chat is still
    // in flight.
    it('sends settings PATCHes for the same chat one at a time, never overlapping', async () => {
      let inFlight = 0
      const overlapDetected: boolean[] = []
      server.use(
        http.patch('/api/v1/chats/:chatId', async () => {
          inFlight++
          overlapDetected.push(inFlight > 1)
          await new Promise((resolve) => setTimeout(resolve, 20))
          inFlight--
          return HttpResponse.json({
            id: EXISTING_CHAT_ID,
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
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)

      useChatStore.getState().clearScope() // action 1
      useChatStore.getState().addReferencedLibrary('library-a') // action 2, right after action 1

      await useChatStore.getState().pendingSettingsUpdate

      expect(overlapDetected).toEqual([false, false])
    })

    // #565: two rapid scope changes on the *same* chat - the last requested action must win, not
    // whichever PATCH response happens to arrive last over the network. Here the first (older)
    // action's PATCH is slower and fails; the second (newer, and successful) action must not be
    // undone by that stale failure once it finally arrives.
    it('lets the last requested settings change win over an earlier, slower-failing PATCH on the same chat', async () => {
      let callIndex = 0
      const capturedBodies: Record<string, unknown>[] = []
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ request }) => {
          const isFirstCall = callIndex === 0
          callIndex++
          if (isFirstCall) {
            await new Promise((resolve) => setTimeout(resolve, 30))
            return HttpResponse.json({ error: 'Speichern fehlgeschlagen' }, { status: 500 })
          }
          const body = (await request.json()) as Record<string, unknown>
          capturedBodies.push(body)
          return HttpResponse.json({
            id: EXISTING_CHAT_ID,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: body.useKnowledge,
            referencedLibraryIds: body.referencedLibraryIds ?? [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )
      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      expect(useChatStore.getState().scope).toBe('all')

      useChatStore.getState().clearScope() // action 1: 'all' -> 'none', slow, will fail
      useChatStore.getState().setScopeAll() // action 2 (last action): 'none' -> 'all', fast, succeeds

      // Action 2's chain entry is, by construction, chained behind action 1 (see
      // settingsUpdateChains) - awaiting it already means action 1 has fully settled too, so no
      // extra sleep is needed here (#570 review, third round).
      await useChatStore.getState().pendingSettingsUpdate

      const state = useChatStore.getState()
      expect(state.scope).toBe('all')
      expect(state.error).toBeNull()
      // The last request the server actually received must be action 2's payload
      // (setScopeAll -> useKnowledge=true, no referencedLibraryIds) - the serialized queue means
      // the server saw action 1's request first, then action 2's, in the order they were made.
      expect(capturedBodies).toHaveLength(1)
      expect(capturedBodies[0]?.useKnowledge).toBe(true)
      expect(capturedBodies[0]?.referencedLibraryIds).toEqual([])
    })

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

    // #570 review, second round: sendMessage must await the *active chat's own* settings chain,
    // not the global pendingSettingsUpdate slot - a fast settings change on a different chat can
    // already have cleared that slot back to null while the active chat's own PATCH is still in
    // flight (scenario: slow change on chat A, switch to chat B, fast change on B, switch back to
    // A - the global slot reflects B's already-settled PATCH, not A's still-pending one).
    it("awaits the active chat's own settings PATCH even after a different chat's faster PATCH already cleared the global pending slot", async () => {
      const events: string[] = []
      // A's settings PATCH resolves only once this test calls resolveExistingChatPatch() itself -
      // deterministic, unlike a fixed delay racing against however long the rest of this test's
      // network round-trips happen to take.
      let resolveExistingChatPatch: (() => void) | undefined
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ params }) => {
          const chatId = String(params.chatId)
          events.push(`patch-start:${chatId}`)
          if (chatId === EXISTING_CHAT_ID) {
            await new Promise<void>((resolve) => {
              resolveExistingChatPatch = resolve
            })
          }
          events.push(`patch-end:${chatId}`)
          return HttpResponse.json({
            id: chatId,
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

      await useChatStore.getState().loadChat(EXISTING_CHAT_ID) // chat A active
      useChatStore.getState().clearScope() // settings change on A, PATCH deliberately held open

      await useChatStore.getState().loadChat(EMPTY_CHAT_ID) // switch to chat B
      useChatStore.getState().addReferencedLibrary('library-b') // fast settings change on B
      await useChatStore.getState().pendingSettingsUpdate // B's PATCH settles quickly

      // The global slot now reflects B's already-settled PATCH - it says nothing about A's, whose
      // PATCH is still deliberately held open above.
      expect(useChatStore.getState().pendingSettingsUpdate).toBeNull()

      await useChatStore.getState().loadChat(EXISTING_CHAT_ID) // switch back to chat A
      const sendPromise = useChatStore.getState().sendMessage('Frage')

      // Give sendMessage's microtask chain a macrotask tick to reach (or skip) the point where it
      // would await A's own pending settings PATCH.
      await new Promise((resolve) => setTimeout(resolve, 0))

      // A's PATCH is still held open at this point - the query must not have been sent yet.
      expect(events).not.toContain('query-start')

      resolveExistingChatPatch?.()
      await sendPromise

      expect(events.indexOf(`patch-end:${EXISTING_CHAT_ID}`)).toBeLessThan(
        events.indexOf('query-start'),
      )
    })

    // #440 review, point 3: settingsUpdateChains and confirmedSettingsByChatId are module-level
    // maps keyed by chatId, not scoped to any particular user - a stale entry left over from the
    // previous user's session would otherwise survive a logout and leak into whichever session
    // reuses the same chat id next (e.g. a deep link into a chat the new user also happens to be
    // able to open).
    it('reset() clears the per-chat settings PATCH queue so a later action for the same chatId does not queue behind a stale, still-pending PATCH', async () => {
      const events: string[] = []
      let resolveFirstPatch: (() => void) | undefined
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ params, request }) => {
          const chatId = String(params.chatId)
          const body = (await request.json()) as Record<string, unknown>
          const isFirstCall = !events.some((e) => e.startsWith('patch-start'))
          events.push(`patch-start:${chatId}`)
          if (isFirstCall) {
            // Deliberately held open - reset() must drop this chat's queue entry regardless of
            // whether the PATCH it belongs to has settled yet.
            await new Promise<void>((resolve) => {
              resolveFirstPatch = resolve
            })
          }
          events.push(`patch-end:${chatId}`)
          return HttpResponse.json({
            id: chatId,
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: null,
            useKnowledge: body.useKnowledge,
            referencedLibraryIds: body.referencedLibraryIds ?? [],
            status: 'PRIVATE',
            messages: [],
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          })
        }),
      )

      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      useChatStore.getState().clearScope() // PATCH #1 fires and stays open
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(events).toEqual([`patch-start:${EXISTING_CHAT_ID}`])

      useChatStore.getState().reset()

      // A later session reusing the same chat id (e.g. the next user's tab still has a deep link
      // to it) without ever going through loadChat again - if reset() had not cleared
      // settingsUpdateChains, this action would silently queue behind PATCH #1, which is still
      // deliberately held open, instead of firing immediately.
      useChatStore.setState({ chatId: EXISTING_CHAT_ID, scope: 'all', referencedLibraryIds: [] })
      useChatStore.getState().addReferencedLibrary('library-fresh')
      await new Promise((resolve) => setTimeout(resolve, 0))

      // The second call's own handler run completes immediately (it is not held open) - this
      // proves it started (and finished) without ever awaiting PATCH #1, which is still pending.
      expect(events).toEqual([
        `patch-start:${EXISTING_CHAT_ID}`,
        `patch-start:${EXISTING_CHAT_ID}`,
        `patch-end:${EXISTING_CHAT_ID}`,
      ])

      resolveFirstPatch?.()
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    it('reset() clears the per-chat confirmed-settings PATCH-rollback baseline', async () => {
      let callIndex = 0
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ params, request }) => {
          callIndex++
          if (callIndex === 1) {
            // clearScope's own PATCH below - succeeds, so confirmedSettingsByChatId records
            // 'none' as this chat's last-confirmed settings.
            const body = (await request.json()) as Record<string, unknown>
            return HttpResponse.json({
              id: String(params.chatId),
              spaceId: SPACE_ID,
              authorId: 'mock-user-id',
              title: null,
              useKnowledge: body.useKnowledge,
              referencedLibraryIds: body.referencedLibraryIds ?? [],
              status: 'PRIVATE',
              messages: [],
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
            })
          }
          // Every later PATCH for this chat fails, forcing applyScopeChange's rollback path.
          return HttpResponse.json({ error: 'Speichern fehlgeschlagen' }, { status: 500 })
        }),
      )

      await useChatStore.getState().loadChat(EXISTING_CHAT_ID) // confirmed baseline: 'all'
      useChatStore.getState().clearScope() // scope -> 'none', succeeds, confirmed baseline: 'none'
      await useChatStore.getState().pendingSettingsUpdate
      expect(useChatStore.getState().scope).toBe('none')

      useChatStore.getState().reset()

      // A later session reusing the same chat id without going through loadChat again - if
      // reset() had not cleared confirmedSettingsByChatId, the failing PATCH below would roll
      // back to the previous session's last-confirmed 'none' instead of applyScopeChange's safe
      // default.
      useChatStore.setState({ chatId: EXISTING_CHAT_ID, scope: 'all', referencedLibraryIds: [] })
      useChatStore.getState().addReferencedLibrary('library-x') // optimistic -> 'libraries', fails
      await useChatStore.getState().pendingSettingsUpdate

      expect(useChatStore.getState().scope).toBe('all')
      expect(useChatStore.getState().referencedLibraryIds).toEqual([])
    })

    // #618 review (nit 3): dropChatSettingsCache is what chatListStore.deleteChatFromList calls
    // once a chat is actually deleted server-side (#573) - it must drop that chat's
    // settingsUpdateChains entry immediately, so a later sendMessage for the same (now-deleted)
    // chat id never queues/awaits behind a chain that can no longer matter.
    it('dropChatSettingsCache clears the per-chat settings PATCH queue so a later sendMessage does not await it', async () => {
      const events: string[] = []
      let resolveHeldPatch: (() => void) | undefined
      server.use(
        http.patch('/api/v1/chats/:chatId', async () => {
          events.push('patch-start')
          // Deliberately held open for the rest of the test - dropChatSettingsCache must not make
          // sendMessage wait for this to ever resolve.
          await new Promise<void>((resolve) => {
            resolveHeldPatch = resolve
          })
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
            metadata: { model: 'gpt-4o', tokenCount: 5, durationMs: 1 },
            chatId: body.chatId,
          })
        }),
      )

      await useChatStore.getState().loadChat(EXISTING_CHAT_ID)
      useChatStore.getState().clearScope() // PATCH #1 fires and stays open
      await new Promise((resolve) => setTimeout(resolve, 0))
      expect(events).toEqual(['patch-start'])

      dropChatSettingsCache(EXISTING_CHAT_ID) // simulates chatListStore.deleteChatFromList's cleanup

      // sendMessage on the same chat id (e.g. an in-flight send that was already under way when the
      // delete completed) must proceed straight to the query, without ever awaiting PATCH #1, which
      // is still deliberately held open.
      await useChatStore.getState().sendMessage('Frage')

      expect(events).toEqual(['patch-start', 'query-start'])

      resolveHeldPatch?.()
      await new Promise((resolve) => setTimeout(resolve, 0))
    })

    // #618 review (nit 2): confirmedSettingsByChatId must not be dropped out from under a still
    // in-flight PATCH's own failure handler just because the chat was deleted in the meantime - the
    // deferred cleanup in dropChatSettingsCache must still apply it once that PATCH itself settles.
    it('dropChatSettingsCache defers the confirmedSettingsByChatId cleanup until the in-flight PATCH for the deleted chat settles', async () => {
      let callIndex = 0
      let resolveSecondPatch: (() => void) | undefined
      server.use(
        http.patch('/api/v1/chats/:chatId', async ({ params, request }) => {
          callIndex++
          if (callIndex === 1) {
            // The first change succeeds, so confirmedSettingsByChatId records 'libraries' +
            // ['library-a'] as this chat's last-confirmed settings - deliberately different from
            // applyScopeChange's hardcoded rollback default ({scope:'all', referencedLibraryIds:[]}),
            // so the assertion below can actually tell a premature wipe apart from a correct one.
            const body = (await request.json()) as Record<string, unknown>
            return HttpResponse.json({
              id: String(params.chatId),
              spaceId: SPACE_ID,
              authorId: 'mock-user-id',
              title: null,
              useKnowledge: body.useKnowledge,
              referencedLibraryIds: body.referencedLibraryIds ?? [],
              status: 'PRIVATE',
              messages: [],
              createdAt: '2026-01-01T00:00:00Z',
              updatedAt: '2026-01-01T00:00:00Z',
            })
          }
          // The second change is held open until this test resolves it itself, then fails.
          await new Promise<void>((resolve) => {
            resolveSecondPatch = resolve
          })
          return HttpResponse.json({ error: 'Speichern fehlgeschlagen' }, { status: 500 })
        }),
      )

      await useChatStore.getState().loadChat(EXISTING_CHAT_ID) // confirmed baseline: 'all'
      useChatStore.getState().addReferencedLibrary('library-a') // succeeds, confirmed: 'libraries', ['library-a']
      await useChatStore.getState().pendingSettingsUpdate
      expect(useChatStore.getState().referencedLibraryIds).toEqual(['library-a'])

      useChatStore.getState().addReferencedLibrary('library-b') // optimistic -> ['library-a', 'library-b'], held open

      dropChatSettingsCache(EXISTING_CHAT_ID) // chat deleted while this second PATCH is still in flight

      // Gives the chained call's own request a tick to actually reach the mock handler (and
      // register resolveSecondPatch) before this test tries to resolve it - the chain only starts
      // its own updateChat() call once action 1's already-settled promise resolves, which is itself
      // asynchronous.
      await new Promise((resolve) => setTimeout(resolve, 0))
      resolveSecondPatch?.()
      await useChatStore.getState().pendingSettingsUpdate

      // The still-in-flight PATCH's own failure handler reads confirmedSettingsByChatId as its
      // rollback base (e.g. the user deleted the chat they were currently viewing without
      // navigating away first) - it must see the real last-confirmed baseline ('libraries',
      // ['library-a']), not applyScopeChange's hardcoded default, which a premature wipe by
      // dropChatSettingsCache would otherwise have forced it to fall back to.
      const state = useChatStore.getState()
      expect(state.scope).toBe('libraries')
      expect(state.referencedLibraryIds).toEqual(['library-a'])
    })
  })
})
