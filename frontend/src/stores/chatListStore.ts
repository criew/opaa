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
  createChatInSpace: (spaceId: string) => Promise<ChatSummary>
  renameChat: (spaceId: string, chatId: string, title: string) => Promise<void>
  deleteChatFromList: (spaceId: string, chatId: string) => Promise<void>
  reset: () => void
}

function sortByLastUse(chats: ChatSummary[]): ChatSummary[] {
  return [...chats].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt))
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
    const detail = await createChat(spaceId)
    const summary: ChatSummary = {
      id: detail.id,
      spaceId: detail.spaceId,
      authorId: detail.authorId,
      title: detail.title,
      useKnowledge: detail.useKnowledge,
      referencedLibraryIds: detail.referencedLibraryIds,
      status: detail.status,
      createdAt: detail.createdAt,
      updatedAt: detail.updatedAt,
    }
    set((state) => ({
      chatsBySpaceId: {
        ...state.chatsBySpaceId,
        [spaceId]: sortByLastUse([...(state.chatsBySpaceId[spaceId] ?? []), summary]),
      },
    }))
    return summary
  },

  renameChat: async (spaceId: string, chatId: string, title: string) => {
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
  },

  deleteChatFromList: async (spaceId: string, chatId: string) => {
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
  },
}))
