export interface HealthResponse {
  status: string
}

export type IndexingStatus = 'IDLE' | 'RUNNING' | 'COMPLETED' | 'FAILED'

export interface IndexingStatusResponse {
  status: IndexingStatus
  documentCount: number
  totalDocuments: number
  message: string | null
  timestamp: string
}

export interface QueryRequest {
  question: string
}

export interface QueryResponse {
  answer: string
  sources: SourceReference[]
  metadata: QueryMetadata
}

export interface SourceReference {
  fileName: string
  relevanceScore: number
  excerpt: string
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
