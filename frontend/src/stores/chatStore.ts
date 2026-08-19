import { create } from 'zustand'
import type { ChatMessage } from '../types/chat'
import type { ChatDetail, ChatMessageResponse, SourceReference } from '../types/api'
import { createChat, getChat, sendQuery, updateChat } from '../services/api'

function generateId(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

function toChatMessage(message: ChatMessageResponse): ChatMessage {
  return {
    id: message.id,
    role: message.role === 'USER' ? 'user' : 'assistant',
    content: message.content,
    // Mirrors the QueryResponse#sources normalization in types/api.ts: the generated schema
    // leaves indexedAt optional, the frontend's own SourceReference always carries it (null when
    // unknown) - persisted messages go through the same shape as a fresh query response.
    sources: message.sources?.map((source): SourceReference => ({
      ...source,
      indexedAt: source.indexedAt ?? null,
    })),
    timestamp: new Date(message.createdAt),
  }
}

interface ChatState {
  /** The space the active (or about-to-be-created) chat lives in - null before any space is
   * known, e.g. right after login before ChatRedirect has resolved a default space. */
  spaceId: string | null
  /** The persisted chat's id (#525/#527), or null for a not-yet-created chat: the first sent
   * message creates it implicitly in `spaceId` (see sendMessage). */
  chatId: string | null
  title: string | null
  messages: ChatMessage[]
  /** True while a question/answer round-trip (and, for the first message, chat creation) is in
   * flight. */
  isLoading: boolean
  /** True while an existing chat's history is being fetched via loadChat. */
  isLoadingChat: boolean
  error: string | null
  /** Whether the search scope includes the knowledge base at all (#528, backend default: true). */
  useKnowledge: boolean
  // Sticky per-chat @-references (#523/#528). Persisted via PATCH /api/v1/chats/{chatId} once a
  // chat exists (see setUseKnowledge/addReferencedLibrary/removeReferencedLibrary); before that,
  // they only shape the first message's implicit chat creation.
  referencedLibraryIds: string[]
  loadChat: (chatId: string) => Promise<void>
  startNewChat: (spaceId: string) => void
  sendMessage: (question: string) => Promise<void>
  setUseKnowledge: (useKnowledge: boolean) => void
  addReferencedLibrary: (libraryId: string) => void
  removeReferencedLibrary: (libraryId: string) => void
}

function applyChatDetail(detail: ChatDetail) {
  return {
    spaceId: detail.spaceId,
    chatId: detail.id,
    title: detail.title ?? null,
    useKnowledge: detail.useKnowledge,
    referencedLibraryIds: detail.referencedLibraryIds ?? [],
    messages: detail.messages.map(toChatMessage),
  }
}

export const useChatStore = create<ChatState>((set, get) => ({
  spaceId: null,
  chatId: null,
  title: null,
  messages: [],
  isLoading: false,
  isLoadingChat: false,
  error: null,
  useKnowledge: true,
  referencedLibraryIds: [],

  loadChat: async (chatId: string) => {
    set({ isLoadingChat: true, error: null })
    try {
      const detail = await getChat(chatId)
      set({ ...applyChatDetail(detail), isLoadingChat: false })
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Chat konnte nicht geladen werden'
      set({ error: message, isLoadingChat: false })
    }
  },

  // A new chat resets the sticky knowledge-scope controls too - they belong to the conversation
  // that gets started here, not to whichever chat was open before. No API call yet: the chat is
  // only persisted once the first message is sent (see sendMessage).
  startNewChat: (spaceId: string) =>
    set({
      spaceId,
      chatId: null,
      title: null,
      messages: [],
      error: null,
      useKnowledge: true,
      referencedLibraryIds: [],
    }),

  sendMessage: async (question: string) => {
    const userMessage: ChatMessage = {
      id: generateId(),
      role: 'user',
      content: question,
      timestamp: new Date(),
    }

    set((state) => ({
      messages: [...state.messages, userMessage],
      isLoading: true,
      error: null,
    }))

    try {
      let { chatId } = get()
      const { spaceId, useKnowledge, referencedLibraryIds } = get()
      if (!chatId) {
        if (!spaceId) {
          throw new Error('Kein Space für den neuen Chat ausgewählt')
        }
        const created = await createChat(spaceId, { useKnowledge, referencedLibraryIds })
        chatId = created.id
        set({ chatId })
      }

      const response = await sendQuery(question, chatId, useKnowledge, referencedLibraryIds)
      const assistantMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: response.answer,
        sources: response.sources,
        answeredWithoutKnowledge: response.metadata.answeredWithoutKnowledge ?? false,
        timestamp: new Date(),
      }
      set((state) => ({
        messages: [...state.messages, assistantMessage],
        isLoading: false,
        chatId: response.chatId,
      }))
    } catch (err) {
      // TODO: Add retry UX (e.g. "Retry" button on failed messages)
      const message = err instanceof Error ? err.message : 'Ein unerwarteter Fehler ist aufgetreten'
      set({ error: message, isLoading: false })
    }
  },

  setUseKnowledge: (useKnowledge: boolean) => {
    set({ useKnowledge })
    const { chatId } = get()
    if (chatId) {
      void updateChat(chatId, { useKnowledge })
    }
  },

  addReferencedLibrary: (libraryId: string) => {
    set((state) =>
      state.referencedLibraryIds.includes(libraryId)
        ? state
        : { referencedLibraryIds: [...state.referencedLibraryIds, libraryId] },
    )
    const { chatId, referencedLibraryIds } = get()
    if (chatId) {
      void updateChat(chatId, { referencedLibraryIds })
    }
  },

  removeReferencedLibrary: (libraryId: string) => {
    set((state) => ({
      referencedLibraryIds: state.referencedLibraryIds.filter((id) => id !== libraryId),
    }))
    const { chatId, referencedLibraryIds } = get()
    if (chatId) {
      void updateChat(chatId, { referencedLibraryIds })
    }
  },
}))
