import { http, HttpResponse } from 'msw'
import { mockUser } from './authFixtures'
import { mockSpaceDetails } from './spaceFixtures'
import {
  mockChatDetails,
  mockChatPins,
  mockChatArchive,
  mockChatsForSpace,
  mockArchivedChatsForSpace,
  mockSearchChats,
  toChatSummary,
  resetMockChats,
} from './chatFixtures'
import type { ChatCreateRequest, ChatSearchRequest, ChatUpdateRequest } from '../types/api'

export function resetChatMockState() {
  resetMockChats()
}

export const chatHandlers = [
  // Mirrors ChatController/ChatService: chats are author-exclusive, listed per space and
  // sorted by last use - mockChatsForSpace already returns them sorted by updatedAt desc.
  http.get('/api/v1/spaces/:spaceId/chats', ({ params }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json(mockChatsForSpace(spaceId))
  }),

  http.post('/api/v1/spaces/:spaceId/chats', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = ((await request.json().catch(() => null)) ?? {}) as ChatCreateRequest
    const id = `chat-${crypto.randomUUID().slice(0, 8)}`
    const now = new Date().toISOString()
    mockChatDetails[id] = {
      id,
      spaceId,
      authorId: mockUser.id,
      title: body.title ?? null,
      useKnowledge: body.useKnowledge ?? true,
      referencedLibraryIds: body.referencedLibraryIds ?? [],
      metadataFilter: body.metadataFilter ?? null,
      status: 'PRIVATE',
      messages: [],
      noteItems: [],
      createdAt: now,
      updatedAt: now,
    }
    return HttpResponse.json(mockChatDetails[id], { status: 201 })
  }),

  http.get('/api/v1/chats/:chatId', ({ params }) => {
    const chatId = String(params.chatId)
    const chat = mockChatDetails[chatId]
    if (!chat) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    return HttpResponse.json({ ...chat, archivedAt: mockChatArchive[chatId] ?? null })
  }),

  http.patch('/api/v1/chats/:chatId', async ({ params, request }) => {
    const chatId = String(params.chatId)
    const chat = mockChatDetails[chatId]
    if (!chat) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as ChatUpdateRequest
    if (body.title !== undefined) chat.title = body.title
    if (body.useKnowledge !== undefined && body.useKnowledge !== null) {
      chat.useKnowledge = body.useKnowledge
    }
    if (body.referencedLibraryIds !== undefined && body.referencedLibraryIds !== null) {
      chat.referencedLibraryIds = body.referencedLibraryIds
    }
    // omitted/null leaves the filter unchanged, an object without any condition clears it.
    if (body.metadataFilter !== undefined && body.metadataFilter !== null) {
      const filter = body.metadataFilter
      const empty =
        (filter.documentTypes ?? []).length === 0 &&
        !filter.documentDateFrom &&
        !filter.documentDateTo
      chat.metadataFilter = empty ? null : filter
    }
    chat.updatedAt = new Date().toISOString()
    return HttpResponse.json(chat)
  }),

  // Mirrors ChatController#pinChat/#unpinChat: a personal mark that leaves the chat, including its
  // updatedAt, untouched; pinning twice keeps the first pinnedAt.
  http.put('/api/v1/chats/:chatId/pin', ({ params }) => {
    const chatId = String(params.chatId)
    const chat = mockChatDetails[chatId]
    if (!chat) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    mockChatPins[chatId] ??= new Date().toISOString()
    delete mockChatArchive[chatId]
    return HttpResponse.json(toChatSummary(chat))
  }),

  http.delete('/api/v1/chats/:chatId/pin', ({ params }) => {
    const chatId = String(params.chatId)
    if (!mockChatDetails[chatId]) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    delete mockChatPins[chatId]
    return new HttpResponse(null, { status: 204 })
  }),

  // Mirrors ChatController#archiveChat/#unarchiveChat: the chat archive is a personal filing that
  // unpins the chat and leaves the chat itself - including its updatedAt - untouched.
  http.put('/api/v1/chats/:chatId/archive', ({ params }) => {
    const chatId = String(params.chatId)
    const chat = mockChatDetails[chatId]
    if (!chat) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    mockChatArchive[chatId] ??= new Date().toISOString()
    delete mockChatPins[chatId]
    return HttpResponse.json(toChatSummary(chat))
  }),

  http.delete('/api/v1/chats/:chatId/archive', ({ params }) => {
    const chatId = String(params.chatId)
    const chat = mockChatDetails[chatId]
    if (!chat) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    delete mockChatArchive[chatId]
    return HttpResponse.json(toChatSummary(chat))
  }),

  http.get('/api/v1/spaces/:spaceId/chats/archived', ({ params, request }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 25)
    const archived = mockArchivedChatsForSpace(spaceId)
    return HttpResponse.json({
      items: archived.slice(page * size, (page + 1) * size),
      page,
      size,
      totalElements: archived.length,
    })
  }),

  // Mirrors ChatController#searchSpaceChats: the term travels in the body, 3 to 200 characters.
  http.post('/api/v1/spaces/:spaceId/chats/search', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as ChatSearchRequest
    const length = [...(body.query ?? '').trim()].length
    if (length < 3 || length > 200) {
      return HttpResponse.json(
        {
          error:
            length < 3
              ? 'Der Suchbegriff muss mindestens 3 Zeichen lang sein'
              : 'Der Suchbegriff darf höchstens 200 Zeichen lang sein',
        },
        { status: 400 },
      )
    }
    return HttpResponse.json(mockSearchChats(spaceId, body))
  }),

  // Mirrors ChatController#applyChatBulkAction: only the space's chats are affected, every other
  // id is skipped without an error.
  http.post('/api/v1/spaces/:spaceId/chats/bulk-actions', async ({ params, request }) => {
    const spaceId = String(params.spaceId)
    if (!mockSpaceDetails[spaceId]) {
      return HttpResponse.json({ error: 'Space nicht gefunden' }, { status: 404 })
    }
    const body = (await request.json()) as { action: string; chatIds: string[] }
    const applied = body.chatIds.filter((id) => mockChatDetails[id]?.spaceId === spaceId)
    const now = new Date().toISOString()
    for (const id of applied) {
      if (body.action === 'ARCHIVE') {
        mockChatArchive[id] ??= now
        delete mockChatPins[id]
      } else if (body.action === 'UNARCHIVE') {
        delete mockChatArchive[id]
      } else {
        delete mockChatDetails[id]
        delete mockChatArchive[id]
        delete mockChatPins[id]
      }
    }
    return HttpResponse.json({ chatIds: applied })
  }),

  // Mirrors ChatController#deleteChatNoteItem (#1487): removing one point of the Gesprächsnotiz,
  // immediately and without a confirmation step.
  http.delete('/api/v1/chats/:chatId/note-items/:itemId', ({ params }) => {
    const chatId = String(params.chatId)
    const itemId = String(params.itemId)
    const chat = mockChatDetails[chatId]
    if (!chat?.noteItems?.some((item) => item.id === itemId)) {
      return HttpResponse.json({ error: 'Notizpunkt nicht gefunden' }, { status: 404 })
    }
    chat.noteItems = chat.noteItems.filter((item) => item.id !== itemId)
    return new HttpResponse(null, { status: 204 })
  }),

  http.delete('/api/v1/chats/:chatId', ({ params }) => {
    const chatId = String(params.chatId)
    if (!mockChatDetails[chatId]) {
      return HttpResponse.json({ error: 'Chat nicht gefunden' }, { status: 404 })
    }
    delete mockChatDetails[chatId]
    return new HttpResponse(null, { status: 204 })
  }),
]
