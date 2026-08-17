import type { components } from './generated/api'

export type HealthResponse = components['schemas']['HealthResponse']
export type IndexingStatus = components['schemas']['IndexingStatus']
export type IndexingStatusResponse = components['schemas']['IndexingStatusResponse']
export type QueryRequest = components['schemas']['QueryRequest']
export type QueryMetadata = components['schemas']['QueryMetadata']
export type ErrorResponse = components['schemas']['ErrorResponse']

export type SpaceRole = components['schemas']['SpaceRole']
export type SpaceVisibility = components['schemas']['SpaceVisibility']
export type SpaceRequest = components['schemas']['SpaceRequest']
export type SpaceUpdateRequest = components['schemas']['SpaceUpdateRequest']
export type SpaceMemberRequest = components['schemas']['SpaceMemberRequest']
export type SpaceListResponse = components['schemas']['SpaceListResponse']
export type SpaceMemberResponse = components['schemas']['SpaceMemberResponse']
export type SpaceResponse = components['schemas']['SpaceResponse']
export type SpaceAddMemberRequest = components['schemas']['SpaceAddMemberRequest']
export type SpaceRoleUpdateRequest = components['schemas']['SpaceRoleUpdateRequest']
export type SpaceTransferOwnershipRequest = components['schemas']['SpaceTransferOwnershipRequest']

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

export type UserInfoResponse = components['schemas']['UserInfoResponse']
export type UserInfo = UserInfoResponse
export type RoleChangeRequest = components['schemas']['RoleChangeRequest']
export type SystemRole = components['schemas']['SystemRole']

export type GroupKind = components['schemas']['GroupKind']
export type GroupRequest = components['schemas']['GroupRequest']
export type GroupUpdateRequest = components['schemas']['GroupUpdateRequest']
export type GroupMemberResponse = components['schemas']['GroupMemberResponse']
export type GroupListResponse = components['schemas']['GroupListResponse']
export type GroupResponse = components['schemas']['GroupResponse']
export type GroupAddMemberRequest = components['schemas']['GroupAddMemberRequest']

export type AssetRole = components['schemas']['AssetRole']
export type LibraryOwnerType = components['schemas']['LibraryOwnerType']
export type LibraryVisibility = components['schemas']['LibraryVisibility']
export type LibraryRequest = components['schemas']['LibraryRequest']
export type LibraryUpdateRequest = components['schemas']['LibraryUpdateRequest']
export type LibraryListResponse = components['schemas']['LibraryListResponse']
export type LibraryResponse = components['schemas']['LibraryResponse']

export type DocumentStatus = components['schemas']['DocumentStatus']
export type DocumentSourceType = components['schemas']['DocumentSourceType']
export type LibraryDocumentResponse = components['schemas']['LibraryDocumentResponse']

export type PermissionSubjectType = components['schemas']['PermissionSubjectType']
export type AssetGrantRequest = components['schemas']['AssetGrantRequest']
export type AssetGrantResponse = components['schemas']['AssetGrantResponse']

export function isErrorResponse(data: unknown): data is ErrorResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    'error' in data &&
    typeof (data as Record<string, unknown>).error === 'string'
  )
}
