export interface HealthResponse {
  status: string
}

export type IndexingStatus = 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface IndexingStatusResponse {
  status: IndexingStatus
  documentCount: number
  totalDocuments: number
  documentsSkipped: number
  message: string | null
  timestamp: string
}

export interface QueryRequest {
  question: string
  conversationId?: string
}

export interface QueryResponse {
  answer: string
  sources: SourceReference[]
  metadata: QueryMetadata
  conversationId: string
}

export interface SourceReference {
  fileName: string
  relevanceScore: number
  matchCount: number
  indexedAt: string | null
  cited: boolean
}

export interface QueryMetadata {
  model: string
  tokenCount: number
  durationMs: number
}

export interface ErrorResponse {
  error: string
  status: number
  timestamp: string
}

export function isErrorResponse(data: unknown): data is ErrorResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    'error' in data &&
    typeof (data as Record<string, unknown>).error === 'string'
  )
}
