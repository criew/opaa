import { create } from 'zustand'
import type { ChatSummary } from '../types/api'
import { createChat, deleteChat, listSpaceChats, updateChat } from '../services/api'

interface ChatListState {
  /** Chats per space, sorted by last use (most recently updated first). Undefined means "not
   * loaded yet" for that space - distinct from an empty array, which means "loaded, no chats". */
  chatsBySpaceId: Record<string, ChatSummary[] | undefined>
  isLoading: boolean
  error: string | null
  loadChats: (spaceId: string) => Promise<void>
  /** Returns null (and sets `error`) when creation fails, instead of throwing - callers must
   * handle the null case explicitly rather than relying on a rejected promise. */
  createChatInSpace: (spaceId: string) => Promise<ChatSummary | null>
  renameChat: (spaceId: string, chatId: string, title: string) => Promise<void>
  deleteChatFromList: (spaceId: string, chatId: string) => Promise<void>
  /** Inserts a chat into its space's list (or replaces an existing entry with the same id) and
   * re-sorts by last use - used by chatStore to make an implicitly created chat show up in the
   * list without a full reload (#548 review, finding 4). */
  upsertChat: (spaceId: string, chat: ChatSummary) => void
  /** Bumps an existing entry's updatedAt and re-sorts, so a chat moves to the top of its list
   * after every turn - a no-op if the chat isn't in the (possibly not yet loaded) list. */
  touchChat: (spaceId: string, chatId: string, updatedAt: string) => void
  /** Applies a title change - either the immediate fallback QueryResponse#chatTitle carries, or
   * the LLM-derived title chatStore's delayed reload picks up (#557) - to a chat already in the
   * list. A no-op if the chat isn't in the (possibly not yet loaded) list. */
  updateChatTitle: (spaceId: string, chatId: string, title: string | null) => void
  reset: () => void
}

function sortByLastUse(chats: ChatSummary[]): ChatSummary[] {
  return [...chats].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
}

function toChatSummary(detail: {
  id: string
  spaceId: string
  authorId: string
  title?: string | null
  useKnowledge: boolean
  referencedLibraryIds?: string[]
  status: ChatSummary['status']
  createdAt: string
  updatedAt: string
}): ChatSummary {
  return {
    id: detail.id,
    spaceId: detail.spaceId,
    authorId: detail.authorId,
    title: detail.title ?? null,
    useKnowledge: detail.useKnowledge,
    referencedLibraryIds: detail.referencedLibraryIds ?? [],
    status: detail.status,
    createdAt: detail.createdAt,
    updatedAt: detail.updatedAt,
  }
}

export const useChatListStore = create<ChatListState>((set) => ({
  chatsBySpaceId: {},
  isLoading: false,
  error: null,

  reset: () => set({ chatsBySpaceId: {}, isLoading: false, error: null }),

  loadChats: async (spaceId: string) => {
    set({ isLoading: true, error: null })
    try {
      const chats = sortByLastUse(await listSpaceChats(spaceId))
      set((state) => ({
        chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: chats },
        isLoading: false,
      }))
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Chats konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  createChatInSpace: async (spaceId: string) => {
    try {
      const detail = await createChat(spaceId)
      const summary = toChatSummary(detail)
      set((state) => ({
        chatsBySpaceId: {
          ...state.chatsBySpaceId,
          [spaceId]: sortByLastUse([...(state.chatsBySpaceId[spaceId] ?? []), summary]),
        },
      }))
      return summary
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Chat konnte nicht erstellt werden'
      set({ error: message })
      return null
    }
  },

  renameChat: async (spaceId: string, chatId: string, title: string) => {
    try {
      await updateChat(chatId, { title })
      set((state) => {
        const chats = state.chatsBySpaceId[spaceId]
        if (!chats) return state
        return {
          chatsBySpaceId: {
            ...state.chatsBySpaceId,
            [spaceId]: chats.map((chat) => (chat.id === chatId ? { ...chat, title } : chat)),
          },
        }
      })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Chat konnte nicht umbenannt werden'
      set({ error: message })
    }
  },

  deleteChatFromList: async (spaceId: string, chatId: string) => {
    try {
      await deleteChat(chatId)
      set((state) => {
        const chats = state.chatsBySpaceId[spaceId]
        if (!chats) return state
        return {
          chatsBySpaceId: {
            ...state.chatsBySpaceId,
            [spaceId]: chats.filter((chat) => chat.id !== chatId),
          },
        }
      })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Chat konnte nicht gelöscht werden'
      set({ error: message })
    }
  },

  upsertChat: (spaceId: string, chat: ChatSummary) =>
    set((state) => {
      const existing = state.chatsBySpaceId[spaceId] ?? []
      const next = existing.some((c) => c.id === chat.id)
        ? existing.map((c) => (c.id === chat.id ? chat : c))
        : [...existing, chat]
      return { chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: sortByLastUse(next) } }
    }),

  touchChat: (spaceId: string, chatId: string, updatedAt: string) =>
    set((state) => {
      const existing = state.chatsBySpaceId[spaceId]
      if (!existing || !existing.some((chat) => chat.id === chatId)) return state
      const next = existing.map((chat) => (chat.id === chatId ? { ...chat, updatedAt } : chat))
      return { chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: sortByLastUse(next) } }
    }),

  updateChatTitle: (spaceId: string, chatId: string, title: string | null) =>
    set((state) => {
      const existing = state.chatsBySpaceId[spaceId]
      if (!existing || !existing.some((chat) => chat.id === chatId)) return state
      const next = existing.map((chat) => (chat.id === chatId ? { ...chat, title } : chat))
      return { chatsBySpaceId: { ...state.chatsBySpaceId, [spaceId]: next } }
    }),
}))
