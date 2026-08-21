import type { SourceReference } from './api'

export type MessageRole = 'user' | 'assistant'

export type AccessLevel = 'Public' | 'Internal' | 'Confidential'

export interface ChatMessage {
  id: string
  role: MessageRole
  content: string
  sources?: SourceReference[]
  /** True when the backend answered without any document retrieval (QueryMetadata#526). */
  answeredWithoutKnowledge?: boolean
  /**
   * True for the #203/#706 fail-open case: the chat's space is curated (@Alles-Wissen, at least
   * one library association) but none of the associated libraries are readable by the caller, so
   * the search scope resolved to empty even though the caller never chose "ohne Wissen". Mutually
   * exclusive with answeredWithoutKnowledge.
   */
  noKnowledgeAvailableInSpace?: boolean
  timestamp: Date
}
