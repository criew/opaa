import { http, HttpResponse } from 'msw'
import { describe, expect, it, beforeEach } from 'vitest'
import { server } from '../mocks/server'
// #575: must be imported before chatListStore below - see the matching comment in
// chatStore.test.ts for why the chatStore/chatListStore circular import makes this order matter.
import { resetAllStores } from './resettableStores'
import { useChatListStore } from './chatListStore'

const SPACE_ID = 'space-personal'

/** Resolves once resolve() is called - lets a test hold an MSW handler open until it explicitly
 * wants the response to arrive, so it can trigger resetAllStores() while the request is still in
 * flight (#575). */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void } {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((r) => {
    resolve = r
  })
  return { promise, resolve }
}

describe('chatListStore', () => {
  beforeEach(() => {
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
  })

  it('loads chats for a space, sorted by last use', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats).toHaveLength(2)
    expect(chats?.[0].id).toBe('chat-personal-2')
    expect(chats?.[1].id).toBe('chat-personal-1')
  })

  it('creates a new chat and adds it to the space list', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    const created = await useChatListStore.getState().createChatInSpace(SPACE_ID)

    expect(created).not.toBeNull()
    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.some((chat) => chat.id === created?.id)).toBe(true)
  })

  // #548 review, nit b: server errors on these three actions used to reject silently (unhandled
  // rejection) instead of surfacing anything - now they resolve and set `error`.
  it('sets an error and returns null when creation fails on the server', async () => {
    server.use(
      http.post('/api/v1/spaces/:spaceId/chats', () => {
        return HttpResponse.json({ error: 'Erstellen fehlgeschlagen' }, { status: 500 })
      }),
    )

    const created = await useChatListStore.getState().createChatInSpace(SPACE_ID)

    expect(created).toBeNull()
    expect(useChatListStore.getState().error).toBeTruthy()
  })

  it('sets an error and keeps the old title when renaming fails on the server', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)
    server.use(
      http.patch('/api/v1/chats/:chatId', () => {
        return HttpResponse.json({ error: 'Umbenennen fehlgeschlagen' }, { status: 500 })
      }),
    )

    await useChatListStore.getState().renameChat(SPACE_ID, 'chat-personal-1', 'Neuer Titel')

    expect(useChatListStore.getState().error).toBeTruthy()
    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.find((chat) => chat.id === 'chat-personal-1')?.title).toBe(
      'Architektur des Projekts',
    )
  })

  it('sets an error and keeps the chat when deletion fails on the server', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)
    server.use(
      http.delete('/api/v1/chats/:chatId', () => {
        return HttpResponse.json({ error: 'Löschen fehlgeschlagen' }, { status: 500 })
      }),
    )

    await useChatListStore.getState().deleteChatFromList(SPACE_ID, 'chat-personal-1')

    expect(useChatListStore.getState().error).toBeTruthy()
    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.some((chat) => chat.id === 'chat-personal-1')).toBe(true)
  })

  // #548 review, finding 4.
  it('upsertChat inserts a new chat and re-sorts by last use', () => {
    useChatListStore.setState({
      chatsBySpaceId: {
        [SPACE_ID]: [
          {
            id: 'chat-old',
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: 'Alt',
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            createdAt: '2020-01-01T00:00:00Z',
            updatedAt: '2020-01-01T00:00:00Z',
          },
        ],
      },
    })

    useChatListStore.getState().upsertChat(SPACE_ID, {
      id: 'chat-new',
      spaceId: SPACE_ID,
      authorId: 'mock-user-id',
      title: null,
      useKnowledge: true,
      referencedLibraryIds: [],
      status: 'PRIVATE',
      createdAt: '2027-01-01T00:00:00Z',
      updatedAt: '2027-01-01T00:00:00Z',
    })

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.map((chat) => chat.id)).toEqual(['chat-new', 'chat-old'])
  })

  it('touchChat bumps updatedAt and moves the chat to the top, and is a no-op for an unlisted chat', () => {
    useChatListStore.setState({
      chatsBySpaceId: {
        [SPACE_ID]: [
          {
            id: 'chat-a',
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: 'A',
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            createdAt: '2020-01-01T00:00:00Z',
            updatedAt: '2020-01-01T00:00:00Z',
          },
          {
            id: 'chat-b',
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: 'B',
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            createdAt: '2027-01-01T00:00:00Z',
            updatedAt: '2027-01-01T00:00:00Z',
          },
        ],
      },
    })

    useChatListStore.getState().touchChat(SPACE_ID, 'chat-a', '2099-01-01T00:00:00Z')

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.map((chat) => chat.id)).toEqual(['chat-a', 'chat-b'])

    // A chat that isn't in the (possibly not yet loaded) list is left alone rather than crashing.
    useChatListStore.getState().touchChat('space-unloaded', 'chat-x', '2099-01-01T00:00:00Z')
    expect(useChatListStore.getState().chatsBySpaceId['space-unloaded']).toBeUndefined()
  })

  it('updateChatTitle applies a title without re-sorting, and is a no-op for an unlisted chat', () => {
    useChatListStore.setState({
      chatsBySpaceId: {
        [SPACE_ID]: [
          {
            id: 'chat-a',
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
            id: 'chat-b',
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: 'B',
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            createdAt: '2027-01-01T00:00:00Z',
            updatedAt: '2027-01-01T00:00:00Z',
          },
        ],
      },
    })

    useChatListStore.getState().updateChatTitle(SPACE_ID, 'chat-a', 'LLM-generierter Titel')

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    // #557: a title update is not a "last use" event - the order stays exactly as it was, unlike
    // touchChat.
    expect(chats?.map((chat) => chat.id)).toEqual(['chat-a', 'chat-b'])
    expect(chats?.find((chat) => chat.id === 'chat-a')?.title).toBe('LLM-generierter Titel')

    // A chat that isn't in the (possibly not yet loaded) list is left alone rather than crashing.
    useChatListStore.getState().updateChatTitle('space-unloaded', 'chat-x', 'Titel')
    expect(useChatListStore.getState().chatsBySpaceId['space-unloaded']).toBeUndefined()
  })

  it('renames a chat in the list', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    await useChatListStore.getState().renameChat(SPACE_ID, 'chat-personal-1', 'Neuer Titel')

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.find((chat) => chat.id === 'chat-personal-1')?.title).toBe('Neuer Titel')
  })

  it('deletes a chat from the list', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    await useChatListStore.getState().deleteChatFromList(SPACE_ID, 'chat-personal-1')

    const chats = useChatListStore.getState().chatsBySpaceId[SPACE_ID]
    expect(chats?.some((chat) => chat.id === 'chat-personal-1')).toBe(false)
  })

  it('sets an error when loading fails', async () => {
    await useChatListStore.getState().loadChats('space-unknown')

    expect(useChatListStore.getState().error).toBeTruthy()
  })

  // #575: found while systematically checking the resettableStores registry for further
  // unguarded async set() paths beyond the ones the issue named explicitly.
  it('a loadChats response arriving after a session reset does not resurrect the chat list', async () => {
    const gate = deferred<void>()
    server.use(
      http.get('/api/v1/spaces/:spaceId/chats', async () => {
        await gate.promise
        return HttpResponse.json([
          {
            id: 'chat-personal-1',
            spaceId: SPACE_ID,
            authorId: 'mock-user-id',
            title: 'Architektur des Projekts',
            useKnowledge: true,
            referencedLibraryIds: [],
            status: 'PRIVATE',
            createdAt: '2026-01-01T00:00:00Z',
            updatedAt: '2026-01-01T00:00:00Z',
          },
        ])
      }),
    )

    const loadPromise = useChatListStore.getState().loadChats(SPACE_ID)
    resetAllStores()
    gate.resolve()
    await loadPromise

    const state = useChatListStore.getState()
    expect(state.chatsBySpaceId[SPACE_ID]).toBeUndefined()
    expect(state.isLoading).toBe(false)
  })
})
