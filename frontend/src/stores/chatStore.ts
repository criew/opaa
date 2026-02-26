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
  sendMessage: (question: string) => Promise<void>
  clearMessages: () => void
}

export const useChatStore = create<ChatState>((set) => ({
  messages: [],
  isLoading: false,
  error: null,

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
      const response = await sendQuery(question)
      const assistantMessage: ChatMessage = {
        id: generateId(),
        role: 'assistant',
        content: response.answer,
        sources: response.sources,
        timestamp: new Date(),
      }
      set((state) => ({ messages: [...state.messages, assistantMessage], isLoading: false }))
    } catch (err) {
      // TODO: Add retry UX (e.g. "Retry" button on failed messages)
      const message = err instanceof Error ? err.message : 'An unexpected error occurred'
      set({ error: message, isLoading: false })
    }
  },

  clearMessages: () => set({ messages: [], error: null }),
}))
