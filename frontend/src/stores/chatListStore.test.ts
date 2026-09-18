import { http, HttpResponse } from 'msw'
import { describe, expect, it, beforeEach } from 'vitest'
import { server } from '../mocks/server'
// #575 review: this import's position relative to chatListStore below no longer matters - see the
// matching comment in chatStore.test.ts for why.
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

describe('chatListStore pinning', () => {
  beforeEach(() => {
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
  })

  it('pins through the server and keeps the pin across a reload', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)

    await useChatListStore.getState().setChatPinned(SPACE_ID, 'chat-personal-1', true)
    useChatListStore.setState({ chatsBySpaceId: {} })
    await useChatListStore.getState().loadChats(SPACE_ID)

    const chat = useChatListStore
      .getState()
      .chatsBySpaceId[SPACE_ID]?.find((c) => c.id === 'chat-personal-1')
    expect(chat?.pinnedAt).toBeTruthy()

    await useChatListStore.getState().setChatPinned(SPACE_ID, 'chat-personal-1', false)
    await useChatListStore.getState().loadChats(SPACE_ID)
    expect(
      useChatListStore.getState().chatsBySpaceId[SPACE_ID]?.find((c) => c.id === 'chat-personal-1')
        ?.pinnedAt,
    ).toBeNull()
  })

  it('restores the previous pin and sets an error when unpinning fails', async () => {
    server.use(
      http.delete('/api/v1/chats/:chatId/pin', () =>
        HttpResponse.json({ error: 'Lösen fehlgeschlagen' }, { status: 500 }),
      ),
    )
    await useChatListStore.getState().loadChats(SPACE_ID)
    useChatListStore.setState((state) => ({
      chatsBySpaceId: {
        [SPACE_ID]: state.chatsBySpaceId[SPACE_ID]?.map((c) =>
          c.id === 'chat-personal-1' ? { ...c, pinnedAt: '2026-09-01T08:00:00Z' } : c,
        ),
      },
    }))

    await useChatListStore.getState().setChatPinned(SPACE_ID, 'chat-personal-1', false)

    const state = useChatListStore.getState()
    expect(state.error).toBe('Lösen fehlgeschlagen')
    expect(state.chatsBySpaceId[SPACE_ID]?.find((c) => c.id === 'chat-personal-1')?.pinnedAt).toBe(
      '2026-09-01T08:00:00Z',
    )
  })

  it('upsertChat keeps the pin of an existing entry when the new summary carries none', () => {
    useChatListStore.setState({
      chatsBySpaceId: {
        [SPACE_ID]: [
          {
            id: 'chat-a',
            spaceId: SPACE_ID,
            authorId: 'me',
            title: 'Alt',
            useKnowledge: true,
            status: 'PRIVATE',
            createdAt: '2026-09-01T08:00:00Z',
            updatedAt: '2026-09-01T08:00:00Z',
            pinnedAt: '2026-09-02T08:00:00Z',
          },
        ],
      },
    })

    useChatListStore.getState().upsertChat(SPACE_ID, {
      id: 'chat-a',
      spaceId: SPACE_ID,
      authorId: 'me',
      title: 'Neu',
      useKnowledge: true,
      status: 'PRIVATE',
      createdAt: '2026-09-01T08:00:00Z',
      updatedAt: '2026-09-03T08:00:00Z',
    })

    const chat = useChatListStore.getState().chatsBySpaceId[SPACE_ID]?.[0]
    expect(chat?.title).toBe('Neu')
    expect(chat?.pinnedAt).toBe('2026-09-02T08:00:00Z')
  })
})

describe('chatListStore pin request order', () => {
  beforeEach(() => {
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
  })

  function pinnedAtOf(chatId: string) {
    return useChatListStore.getState().chatsBySpaceId[SPACE_ID]?.find((c) => c.id === chatId)
      ?.pinnedAt
  }

  // Pinning and at once unpinning again: the slow pin answer must neither overtake the unpin on
  // the server nor overwrite what the list shows once it finally arrives.
  it('sends quick pin and unpin requests in order and keeps the last one', async () => {
    const pinGate = deferred<void>()
    const serverOrder: string[] = []
    server.use(
      http.put('/api/v1/chats/:chatId/pin', async () => {
        serverOrder.push('pin')
        await pinGate.promise
        return HttpResponse.json({ id: 'chat-personal-1', pinnedAt: '2026-09-18T09:00:00Z' })
      }),
      http.delete('/api/v1/chats/:chatId/pin', () => {
        serverOrder.push('unpin')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await useChatListStore.getState().loadChats(SPACE_ID)

    const pin = useChatListStore.getState().setChatPinned(SPACE_ID, 'chat-personal-1', true)
    const unpin = useChatListStore.getState().setChatPinned(SPACE_ID, 'chat-personal-1', false)
    expect(pinnedAtOf('chat-personal-1')).toBeNull()
    await waitForRequests(serverOrder, 1)
    expect(serverOrder).toEqual(['pin'])

    pinGate.resolve()
    await Promise.all([pin, unpin])

    expect(serverOrder).toEqual(['pin', 'unpin'])
    expect(pinnedAtOf('chat-personal-1')).toBeNull()
    expect(useChatListStore.getState().error).toBeNull()
  })

  it('rolls a failed latest request back to what the server last confirmed', async () => {
    server.use(
      http.delete('/api/v1/chats/:chatId/pin', () =>
        HttpResponse.json({ error: 'Lösen fehlgeschlagen' }, { status: 500 }),
      ),
      http.put('/api/v1/chats/:chatId/pin', () =>
        HttpResponse.json({ id: 'chat-personal-1', pinnedAt: '2026-09-18T09:00:00Z' }),
      ),
    )
    await useChatListStore.getState().loadChats(SPACE_ID)

    const pin = useChatListStore.getState().setChatPinned(SPACE_ID, 'chat-personal-1', true)
    const unpin = useChatListStore.getState().setChatPinned(SPACE_ID, 'chat-personal-1', false)
    const [pinned, unpinned] = await Promise.all([pin, unpin])

    expect(pinnedAtOf('chat-personal-1')).toBe('2026-09-18T09:00:00Z')
    expect(useChatListStore.getState().error).toBe('Lösen fehlgeschlagen')
    expect(pinned).toBe(true)
    expect(unpinned).toBe(false)
  })
})

/** Waits (bounded) until the handlers have seen `count` requests. */
async function waitForRequests(seen: string[], count: number) {
  for (let attempt = 0; attempt < 50 && seen.length < count; attempt++) {
    await new Promise((resolve) => setTimeout(resolve, 5))
  }
  // Give a request that must not be sent yet the chance to show up.
  await new Promise((resolve) => setTimeout(resolve, 20))
}

describe('chatListStore chat archive', () => {
  beforeEach(() => {
    useChatListStore.setState({
      chatsBySpaceId: {},
      archiveBySpaceId: {},
      isLoading: false,
      isLoadingArchive: false,
      error: null,
    })
  })

  function activeIds() {
    return useChatListStore.getState().chatsBySpaceId[SPACE_ID]?.map((c) => c.id)
  }

  it('moves a chat into the archive and back', async () => {
    await useChatListStore.getState().loadChats(SPACE_ID)
    await useChatListStore.getState().loadArchivedChats(SPACE_ID)

    expect(
      await useChatListStore.getState().setChatArchived(SPACE_ID, 'chat-personal-1', true),
    ).toBe(true)

    expect(activeIds()).toEqual(['chat-personal-2'])
    const archive = useChatListStore.getState().archiveBySpaceId[SPACE_ID]
    expect(archive?.totalElements).toBe(1)
    expect(archive?.items[0].id).toBe('chat-personal-1')
    expect(archive?.items[0].archivedAt).toBeTruthy()

    await useChatListStore.getState().setChatArchived(SPACE_ID, 'chat-personal-1', false)

    expect(activeIds()).toEqual(['chat-personal-2', 'chat-personal-1'])
    expect(useChatListStore.getState().archiveBySpaceId[SPACE_ID]?.totalElements).toBe(0)
  })

  // Pinning brings a chat back from the archive on the server; an archive request that overtook a
  // slow pin would leave the chat pinned and active while the list shows it archived.
  it('sends an archive request only after a pin request still under way for the chat', async () => {
    const pinGate = deferred<void>()
    const serverOrder: string[] = []
    server.use(
      http.put('/api/v1/chats/:chatId/pin', async () => {
        serverOrder.push('pin')
        await pinGate.promise
        return HttpResponse.json({ id: 'chat-personal-1', pinnedAt: '2026-09-18T09:00:00Z' })
      }),
      http.put('/api/v1/chats/:chatId/archive', () => {
        serverOrder.push('archive')
        return HttpResponse.json({ id: 'chat-personal-1', archivedAt: '2026-09-18T09:01:00Z' })
      }),
    )
    await useChatListStore.getState().loadChats(SPACE_ID)

    const pin = useChatListStore.getState().setChatPinned(SPACE_ID, 'chat-personal-1', true)
    const archive = useChatListStore.getState().setChatArchived(SPACE_ID, 'chat-personal-1', true)
    await waitForRequests(serverOrder, 1)
    expect(serverOrder).toEqual(['pin'])

    pinGate.resolve()
    await Promise.all([pin, archive])

    expect(serverOrder).toEqual(['pin', 'archive'])
    expect(activeIds()).toEqual(['chat-personal-2'])
  })

  it('keeps the chat and sets an error when archiving fails', async () => {
    server.use(
      http.put('/api/v1/chats/:chatId/archive', () =>
        HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 }),
      ),
    )
    await useChatListStore.getState().loadChats(SPACE_ID)

    expect(
      await useChatListStore.getState().setChatArchived(SPACE_ID, 'chat-personal-1', true),
    ).toBe(false)

    expect(activeIds()).toEqual(['chat-personal-2', 'chat-personal-1'])
    expect(useChatListStore.getState().error).toBe('Chat nicht gefunden')
  })

  it('applies a bulk action and reloads both lists', async () => {
    let body: unknown = null
    server.use(
      http.post('/api/v1/spaces/:spaceId/chats/bulk-actions', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ chatIds: ['chat-personal-1'] })
      }),
    )

    const applied = await useChatListStore
      .getState()
      .applyBulkAction(SPACE_ID, 'ARCHIVE', ['chat-personal-1', 'fremd'])

    expect(body).toEqual({ action: 'ARCHIVE', chatIds: ['chat-personal-1', 'fremd'] })
    expect(applied).toEqual(['chat-personal-1'])
    expect(useChatListStore.getState().chatsBySpaceId[SPACE_ID]).toBeDefined()
    expect(useChatListStore.getState().archiveBySpaceId[SPACE_ID]).toBeDefined()
  })

  it('falls back to the last page when the shown archive page has emptied out', async () => {
    const requestedPages: number[] = []
    server.use(
      http.get('/api/v1/spaces/:spaceId/chats/archived', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'))
        requestedPages.push(page)
        return HttpResponse.json({
          items: page === 0 ? [{ id: 'chat-personal-1' }] : [],
          page,
          size: 50,
          totalElements: 1,
        })
      }),
    )

    await useChatListStore.getState().loadArchivedChats(SPACE_ID, 1)

    expect(requestedPages).toEqual([1, 0])
    expect(useChatListStore.getState().archiveBySpaceId[SPACE_ID]?.page).toBe(0)
  })
})
