import type { components } from './generated/api'

export type HealthResponse = components['schemas']['HealthResponse']
export type IndexingStatus = components['schemas']['IndexingStatus']
export type IndexingStatusResponse = components['schemas']['IndexingStatusResponse']
export type IndexingRunEventCategory = components['schemas']['IndexingRunEventCategory']
export type IndexingRunEvent = components['schemas']['IndexingRunEvent']
export type IndexingRunResponse = components['schemas']['IndexingRunResponse']
export type IndexingRunListResponse = components['schemas']['IndexingRunListResponse']
export type IndexingTriggerSource = components['schemas']['IndexingTriggerSource']
export type IndexingRunMode = components['schemas']['IndexingRunMode']
export type IndexingRunMetrics = components['schemas']['IndexingRunMetrics']
export type QueryRequest = components['schemas']['QueryRequest']
export type QueryMetadata = components['schemas']['QueryMetadata']
export type ChunkLocation = components['schemas']['ChunkLocation']
export type SearchedLibrary = components['schemas']['SearchedLibrary']
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
export type SpaceLibraryAssociationRequest = components['schemas']['SpaceLibraryAssociationRequest']
export type SpaceLibraryAssociationResponse =
  components['schemas']['SpaceLibraryAssociationResponse']
export type SpaceLibraryAssociationListResponse =
  components['schemas']['SpaceLibraryAssociationListResponse']
export type LibrarySpaceAssociationResponse =
  components['schemas']['LibrarySpaceAssociationResponse']

export type NotificationType = components['schemas']['NotificationType']
export type NotificationResponse = components['schemas']['NotificationResponse']

type GeneratedSourceReference = components['schemas']['SourceReference']
export type SourceReference = Omit<GeneratedSourceReference, 'indexedAt'> & {
  indexedAt: string | null
}
type GeneratedQueryResponse = components['schemas']['QueryResponse']
export type QueryResponse = Omit<GeneratedQueryResponse, 'sources'> & {
  sources: SourceReference[]
}

export type UserInfoResponse = components['schemas']['UserInfoResponse']
export type UserInfo = UserInfoResponse
export type UserSummaryResponse = components['schemas']['UserSummaryResponse']
export type UserSummary = UserSummaryResponse
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
export type LibraryDiagnosticsLockRequest = components['schemas']['LibraryDiagnosticsLockRequest']
export type LibraryDiagnosticsLockResponse = components['schemas']['LibraryDiagnosticsLockResponse']
export type SourceConnectionTestRequest = components['schemas']['SourceConnectionTestRequest']
export type SourceConnectionTestResponse = components['schemas']['SourceConnectionTestResponse']
export type ScheduleFrequency = components['schemas']['ScheduleFrequency']
export type ScheduleWeekday = components['schemas']['ScheduleWeekday']
export type LibraryScheduleRequest = components['schemas']['LibraryScheduleRequest']
export type LibrarySchedule = components['schemas']['LibrarySchedule']

export type DocumentStatus = components['schemas']['DocumentStatus']
export type DocumentSourceType = components['schemas']['DocumentSourceType']
export type ConfluenceEdition = components['schemas']['ConfluenceEdition']
export type ConfluenceSpaceRef = components['schemas']['ConfluenceSpaceRef']
export type ConfluenceSpaceListRequest = components['schemas']['ConfluenceSpaceListRequest']
export type ConfluenceSpaceListResponse = components['schemas']['ConfluenceSpaceListResponse']
export type ConfluenceWebhookSecretResponse =
  components['schemas']['ConfluenceWebhookSecretResponse']
export type LibraryDocumentResponse = components['schemas']['LibraryDocumentResponse']
export type LibraryDocumentPageResponse = components['schemas']['LibraryDocumentPageResponse']
export type MetadataOrigin = components['schemas']['MetadataOrigin']
export type DatePrecision = components['schemas']['DatePrecision']
export type MetadataValueRequest = components['schemas']['MetadataValueRequest']
export type DocumentMetadataFieldResponse = components['schemas']['DocumentMetadataFieldResponse']
export type DocumentMetadataResponse = components['schemas']['DocumentMetadataResponse']
export type BulkMetadataValueRequest = components['schemas']['BulkMetadataValueRequest']
export type BulkMetadataValueResponse = components['schemas']['BulkMetadataValueResponse']
export type DocumentTypeVocabularyEntryResponse =
  components['schemas']['DocumentTypeVocabularyEntryResponse']
export type DocumentTypeVocabularyResponse = components['schemas']['DocumentTypeVocabularyResponse']
export type MetadataFilter = components['schemas']['MetadataFilter']
export type MetadataFilterMatch = components['schemas']['MetadataFilterMatch']
export type MetadataFilterFieldOption = components['schemas']['MetadataFilterFieldOption']
export type MetadataFilterDocumentTypeOption =
  components['schemas']['MetadataFilterDocumentTypeOption']
export type MetadataFilterOptionsResponse = components['schemas']['MetadataFilterOptionsResponse']
export type MetadataFieldState = components['schemas']['MetadataFieldState']
export type MetadataFieldMaintenanceResponse =
  components['schemas']['MetadataFieldMaintenanceResponse']
export type LibraryMetadataMaintenanceResponse =
  components['schemas']['LibraryMetadataMaintenanceResponse']

export type LibraryFolderListItem = components['schemas']['LibraryFolderListItem']
export type LibraryFolderBreadcrumbItem = components['schemas']['LibraryFolderBreadcrumbItem']
export type LibraryFolderRequest = components['schemas']['LibraryFolderRequest']
export type LibraryFolderRenameRequest = components['schemas']['LibraryFolderRenameRequest']
export type LibraryFolderResponse = components['schemas']['LibraryFolderResponse']

export type PermissionSubjectType = components['schemas']['PermissionSubjectType']
export type AssetGrantRequest = components['schemas']['AssetGrantRequest']
export type AssetGrantResponse = components['schemas']['AssetGrantResponse']

export type ChatStatus = components['schemas']['ChatStatus']
export type ChatRole = components['schemas']['ChatRole']
export type ChatSummary = components['schemas']['ChatSummary']
export type ChatDetail = components['schemas']['ChatDetail']
export type ChatMessageResponse = components['schemas']['ChatMessageResponse']
export type ChatCreateRequest = components['schemas']['ChatCreateRequest']
export type ChatUpdateRequest = components['schemas']['ChatUpdateRequest']

export type ColorScheme = components['schemas']['ColorScheme']
export type BrandingResponse = components['schemas']['BrandingResponse']
export type BrandingUpdateRequest = components['schemas']['BrandingUpdateRequest']

export type LlmModelResponse = components['schemas']['LlmModelResponse']
export type LlmModelRequest = components['schemas']['LlmModelRequest']
export type LlmModelTestRequest = components['schemas']['LlmModelTestRequest']
export type LlmModelTestResponse = components['schemas']['LlmModelTestResponse']
export type EmbeddingInfoResponse = components['schemas']['EmbeddingInfoResponse']

export type SearchModelRole = components['schemas']['SearchModelRole']
export type SearchModelRoleState = components['schemas']['SearchModelRoleState']
export type SearchModelRoleStatusResponse = components['schemas']['SearchModelRoleStatusResponse']
export type SearchPath = components['schemas']['SearchPath']
export type SearchPathState = components['schemas']['SearchPathState']
export type SearchPathStatusResponse = components['schemas']['SearchPathStatusResponse']
export type LibraryIndexState = components['schemas']['LibraryIndexState']
export type LibrarySearchStatusResponse = components['schemas']['LibrarySearchStatusResponse']
export type MetadataBackfillStatusResponse = components['schemas']['MetadataBackfillStatusResponse']
export type CoreMetadataFieldFillResponse = components['schemas']['CoreMetadataFieldFillResponse']
export type MetadataBackfillRequest = components['schemas']['MetadataBackfillRequest']
export type MetadataBackfillResponse = components['schemas']['MetadataBackfillResponse']
export type SearchStatusResponse = components['schemas']['SearchStatusResponse']
export type SearchPermissionProfileResponse =
  components['schemas']['SearchPermissionProfileResponse']
export type SearchDiagnosisContextType = components['schemas']['SearchDiagnosisContextType']
export type SearchDiagnosisContextResponse = components['schemas']['SearchDiagnosisContextResponse']
export type SearchDiagnosisRequest = components['schemas']['SearchDiagnosisRequest']
export type SearchDiagnosisResponse = components['schemas']['SearchDiagnosisResponse']
export type RetrievalStage = components['schemas']['RetrievalStage']
export type RetrievalStageStatus = components['schemas']['RetrievalStageStatus']
export type RetrievalStageResponse = components['schemas']['RetrievalStageResponse']
export type RetrievalVerdictResponse = components['schemas']['RetrievalVerdictResponse']
export type RetrievalCandidateOutcome = components['schemas']['RetrievalCandidateOutcome']
export type RetrievalVerdictReason = components['schemas']['RetrievalVerdictReason']
export type DiagnosisSelectionEntryResponse =
  components['schemas']['DiagnosisSelectionEntryResponse']
export type TrackedDocumentOutcome = components['schemas']['TrackedDocumentOutcome']
export type TrackedDocumentResponse = components['schemas']['TrackedDocumentResponse']
export type ChunkInspectionResponse = components['schemas']['ChunkInspectionResponse']
export type DocumentChunksResponse = components['schemas']['DocumentChunksResponse']

export function isErrorResponse(data: unknown): data is ErrorResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    'error' in data &&
    typeof (data as Record<string, unknown>).error === 'string'
  )
}
