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
  sendMessage: (question: string, workspaceIds?: string[]) => Promise<void>
  clearMessages: () => void
}

export const useChatStore = create<ChatState>((set, get) => ({
  messages: [],
  isLoading: false,
  error: null,
  conversationId: null,

  sendMessage: async (question: string, workspaceIds?: string[]) => {
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
      const response = await sendQuery(question, get().conversationId ?? undefined, workspaceIds)
      const assistantMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: response.answer,
        sources: response.sources,
        timestamp: new Date(),
      }
      set((state) => ({
        messages: [...state.messages, assistantMessage],
        isLoading: false,
        conversationId: response.conversationId,
      }))
    } catch (err) {
      // TODO: Add retry UX (e.g. "Retry" button on failed messages)
      const message = err instanceof Error ? err.message : 'An unexpected error occurred'
      set({ error: message, isLoading: false })
    }
  },

  clearMessages: () => set({ messages: [], error: null, conversationId: null }),
}))
