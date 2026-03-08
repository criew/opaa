import type { components } from './generated/api'

export type HealthResponse = components['schemas']['HealthResponse']
export type IndexingStatus = components['schemas']['IndexingStatus']
export type IndexingStatusResponse = components['schemas']['IndexingStatusResponse']
export type QueryRequest = components['schemas']['QueryRequest']
export type QueryMetadata = components['schemas']['QueryMetadata']
export type ErrorResponse = components['schemas']['ErrorResponse']

export type WorkspaceRole = components['schemas']['WorkspaceRole']
export type WorkspaceType = components['schemas']['WorkspaceType']
export type WorkspaceRequest = components['schemas']['WorkspaceRequest']
export type WorkspaceMemberRequest = components['schemas']['WorkspaceMemberRequest']
export type WorkspaceListResponse = components['schemas']['WorkspaceListResponse']
export type WorkspaceMemberResponse = components['schemas']['WorkspaceMemberResponse']
export type WorkspaceResponse = components['schemas']['WorkspaceResponse']
export type WorkspaceAddMemberRequest = components['schemas']['WorkspaceAddMemberRequest']
export type WorkspaceRoleUpdateRequest = components['schemas']['WorkspaceRoleUpdateRequest']
export type WorkspaceTransferOwnershipRequest =
  components['schemas']['WorkspaceTransferOwnershipRequest']
export type WorkspaceDocumentResponse = components['schemas']['WorkspaceDocumentResponse']

type GeneratedSourceReference = components['schemas']['SourceReference']
export type SourceReference = Omit<GeneratedSourceReference, 'indexedAt'> & {
  indexedAt: string | null
}
type GeneratedQueryResponse = components['schemas']['QueryResponse']
export type QueryResponse = Omit<GeneratedQueryResponse, 'sources'> & {
  sources: SourceReference[]
}

type GeneratedIndexingTriggerRequest = components['schemas']['IndexingTriggerRequest']
export type IndexingTriggerRequest = Omit<GeneratedIndexingTriggerRequest, 'insecureSsl'> & {
  insecureSsl?: boolean
}

export interface UserInfo {
  id: string
  email: string | null
  displayName: string | null
  systemRole: string
}

export function isErrorResponse(data: unknown): data is ErrorResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    'error' in data &&
    typeof (data as Record<string, unknown>).error === 'string'
  )
}
