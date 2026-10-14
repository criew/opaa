// Theme boundaries of the frontend, source of eslint/boundaries.js (rules in frontend/AGENTS.md).
// Every file under src/ belongs to exactly one bucket - a theme, shared, app or test-infra - via
// its fixed target location or a legacy glob (eslint/themes.test.mjs). Moving a file to the target
// location needs no change here; its legacy glob just stops matching. Globs are micromatch
// patterns relative to frontend/.

/** Target location of a theme; the theme's public entry is `index.ts` directly below it. */
export const featureRoot = (theme) => `src/features/${theme}`

/** Mock handlers and fixtures: legacy src/mocks/ and src/features/<theme>/mocks/. */
export const MOCK_GLOBS = ['src/mocks/**', 'src/features/*/mocks/**']

export const THEMES = {
  chat: {
    legacy: [
      'src/components/chat/**',
      'src/pages/{ChatPage,ChatRedirect,ChatsPage}.*',
      'src/stores/{chatStore,chatListStore,chatSettingsPersistence,metadataFilterOptionsStore}.*',
      'src/services/{chatApi,queryApi,searchApi,transcriptionApi}.*',
      'src/hooks/{useChatSearch,useDictation,useTranscriptionAvailability}.*',
      'src/utils/{audioRecording,retrievalProtocolText,retrieval-note-templates,accessLevel}.*',
      'src/types/chat.*',
      'src/mocks/{chat,query,search,transcription}{Fixtures,Handlers}.*',
    ],
  },
  space: {
    legacy: [
      'src/components/space/**',
      'src/pages/{SpaceCreatePage,SpacePage,SpaceSettingsPage,SpacesOverviewPage}.*',
      'src/stores/spaceStore.*',
      'src/services/spaceApi.*',
      'src/utils/recentSpaces.*',
      'src/mocks/space{Fixtures,Handlers}.*',
    ],
  },
  asset: {
    legacy: [
      'src/components/assets/**',
      'src/components/permissions/{AccessDerivation,AssetGrantsSection}.*',
      'src/pages/{CatalogAssetTypeRegistry,CatalogNewPage,CatalogPage}.*',
      'src/stores/{catalogStore,grantStore}.*',
      'src/services/{assetApi,catalogApi,catalogPaging}.*',
      'src/mocks/{asset,catalog}{Fixtures,Handlers}.*',
    ],
  },
  library: {
    legacy: [
      'src/components/library/**',
      'src/components/metadata/**',
      'src/components/{DocumentTextPreviewDialog,EditLibraryScheduleDialog,EditLibrarySourceDialog}.*',
      'src/pages/{LibraryCreatePage,LibraryDetailPage,ConnectionCallbackPage}.*',
      'src/stores/{libraryStore,documentStore,indexingStore}.*',
      'src/services/{libraryApi,libraryMetadataApi,indexingApi,diagnosticAccessApi,privateStorageApi}.*',
      'src/hooks/{useDocumentPreview,useSourceTypes}.*',
      'src/utils/{confluenceSource,dropboxSource,filesystemSource,googleDriveSource,nextcloudSource,s3Source,sharePointSource,smbSource}.*',
      'src/utils/{librarySchedule,librarySourceConfig,metadataChunkKeys,documentContent,sourceIssue,directoryEntries}.*',
      'src/mocks/{library,libraryDocument,libraryFolder,libraryMetadata,indexing,documentMetadata,diagnosticAccess,privateStorage}{Fixtures,Handlers}.*',
      'src/mocks/sharePointSourceMock.*',
    ],
  },
  prompt: {
    legacy: [
      'src/components/prompts/**',
      'src/pages/{PromptLibraryCreatePage,PromptLibraryDetailPage,PromptLibraryFlow}.*',
      'src/stores/promptLibraryStore.*',
      'src/services/promptLibraryApi.*',
      'src/utils/promptTemplate.*',
      'src/mocks/promptLibrary{Fixtures,Handlers}.*',
    ],
  },
  principal: {
    legacy: [
      'src/components/permissions/{SubjectPicker,SubjectFormRow,subjectSelection,GroupPicker,GroupMembersDisclosure}.*',
      'src/components/groups/UserPicker.*',
      'src/hooks/{useUserSearch,useGroupSearch,useMyGroups}.*',
      'src/services/userApi.*',
      'src/mocks/user{Fixtures,Handlers}.*',
    ],
  },
  group: {
    legacy: [
      'src/components/groups/{GroupFormFields,GroupStewardsSection,InitialStewardsSection,MyGroupsSection,groupDeactivation,groupOriginLabels}.*',
      'src/components/admin/groups/**',
      'src/components/CreateGroupDialog.*',
      'src/pages/{GroupManagementPage,ProviderGroupWorklistPage}.*',
      'src/stores/{groupStore,groupAdminListStore}.*',
      'src/services/{groupApi,groupAdminApi}.*',
      'src/mocks/{group,groupAdmin}{Fixtures,Handlers}.*',
    ],
  },
  succession: {
    legacy: [
      'src/components/succession/**',
      'src/components/permissions/PermissionTransferDialog.*',
      'src/pages/SuccessionPage.*',
      'src/services/{successionApi,permissionTransferApi}.*',
      'src/mocks/succession{Fixtures,Handlers}.*',
    ],
  },
  capability: {
    legacy: [
      'src/components/admin/capabilities/**',
      'src/pages/CapabilityManagementPage.*',
      'src/services/capabilityAdminApi.*',
      'src/mocks/capability{Fixtures,Handlers}.*',
    ],
  },
  user: {
    legacy: [
      'src/components/admin/users/**',
      'src/pages/UserManagementPage.*',
      'src/stores/userAdminStore.*',
      'src/services/{accountApi,localUserApi}.*',
      'src/mocks/{account,localUser}{Fixtures,Handlers}.*',
    ],
  },
  'identity-provider': {
    legacy: [
      'src/components/admin/providers/**',
      'src/components/admin/{OidcProviderFormDialog,OidcProviderSetupInstructions,oidcProviderConflicts,oidcProviderState}.*',
      'src/pages/OidcProviderManagementPage.*',
      'src/stores/oidcProviderStore.*',
      'src/services/identityProviderApi.*',
      'src/mocks/identityProvider{Fixtures,Handlers}.*',
    ],
  },
  'directory-sync': {
    legacy: [
      'src/components/admin/directorysync/**',
      'src/pages/DirectorySyncPage.*',
      'src/services/directorySyncApi.*',
      'src/mocks/directorySyncScenarioFixtures.*',
    ],
  },
  model: {
    legacy: [
      'src/components/admin/models/**',
      'src/pages/LlmModelManagementPage.*',
      'src/stores/llmModelStore.*',
      'src/services/modelApi.*',
      'src/mocks/model{Fixtures,Handlers}.*',
    ],
  },
  mail: {
    legacy: [
      'src/components/admin/mail/**',
      'src/pages/MailSettingsPage.*',
      'src/stores/mailStore.*',
      'src/services/mailApi.*',
      'src/hooks/useMailTemplatePreview.*',
      'src/mocks/mail{Fixtures,Handlers}.*',
    ],
  },
  branding: {
    legacy: [
      'src/components/admin/BrandingPreview.*',
      'src/pages/BrandingSettingsPage.*',
      'src/mocks/branding{Fixtures,Handlers}.*',
    ],
  },
  connection: {
    legacy: [
      'src/components/admin/connections/**',
      'src/components/settings/**',
      'src/services/{connectionProfileApi,connectedAccountApi,outboundProxyApi}.*',
      'src/hooks/useConnectionProfileOptions.*',
      'src/mocks/{connectionProfile,connectedAccount,outboundProxy}{Fixtures,Handlers}.*',
    ],
  },
  'external-access': {
    legacy: [
      'src/components/externalaccess/**',
      'src/components/admin/externalaccess/**',
      'src/services/externalAccessApi.*',
      'src/mocks/{externalAccess,externalAccessLibrary,externalAccessToken}{Fixtures,Handlers}.*',
    ],
  },
  'search-admin': {
    legacy: [
      'src/components/searchadmin/**',
      'src/components/admin/IndexingSnackbar.*',
      'src/pages/SearchIndexingAdminPage.*',
      'src/stores/searchAdminStore.*',
      'src/services/searchAdminApi.*',
      'src/mocks/searchAdmin{Fixtures,Handlers}.*',
    ],
  },
  audit: {
    legacy: [
      'src/components/audit/**',
      'src/pages/{RightsHistoryPage,ConnectionLogPage}.*',
      'src/services/{rightsHistoryApi,connectionLogApi}.*',
      'src/mocks/connectionLog{Fixtures,Handlers}.*',
    ],
  },
  notification: {
    legacy: ['src/components/notifications/**', 'src/services/notificationApi.*'],
  },
  auth: {
    legacy: [
      'src/components/auth/**',
      'src/pages/{AuthCallbackPage,ChangePasswordPage,ForgotPasswordPage,HandoverPage,LoginPage,RegisterPage,SetPasswordPage,SystemLoginPage,VerifyEmailPage}.*',
      'src/services/{selfServiceApi,handoverApi}.*',
      'src/hooks/useLinkToken.*',
      'src/mocks/localAuth{Fixtures,Handlers}.*',
    ],
  },
}

/** Cross-cutting building blocks; may depend on nothing but itself. */
export const SHARED = {
  root: 'src/shared',
  legacy: [
    'src/components/{AreaPageHeader,AreaTabs,BrandMark,ConfirmHost,GlobalBadge,GlobalScopeNote,KeyValueList,MetaBadge,NotificationHost,PageSection,ProviderMonogram,SectionHead,StateDot,StatusLine}.*',
    'src/components/{a11y,choice,overview,wizard}/**',
    'src/components/admin/list/**',
    'src/layouts/globalArea.*',
    'src/hooks/{useErrorFocus,useFeatureEnabled,useMyCapabilities,usePageTitle}.*',
    'src/services/{api,apiErrorDetails,apiInterceptors,authApi,brandingApi,capabilityApi,devAuth,leaveApp}.*',
    'src/stores/{authStore,brandingStore,confirmStore,handoverFlow,notificationStore,resettableStores,sessionEpoch,silentSignIn,uiStore}.*',
    'src/theme/**',
    'src/types/{api,auth}.*',
    'src/types/generated/**',
    'src/utils/{authMessages,passwordStrength,clipboard,contrast,copyOnce,installationFeatures,labels,runtimeConfig,safeRedirectPath,userInitial}.*',
    'src/routes.*',
    'src/opaa-version.d.ts',
    'src/mocks/{auth,health}{Fixtures,Handlers}.*',
  ],
}

/** Application frame: routing, shell, area pages composed from several themes, MSW wiring. */
export const APP = {
  root: 'src/app',
  legacy: [
    'src/{App,main}.*',
    'src/layouts/{AboutDialog,AppShell,DemoNotice,GlobalAreaLayout,GlobalRail,MobileHeader,Sidebar,SidebarResizeHandle}.*',
    'src/components/{ErrorBoundary,ProtectedRoute}.*',
    'src/pages/{SettingsPage,ConnectionsAdminPage}.*',
    'src/mocks/{handlers,browser}.*',
  ],
}

/** Test setup and render helpers; may depend on everything, nothing but tests depends on it. */
export const TEST_INFRA = {
  root: 'src/test',
  legacy: ['src/mocks/server.*'],
}

/**
 * Allowed dependencies between themes, cycle-free. A theme always reaches itself and `shared`;
 * `app` and `test-infra` reach every theme. Edges point from the depending theme to its targets.
 */
export const ALLOWED = {
  chat: ['space', 'prompt', 'library'],
  space: ['asset', 'principal', 'succession'],
  asset: ['principal', 'succession'],
  library: ['asset', 'principal', 'succession', 'connection', 'external-access'],
  prompt: ['asset', 'principal', 'succession'],
  principal: [],
  group: ['principal', 'succession', 'identity-provider', 'directory-sync', 'user'],
  succession: ['principal'],
  capability: ['principal'],
  user: ['identity-provider', 'mail'],
  'identity-provider': [],
  'directory-sync': ['identity-provider', 'principal'],
  model: [],
  mail: [],
  branding: [],
  connection: ['capability', 'audit'],
  'external-access': ['asset'],
  'search-admin': ['library'],
  audit: [],
  notification: [],
  auth: ['user'],
}

/** All globs of a theme: target location first, then the legacy globs. */
export function themeGlobs(theme) {
  return [`${featureRoot(theme)}/**`, ...THEMES[theme].legacy]
}

/** All globs of a non-theme bucket (`SHARED`, `APP`, `TEST_INFRA`). */
export function bucketGlobs(bucket) {
  return [`${bucket.root}/**`, ...bucket.legacy]
}
