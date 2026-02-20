import type { SourceReference } from './api'

export type MessageRole = 'user' | 'assistant'

export type AccessLevel = 'Public' | 'Internal' | 'Confidential'

export interface ChatMessage {
  id: string
  role: MessageRole
  content: string
  sources?: SourceReference[]
  timestamp: Date
}
