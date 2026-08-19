import { create } from 'zustand'
import type { ChatMessage } from '../types/chat'
import { sendQuery } from '../services/api'

function generateId(): string {
  return crypto.randomUUID?.() ?? `${Date.now()}-${Math.random().toString(36).slice(2, 11)}`
}

interface ChatState {
  messages: ChatMessage[]
  isLoading: boolean
  error: string | null
  conversationId: string | null
  /** Whether the search scope includes the knowledge base at all (#528, backend default: true). */
  useKnowledge: boolean
  // Sticky per-chat @-references (#523/#528). Kept as plain store state behind this same
  // interface for now; #525/#527 will back it with PATCH /api/v1/chats/{chatId} once chat
  // persistence lands, without callers of these actions needing to change.
  referencedLibraryIds: string[]
  sendMessage: (question: string) => Promise<void>
  clearMessages: () => void
  setUseKnowledge: (useKnowledge: boolean) => void
  addReferencedLibrary: (libraryId: string) => void
  removeReferencedLibrary: (libraryId: string) => void
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  isLoading: false,
  error: null,
  conversationId: null,
  useKnowledge: true,
  referencedLibraryIds: [],

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
      const { useKnowledge, referencedLibraryIds } = get()
      const response = await sendQuery(
        question,
        get().conversationId ?? undefined,
        useKnowledge,
        referencedLibraryIds,
      )
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
        conversationId: response.conversationId,
      }))
    } catch (err) {
      // TODO: Add retry UX (e.g. "Retry" button on failed messages)
      const message = err instanceof Error ? err.message : 'Ein unerwarteter Fehler ist aufgetreten'
      set({ error: message, isLoading: false })
    }
  },

  // A new chat also resets the sticky knowledge-scope controls - they belong to the conversation
  // that gets cleared here, not to the next one.
  clearMessages: () =>
    set({
      messages: [],
      error: null,
      conversationId: null,
      useKnowledge: true,
      referencedLibraryIds: [],
    }),

  setUseKnowledge: (useKnowledge: boolean) => set({ useKnowledge }),

  addReferencedLibrary: (libraryId: string) =>
    set((state) =>
      state.referencedLibraryIds.includes(libraryId)
        ? state
        : { referencedLibraryIds: [...state.referencedLibraryIds, libraryId] },
    ),

  removeReferencedLibrary: (libraryId: string) =>
    set((state) => ({
      referencedLibraryIds: state.referencedLibraryIds.filter((id) => id !== libraryId),
    })),
}))
