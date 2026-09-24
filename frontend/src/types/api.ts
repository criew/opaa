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
export type AssetType = components['schemas']['AssetType']
export type SpaceAssetAssociationRequest = components['schemas']['SpaceAssetAssociationRequest']
export type SpaceAssetAssociationResponse = components['schemas']['SpaceAssetAssociationResponse']
export type SpaceAssetAssociationListResponse =
  components['schemas']['SpaceAssetAssociationListResponse']
export type AssetSpaceAssociationResponse = components['schemas']['AssetSpaceAssociationResponse']
export type AssetSpaceAssociationListResponse =
  components['schemas']['AssetSpaceAssociationListResponse']

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
export type GroupStewardResponse = components['schemas']['GroupStewardResponse']
export type GroupContactResponse = components['schemas']['GroupContactResponse']
export type GroupOrigin = components['schemas']['GroupOrigin']
export type GroupMechanism = components['schemas']['GroupMechanism']
export type GroupProviderResponse = components['schemas']['GroupProviderResponse']
export type SelectableGroupResponse = components['schemas']['SelectableGroupResponse']
export type GroupMemberDisclosureResponse = components['schemas']['GroupMemberDisclosureResponse']
export type DisclosedGroupMemberResponse = components['schemas']['DisclosedGroupMemberResponse']
export type GroupEffectsResponse = components['schemas']['GroupEffectsResponse']

export type AccessPathGroup = components['schemas']['AccessPathGroup']
export type AccessPathResponse = components['schemas']['AccessPathResponse']
export type AssetAccessDerivationResponse = components['schemas']['AssetAccessDerivationResponse']
export type SpaceAccessDerivationResponse = components['schemas']['SpaceAccessDerivationResponse']

export type Capability = components['schemas']['Capability']
export type MyCapabilitiesResponse = components['schemas']['MyCapabilitiesResponse']
export type CapabilitySubjectType = components['schemas']['CapabilitySubjectType']
export type CapabilityGrantRequest = components['schemas']['CapabilityGrantRequest']
export type CapabilityGrantResponse = components['schemas']['CapabilityGrantResponse']
export type CapabilityOverviewResponse = components['schemas']['CapabilityOverviewResponse']

export type PermissionTransferScope = components['schemas']['PermissionTransferScope']
export type PermissionTransferPreviewRequest =
  components['schemas']['PermissionTransferPreviewRequest']
export type PermissionTransferPreviewResponse =
  components['schemas']['PermissionTransferPreviewResponse']
export type PermissionTransferRequest = components['schemas']['PermissionTransferRequest']
export type PermissionTransferResponse = components['schemas']['PermissionTransferResponse']
export type PermissionTransferCountsResponse =
  components['schemas']['PermissionTransferCountsResponse']

export type SuccessionKind = components['schemas']['SuccessionKind']
export type SuccessionObjectType = components['schemas']['SuccessionObjectType']
export type SuccessionAddressee = components['schemas']['SuccessionAddressee']
export type SuccessionEntryResponse = components['schemas']['SuccessionEntryResponse']
export type SuccessionListResponse = components['schemas']['SuccessionListResponse']
export type SuccessionReviewRequest = components['schemas']['SuccessionReviewRequest']
export type SuccessionReviewResponse = components['schemas']['SuccessionReviewResponse']
export type SuccessionStateResponse = components['schemas']['SuccessionStateResponse']

export type DirectorySyncOutcome = components['schemas']['DirectorySyncOutcome']
export type DirectorySyncStatusResponse = components['schemas']['DirectorySyncStatusResponse']
export type DirectorySyncReportResponse = components['schemas']['DirectorySyncReportResponse']
export type DirectorySyncGroupChange = components['schemas']['DirectorySyncGroupChange']
export type DirectorySyncMembershipChange = components['schemas']['DirectorySyncMembershipChange']
export type DirectorySyncUserRef = components['schemas']['DirectorySyncUserRef']
export type DirectorySyncPendingPlanResponse =
  components['schemas']['DirectorySyncPendingPlanResponse']
export type DirectorySyncPendingPlanSummary =
  components['schemas']['DirectorySyncPendingPlanSummary']
export type DirectorySyncPlanDecisionRequest =
  components['schemas']['DirectorySyncPlanDecisionRequest']
export type OidcProviderDirectorySyncRequest =
  components['schemas']['OidcProviderDirectorySyncRequest']
export type DirectoryConnectorType = components['schemas']['DirectoryConnectorType']
export type DirectoryConnectorRequest = components['schemas']['DirectoryConnectorRequest']
export type DirectoryConnectorTestRequest = components['schemas']['DirectoryConnectorTestRequest']
export type DirectoryConnectorResponse = components['schemas']['DirectoryConnectorResponse']

export type AssetRole = components['schemas']['AssetRole']
export type AssetOwnerType = components['schemas']['AssetOwnerType']
export type AssetReach = components['schemas']['AssetReachResponse']
export type LibraryRequest = components['schemas']['LibraryRequest']
export type LibraryUpdateRequest = components['schemas']['LibraryUpdateRequest']
export type LibraryShareCapRequest = components['schemas']['LibraryShareCapRequest']
export type LibraryListResponse = components['schemas']['LibraryListResponse']
export type LibraryResponse = components['schemas']['LibraryResponse']
export type PromptVariableType = components['schemas']['PromptVariableType']
export type PromptVariable = components['schemas']['PromptVariable']
export type PromptRequest = components['schemas']['PromptRequest']
export type PromptResponse = components['schemas']['PromptResponse']
export type PromptLibraryRequest = components['schemas']['PromptLibraryRequest']
export type PromptLibraryUpdateRequest = components['schemas']['PromptLibraryUpdateRequest']
export type PromptLibraryResponse = components['schemas']['PromptLibraryResponse']
export type ExternalAccessState = components['schemas']['ExternalAccessState']
export type LibraryExternalAccessRequest = components['schemas']['LibraryExternalAccessRequest']
export type LibraryExternalAccessResponse = components['schemas']['LibraryExternalAccessResponse']
export type ExternalAccessLibraryResponse = components['schemas']['ExternalAccessLibraryResponse']
export type LibraryDiagnosticsLockRequest = components['schemas']['LibraryDiagnosticsLockRequest']
export type LibraryDiagnosticsLockResponse = components['schemas']['LibraryDiagnosticsLockResponse']
export type SourceConnectionTestRequest = components['schemas']['SourceConnectionTestRequest']
export type SourceConnectionTestResponse = components['schemas']['SourceConnectionTestResponse']
export type AccessAsOfObjectType = components['schemas']['AccessAsOfObjectType']
export type AccessAsOfEntry = components['schemas']['AccessAsOfEntry']
export type AccessAsOfPage = components['schemas']['AccessAsOfPage']
export type AccessBasis = components['schemas']['AccessBasis']
export type AccessAsOfSource = components['schemas']['AccessAsOfSource']
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
export type S3Settings = components['schemas']['S3Settings']
export type S3ScopeRef = components['schemas']['S3ScopeRef']
export type S3ScopeCheck = components['schemas']['S3ScopeCheck']
export type S3BucketListRequest = components['schemas']['S3BucketListRequest']
export type S3BucketListResponse = components['schemas']['S3BucketListResponse']
export type ConfluenceWebhookSecretResponse =
  components['schemas']['ConfluenceWebhookSecretResponse']
export type S3EventsTokenResponse = components['schemas']['S3EventsTokenResponse']
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
export type LibraryMetadataExtractionSettingsResponse =
  components['schemas']['LibraryMetadataExtractionSettingsResponse']
export type LibraryMetadataExtractionSettingsRequest =
  components['schemas']['LibraryMetadataExtractionSettingsRequest']
export type ActiveChatModelSummaryResponse = components['schemas']['ActiveChatModelSummaryResponse']
export type MetadataModelExtractionStatsResponse =
  components['schemas']['MetadataModelExtractionStatsResponse']
export type MetadataFieldQualityResponse = components['schemas']['MetadataFieldQualityResponse']
export type LibraryMetadataQualityResponse = components['schemas']['LibraryMetadataQualityResponse']
export type LibraryMetadataSampleResponse = components['schemas']['LibraryMetadataSampleResponse']
export type LibraryMetadataFieldType = components['schemas']['LibraryMetadataFieldType']
export type LibraryMetadataFieldResponse = components['schemas']['LibraryMetadataFieldResponse']
export type LibraryMetadataFieldsResponse = components['schemas']['LibraryMetadataFieldsResponse']
export type CoreContextPrefixRequest = components['schemas']['CoreContextPrefixRequest']
export type CoreContextPrefixResponse = components['schemas']['CoreContextPrefixResponse']
export type MetadataChangeKind = components['schemas']['MetadataChangeKind']
export type MetadataChangeImpactResponse = components['schemas']['MetadataChangeImpactResponse']
export type LibraryMetadataFieldValueResponse =
  components['schemas']['LibraryMetadataFieldValueResponse']
export type CreateLibraryMetadataFieldRequest =
  components['schemas']['CreateLibraryMetadataFieldRequest']
export type UpdateLibraryMetadataFieldRequest =
  components['schemas']['UpdateLibraryMetadataFieldRequest']
export type LibraryMetadataFieldValueRequest =
  components['schemas']['LibraryMetadataFieldValueRequest']
export type MetadataFieldUsageResponse = components['schemas']['MetadataFieldUsageResponse']
export type RemapLibraryMetadataFieldValueResponse =
  components['schemas']['RemapLibraryMetadataFieldValueResponse']
export type LibraryMetadataSchemaChangeResponse =
  components['schemas']['LibraryMetadataSchemaChangeResponse']
export type LibraryMetadataSchemaRunResponse =
  components['schemas']['LibraryMetadataSchemaRunResponse']
export type MetadataFilterFormatFieldCondition =
  components['schemas']['MetadataFilterFormatFieldCondition']
export type MetadataFilterFormatFieldOption =
  components['schemas']['MetadataFilterFormatFieldOption']
export type MetadataFilterLibraryFieldCondition =
  components['schemas']['MetadataFilterLibraryFieldCondition']
export type MetadataFilterLibraryFieldOption =
  components['schemas']['MetadataFilterLibraryFieldOption']

export type LibraryFolderListItem = components['schemas']['LibraryFolderListItem']
export type LibraryFolderBreadcrumbItem = components['schemas']['LibraryFolderBreadcrumbItem']
export type LibraryFolderRequest = components['schemas']['LibraryFolderRequest']
export type LibraryFolderRenameRequest = components['schemas']['LibraryFolderRenameRequest']
export type LibraryFolderResponse = components['schemas']['LibraryFolderResponse']

export type PermissionSubjectType = components['schemas']['PermissionSubjectType']
export type AssetGrantSubjectType = components['schemas']['AssetGrantSubjectType']
export type AssetGrantRequest = components['schemas']['AssetGrantRequest']
export type AssetGrantResponse = components['schemas']['AssetGrantResponse']

export type ChatStatus = components['schemas']['ChatStatus']
export type ChatRole = components['schemas']['ChatRole']
export type ChatSummary = components['schemas']['ChatSummary']
export type ChatDetail = components['schemas']['ChatDetail']
export type ChatSummaryPage = components['schemas']['ChatSummaryPage']
export type ChatBulkAction = components['schemas']['ChatBulkAction']
export type ChatBulkActionResult = components['schemas']['ChatBulkActionResult']
export type ChatMessageResponse = components['schemas']['ChatMessageResponse']
export type AvailablePrompt = components['schemas']['AvailablePrompt']
export type ChatCreateRequest = components['schemas']['ChatCreateRequest']
export type ChatUpdateRequest = components['schemas']['ChatUpdateRequest']
export type ChatNoteItem = components['schemas']['ChatNoteItem']
export type ChatNoteItemKind = components['schemas']['ChatNoteItemKind']
export type ChatSearchRequest = components['schemas']['ChatSearchRequest']
export type ChatSearchResponse = components['schemas']['ChatSearchResponse']
export type ChatSearchHit = components['schemas']['ChatSearchHit']
export type ChatSearchHighlight = components['schemas']['ChatSearchHighlight']

export type ColorScheme = components['schemas']['ColorScheme']
export type BrandingResponse = components['schemas']['BrandingResponse']
export type BrandingUpdateRequest = components['schemas']['BrandingUpdateRequest']

export type LlmModelResponse = components['schemas']['LlmModelResponse']
export type LlmModelRequest = components['schemas']['LlmModelRequest']
export type LlmModelTestRequest = components['schemas']['LlmModelTestRequest']
export type LlmModelTestResponse = components['schemas']['LlmModelTestResponse']
export type OidcProviderResponse = components['schemas']['OidcProviderResponse']
export type OidcProviderRequest = components['schemas']['OidcProviderRequest']
export type OidcClaimMappingDto = components['schemas']['OidcClaimMappingDto']
export type OidcProviderRegistryState = components['schemas']['OidcProviderRegistryState']
export type OidcProviderOrderRequest = components['schemas']['OidcProviderOrderRequest']
export type OidcProviderTestRequest = components['schemas']['OidcProviderTestRequest']
export type OidcProviderTestResponse = components['schemas']['OidcProviderTestResponse']
export type EmbeddingInfoResponse = components['schemas']['EmbeddingInfoResponse']

export type MailEncryption = components['schemas']['MailEncryption']
export type MailSettingsResponse = components['schemas']['MailSettingsResponse']
export type MailSettingsUpdateRequest = components['schemas']['MailSettingsUpdateRequest']
export type MailSendOutcome = components['schemas']['MailSendOutcome']
export type MailSendResultResponse = components['schemas']['MailSendResultResponse']
export type MailTemplateSource = components['schemas']['MailTemplateSource']
export type MailTemplateSummaryResponse = components['schemas']['MailTemplateSummaryResponse']
export type MailTemplateResponse = components['schemas']['MailTemplateResponse']
export type MailTemplateUpdateRequest = components['schemas']['MailTemplateUpdateRequest']
export type MailTemplatePreviewRequest = components['schemas']['MailTemplatePreviewRequest']
export type MailTemplatePreviewResponse = components['schemas']['MailTemplatePreviewResponse']

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
export type ContextPrefixRerunRequest = components['schemas']['ContextPrefixRerunRequest']
export type ContextPrefixRerunResponse = components['schemas']['ContextPrefixRerunResponse']
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

export type ProviderType = components['schemas']['ProviderType']
export type MailDeliveryPath = components['schemas']['MailDeliveryPath']
export type LockReason = components['schemas']['LockReason']
export type LocalAccountState = components['schemas']['LocalAccountState']
export type LocalAccountActivity = components['schemas']['LocalAccountActivity']
export type LocalUserCreationMode = components['schemas']['LocalUserCreationMode']
export type LocalUserResponse = components['schemas']['LocalUserResponse']
export type LocalUserPageResponse = components['schemas']['LocalUserPageResponse']
export type LocalUserSummaryResponse = components['schemas']['LocalUserSummaryResponse']
export type LocalUserCreateRequest = components['schemas']['LocalUserCreateRequest']
export type LocalUserCreatedResponse = components['schemas']['LocalUserCreatedResponse']
export type LocalUserUpdateRequest = components['schemas']['LocalUserUpdateRequest']
export type LocalUserLockRequest = components['schemas']['LocalUserLockRequest']
export type LocalUserPasswordResetResponse = components['schemas']['LocalUserPasswordResetResponse']
export type LocalUserGeneratedPasswordResponse =
  components['schemas']['LocalUserGeneratedPasswordResponse']
export type LocalUserHandoverRequest = components['schemas']['LocalUserHandoverRequest']
export type LocalUserHandoverResponse = components['schemas']['LocalUserHandoverResponse']
export type LocalHandoverPreviewResponse = components['schemas']['LocalHandoverPreviewResponse']
export type LocalHandoverProvider = components['schemas']['LocalHandoverProvider']
export type LocalHandoverScope = components['schemas']['LocalHandoverScope']
export type LocalAuthSettingsResponse = components['schemas']['LocalAuthSettingsResponse']
export type LocalAuthSettingsUpdateRequest = components['schemas']['LocalAuthSettingsUpdateRequest']
export type AccountResponse = components['schemas']['AccountResponse']
export type AccountPageResponse = components['schemas']['AccountPageResponse']
export type AccountProviderResponse = components['schemas']['AccountProviderResponse']
export type ExternalAccessSettingsResponse = components['schemas']['ExternalAccessSettingsResponse']
export type ExternalAccessSettingsUpdateRequest =
  components['schemas']['ExternalAccessSettingsUpdateRequest']
export type ExternalAccessChannelInfoResponse =
  components['schemas']['ExternalAccessChannelInfoResponse']
export type ExternalAccessTokenStatus = components['schemas']['ExternalAccessTokenStatus']
export type EligibleExternalAccessLibraryResponse =
  components['schemas']['EligibleExternalAccessLibraryResponse']
export type ExternalAccessTokenLibraryResponse =
  components['schemas']['ExternalAccessTokenLibraryResponse']
export type CreateExternalAccessTokenRequest =
  components['schemas']['CreateExternalAccessTokenRequest']
export type CreatedExternalAccessTokenResponse =
  components['schemas']['CreatedExternalAccessTokenResponse']
export type OwnExternalAccessTokenResponse = components['schemas']['OwnExternalAccessTokenResponse']
export type AdminExternalAccessTokenResponse =
  components['schemas']['AdminExternalAccessTokenResponse']

export function isErrorResponse(data: unknown): data is ErrorResponse {
  return (
    typeof data === 'object' &&
    data !== null &&
    'error' in data &&
    typeof (data as Record<string, unknown>).error === 'string'
  )
}
