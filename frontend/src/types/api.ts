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
  workspaceIds?: string[]
}

export interface QueryResponse {
  answer: string
  sources: SourceReference[]
  metadata: QueryMetadata
  conversationId: string
}

export interface SourceReference {
  fileName: string
  workspaceName?: string
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

export interface IndexingTriggerRequest {
  url?: string
  proxy?: string
  credentials?: string
  insecureSsl?: boolean
}

export type WorkspaceType = 'PERSONAL' | 'SHARED'

export type WorkspaceRole = 'VIEWER' | 'EDITOR' | 'ADMIN' | 'OWNER'

export interface WorkspaceListResponse {
  id: string
  name: string
  description: string | null
  type: WorkspaceType
  memberCount: number
  userRole: WorkspaceRole
  createdAt: string
  updatedAt: string
}

export interface WorkspaceMemberResponse {
  userId: string
  role: WorkspaceRole
  createdAt: string
}

export interface WorkspaceResponse {
  id: string
  name: string
  description: string | null
  type: WorkspaceType
  ownerId: string
  memberCount: number
  userRole: WorkspaceRole
  roleCounts: Record<WorkspaceRole, number>
  members: WorkspaceMemberResponse[]
  createdAt: string
  updatedAt: string
}

export interface WorkspaceDocumentResponse {
  id: string
  name: string
  type: string
  uploadedAt: string
  ownerDisplayName: string
}

export function isErrorResponse(data: unknown): data is ErrorResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    'error' in data &&
    typeof (data as Record<string, unknown>).error === 'string'
  )
}
