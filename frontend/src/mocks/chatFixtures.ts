import type {
  ChatDetail,
  ChatSummary,
  ChatSearchHighlight,
  ChatSearchHit,
  ChatSearchRequest,
  ChatSearchResponse,
} from '../types/api'
import { mockQueryResponses } from './queryFixtures'

const INITIAL_CHAT_DETAILS: Record<string, ChatDetail> = {
  'chat-personal-1': {
    id: 'chat-personal-1',
    spaceId: 'space-personal',
    authorId: 'mock-user-id',
    title: 'Architektur des Projekts',
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    messages: [
      {
        id: 'message-personal-1-1',
        chatId: 'chat-personal-1',
        role: 'USER',
        content: 'Wie ist das Projekt aufgebaut?',
        createdAt: '2026-03-05T09:00:00Z',
      },
      {
        id: 'message-personal-1-2',
        chatId: 'chat-personal-1',
        role: 'ASSISTANT',
        content: mockQueryResponses[0].answer,
        sources: mockQueryResponses[0].sources,
        createdAt: '2026-03-05T09:00:05Z',
      },
    ],
    noteItems: [],
    createdAt: '2026-03-05T09:00:00Z',
    updatedAt: '2026-03-05T09:00:05Z',
  },
  'chat-personal-2': {
    id: 'chat-personal-2',
    spaceId: 'space-personal',
    authorId: 'mock-user-id',
    title: 'Deployment-Fragen',
    useKnowledge: false,
    referencedLibraryIds: ['library-referat-50'],
    status: 'PRIVATE',
    messages: [
      {
        id: 'message-personal-2-1',
        chatId: 'chat-personal-2',
        role: 'USER',
        content: 'Wie läuft das Deployment ab?',
        createdAt: '2026-03-06T11:00:00Z',
      },
      {
        id: 'message-personal-2-2',
        chatId: 'chat-personal-2',
        role: 'ASSISTANT',
        content: mockQueryResponses[2].answer,
        sources: mockQueryResponses[2].sources,
        createdAt: '2026-03-06T11:00:05Z',
      },
    ],
    noteItems: [],
    createdAt: '2026-03-06T11:00:00Z',
    updatedAt: '2026-03-06T11:00:05Z',
  },
  'chat-engineering-1': {
    id: 'chat-engineering-1',
    spaceId: 'space-engineering',
    authorId: 'mock-user-id',
    title: null,
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    messages: [],
    noteItems: [],
    createdAt: '2026-03-07T08:00:00Z',
    updatedAt: '2026-03-07T08:00:00Z',
  },
  // Three completed rounds and two note points - the one fixture that actually shows the
  // Gesprächsnotiz (#1488), whose button needs both.
  'chat-engineering-2': {
    id: 'chat-engineering-2',
    spaceId: 'space-engineering',
    authorId: 'mock-user-id',
    title: 'Anwohnerparkausweis Nebenstelle 3',
    useKnowledge: true,
    referencedLibraryIds: [],
    status: 'PRIVATE',
    messages: [
      {
        id: 'message-engineering-2-1',
        chatId: 'chat-engineering-2',
        role: 'USER',
        content: 'Ich arbeite im Bürgerbüro Nebenstelle 3. Was kostet ein Anwohnerparkausweis?',
        createdAt: '2026-03-08T09:00:00Z',
      },
      {
        id: 'message-engineering-2-2',
        chatId: 'chat-engineering-2',
        role: 'ASSISTANT',
        content: 'Ein Anwohnerparkausweis kostet 30,70 Euro pro Jahr.',
        createdAt: '2026-03-08T09:00:05Z',
      },
      {
        id: 'message-engineering-2-3',
        chatId: 'chat-engineering-2',
        role: 'USER',
        content: 'Es geht um das Bezugsjahr 2024. Und bei Bedürftigkeit?',
        createdAt: '2026-03-08T09:01:00Z',
      },
      {
        id: 'message-engineering-2-4',
        chatId: 'chat-engineering-2',
        role: 'ASSISTANT',
        content: 'Bei nachgewiesener Bedürftigkeit kann die Gebühr ermäßigt werden.',
        createdAt: '2026-03-08T09:01:05Z',
      },
      {
        id: 'message-engineering-2-5',
        chatId: 'chat-engineering-2',
        role: 'USER',
        content: 'Welche Nachweise brauche ich dafür?',
        createdAt: '2026-03-08T09:02:00Z',
      },
      {
        id: 'message-engineering-2-6',
        chatId: 'chat-engineering-2',
        role: 'ASSISTANT',
        content: 'Vorzulegen sind der Bescheid über die laufende Leistung und ein Meldenachweis.',
        createdAt: '2026-03-08T09:02:05Z',
      },
    ],
    noteItems: [
      {
        id: '3f1b6d64-4a3c-4f2e-9b1a-0c9d8e7f6a51',
        text: 'Arbeitet im Bürgerbüro Nebenstelle 3',
        kind: 'RAHMEN',
        createdAt: '2026-03-08T09:00:10Z',
      },
      {
        id: '5c2e8a17-9d44-4f81-b3c7-2a6f4e0d1b93',
        text: 'Bezugsjahr 2024',
        kind: 'RAHMEN',
        createdAt: '2026-03-08T09:01:10Z',
      },
    ],
    createdAt: '2026-03-08T09:00:00Z',
    updatedAt: '2026-03-08T09:02:05Z',
  },
}

/** The mock person's pins, by chat id - a personal mark, kept apart from the chat itself. */
export let mockChatPins: Record<string, string> = {}

/** The mock person's chat archive, by chat id - a personal mark like the pins. */
export let mockChatArchive: Record<string, string> = {}

export function toChatSummary(detail: ChatDetail): ChatSummary {
  return {
    id: detail.id,
    spaceId: detail.spaceId,
    authorId: detail.authorId,
    title: detail.title,
    useKnowledge: detail.useKnowledge,
    referencedLibraryIds: detail.referencedLibraryIds,
    status: detail.status,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
    pinnedAt: mockChatPins[detail.id] ?? null,
    archivedAt: mockChatArchive[detail.id] ?? null,
  }
}

// Mutable copies, mirroring the mockLibraryDocuments pattern - the handlers read and write
// these on GET/POST/PATCH/DELETE, reset between tests via resetMockChats().
export let mockChatDetails: Record<string, ChatDetail> = structuredClone(INITIAL_CHAT_DETAILS)

/** The space's active chats - those not in the mock person's chat archive. */
export function mockChatsForSpace(spaceId: string): ChatSummary[] {
  return Object.values(mockChatDetails)
    .filter((chat) => chat.spaceId === spaceId && !mockChatArchive[chat.id])
    .map(toChatSummary)
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
}

/** The space's archived chats, most recently archived first. */
export function mockArchivedChatsForSpace(spaceId: string): ChatSummary[] {
  return Object.values(mockChatDetails)
    .filter((chat) => chat.spaceId === spaceId && mockChatArchive[chat.id])
    .map(toChatSummary)
    .sort((a, b) => (b.archivedAt ?? '').localeCompare(a.archivedAt ?? ''))
}

const MOCK_EXCERPT_RADIUS = 60

/** The excerpt around the first match of `words` in `text`, with every match in it highlighted. */
function mockExcerpt(text: string, words: string[]): Pick<ChatSearchHit, 'excerpt' | 'highlights'> {
  const lower = text.toLocaleLowerCase('de')
  const first = lower.indexOf(words[0])
  const from = Math.max(0, first - MOCK_EXCERPT_RADIUS)
  const to = Math.min(text.length, first + words[0].length + MOCK_EXCERPT_RADIUS)
  const excerpt = text.slice(from, to)
  const excerptLower = lower.slice(from, to)
  const ranges: ChatSearchHighlight[] = []
  for (const word of words) {
    let index = excerptLower.indexOf(word)
    while (index >= 0) {
      ranges.push({ start: index, end: index + word.length })
      index = excerptLower.indexOf(word, index + word.length)
    }
  }
  ranges.sort((a, b) => a.start - b.start)
  const highlights = ranges.filter((range, i) => i === 0 || range.start >= ranges[i - 1].end)
  return { excerpt, highlights }
}

/**
 * Mirrors ChatService#searchChats on the mock data: all words of the term in one message (or the
 * title), one hit per chat at its best message, archived chats included. Ordered like the backend
 * by relevance - here the number of matched words - and on a tie by the time of the hit, newest
 * first; a chat matching by its title alone counts with its last activity.
 */
export function mockSearchChats(spaceId: string, request: ChatSearchRequest): ChatSearchResponse {
  const words = request.query.trim().toLocaleLowerCase('de').split(/\s+/).filter(Boolean)
  const rankOf = (text: string | null | undefined): number => {
    if (text == null) return 0
    const lower = text.toLocaleLowerCase('de')
    if (!words.every((word) => lower.includes(word))) return 0
    return words.reduce((sum, word) => sum + lower.split(word).length - 1, 0)
  }
  const ranked: Array<{ hit: ChatSearchHit; rank: number; hitAt: string }> = []
  for (const chat of Object.values(mockChatDetails)) {
    if (chat.spaceId !== spaceId) continue
    const archivedAt = mockChatArchive[chat.id] ?? null
    // Best message by rank, the later one on a tie - as the backend's DISTINCT ON per chat.
    let best: { message: (typeof chat.messages)[number]; rank: number } | null = null
    for (const message of chat.messages) {
      const rank = rankOf(message.content)
      if (rank > 0 && (best === null || rank >= best.rank)) best = { message, rank }
    }
    const titleRank = rankOf(chat.title)
    if (best) {
      ranked.push({
        hit: {
          chatId: chat.id,
          title: chat.title,
          archivedAt,
          messageId: best.message.id,
          role: best.message.role,
          messageCreatedAt: best.message.createdAt,
          ...mockExcerpt(best.message.content, words),
        },
        rank: Math.max(best.rank, titleRank),
        hitAt: best.message.createdAt,
      })
    } else if (titleRank > 0) {
      ranked.push({
        hit: { chatId: chat.id, title: chat.title, archivedAt, ...mockExcerpt(chat.title!, words) },
        rank: titleRank,
        hitAt: chat.updatedAt,
      })
    }
  }
  ranked.sort(
    (a, b) =>
      b.rank - a.rank || b.hitAt.localeCompare(a.hitAt) || a.hit.chatId.localeCompare(b.hit.chatId),
  )
  const hits = ranked.map((entry) => entry.hit)
  const page = request.page ?? 0
  const size = Math.min(request.pageSize ?? 20, 50)
  return {
    hits: hits.slice(page * size, (page + 1) * size),
    hasMore: hits.length > (page + 1) * size,
  }
}

export function resetMockChats() {
  mockChatDetails = structuredClone(INITIAL_CHAT_DETAILS)
  mockChatPins = {}
  mockChatArchive = {}
}
