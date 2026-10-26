// chat
import { chatHandlers } from '@/features/chat/mocks/chatHandlers'
import { queryHandlers } from '@/features/chat/mocks/queryHandlers'
import { transcriptionHandlers } from '@/features/chat/mocks/transcriptionHandlers'
// space
import { spaceHandlers } from '@/features/space/mocks/spaceHandlers'
// asset
import { assetHandlers } from '@/features/asset/mocks/assetHandlers'
import { catalogHandlers } from '@/features/asset/mocks/catalogHandlers'
// library
import { libraryHandlers } from '@/features/library/mocks/libraryHandlers'
import { libraryDocumentHandlers } from '@/features/library/mocks/libraryDocumentHandlers'
import { libraryMetadataHandlers } from '@/features/library/mocks/libraryMetadataHandlers'
import { documentMetadataHandlers } from '@/features/library/mocks/documentMetadataHandlers'
import { libraryFolderHandlers } from '@/features/library/mocks/libraryFolderHandlers'
import { indexingHandlers } from '@/features/library/mocks/indexingHandlers'
import { privateStorageHandlers } from '@/features/library/mocks/privateStorageHandlers'
import { fullSyncRhythmHandlers } from '@/features/library/mocks/fullSyncRhythmHandlers'
// prompt
import { promptLibraryHandlers } from '@/features/prompt/mocks/promptLibraryHandlers'
// group
import { groupHandlers } from '@/features/group/mocks/groupHandlers'
import { groupAdminHandlers } from '@/features/group/mocks/groupAdminHandlers'
// succession
import { successionHandlers } from '@/features/succession/mocks/successionHandlers'
// directory-sync
import { directorySyncSettingsHandlers } from '@/features/directory-sync/mocks/directorySyncSettingsHandlers'
// capability
import { capabilityHandlers } from '@/features/capability/mocks/capabilityHandlers'
// user
import { userHandlers } from '@/features/principal/mocks/userHandlers'
import { localUserHandlers } from '@/features/user/mocks/localUserHandlers'
import { accountHandlers } from '@/features/user/mocks/accountHandlers'
// identity-provider
import { identityProviderHandlers } from '@/features/identity-provider/mocks/identityProviderHandlers'
// model
import { modelHandlers } from '@/features/model/mocks/modelHandlers'
import { modelRoleHandlers } from '@/features/model/mocks/modelRoleHandlers'
// mail
import { mailHandlers } from '@/features/mail/mocks/mailHandlers'
// branding
import { brandingHandlers } from '@/features/branding/mocks/brandingHandlers'
// link-targets
import { linkTargetHandlers } from '@/features/link-targets/mocks/linkTargetHandlers'
// settings
import { settingsHandlers } from '@/features/settings/mocks/settingsHandlers'
// connection
import { connectionProfileHandlers } from '@/features/connection/mocks/connectionProfileHandlers'
import { connectedAccountHandlers } from '@/features/connection/mocks/connectedAccountHandlers'
import { connectionLogHandlers } from '@/features/audit/mocks/connectionLogHandlers'
import { outboundProxyHandlers } from '@/features/connection/mocks/outboundProxyHandlers'
import { connectionLifecycleHandlers } from '@/features/connection/mocks/connectionLifecycleHandlers'
// external-access
import { externalAccessHandlers } from '@/features/external-access/mocks/externalAccessHandlers'
import { externalAccessTokenHandlers } from '@/features/external-access/mocks/externalAccessTokenHandlers'
import { externalAccessLibraryHandlers } from '@/features/external-access/mocks/externalAccessLibraryHandlers'
// search-admin
import { searchHandlers } from '@/features/chat/mocks/searchHandlers'
import { searchAdminHandlers } from '@/features/search-admin/mocks/searchAdminHandlers'
import { diagnosticAccessHandlers } from '@/features/library/mocks/diagnosticAccessHandlers'
// notification
import { notificationHandlers } from '@/features/notification/mocks/notificationHandlers'
// compliance
import { selfDisclosureHandlers } from '@/features/compliance/mocks/selfDisclosureHandlers'
import { retentionHandlers } from '@/features/compliance/mocks/retentionHandlers'
// auth
import { authHandlers } from '@/shared/mocks/authHandlers'
import { localAuthHandlers } from '@/features/auth/mocks/localAuthHandlers'
// installation
import { installationHandlers } from '@/features/installation/mocks/installationHandlers'
// app
import { healthHandlers } from '@/shared/mocks/healthHandlers'

/**
 * All MSW handlers, one module per topic of the OpenAPI spec, for the browser worker and the test
 * server. No two topics share a method and path pattern, so the order of the modules below is free.
 * MSW answers with the first match, so order matters inside a module: a literal segment stays ahead
 * of a parameter at the same position (`GET /groups/selectable` before `GET /groups/:groupId`). A
 * handler whose path could match another topic's belongs into that topic's module.
 */
export const handlers = [
  // chat
  ...chatHandlers,
  ...queryHandlers,
  ...transcriptionHandlers,
  // space
  ...spaceHandlers,
  // asset
  ...assetHandlers,
  ...catalogHandlers,
  // library
  ...libraryHandlers,
  ...libraryDocumentHandlers,
  ...libraryMetadataHandlers,
  ...documentMetadataHandlers,
  ...libraryFolderHandlers,
  ...indexingHandlers,
  ...privateStorageHandlers,
  ...fullSyncRhythmHandlers,
  // prompt
  ...promptLibraryHandlers,
  // group
  ...groupHandlers,
  ...groupAdminHandlers,
  // succession
  ...successionHandlers,
  // directory-sync
  ...directorySyncSettingsHandlers,
  // capability
  ...capabilityHandlers,
  // user
  ...userHandlers,
  ...localUserHandlers,
  ...accountHandlers,
  // identity-provider
  ...identityProviderHandlers,
  // model
  ...modelRoleHandlers,
  ...modelHandlers,
  // mail
  ...mailHandlers,
  // branding
  ...brandingHandlers,
  // link-targets
  ...linkTargetHandlers,
  // settings
  ...settingsHandlers,
  // connection
  ...connectionProfileHandlers,
  ...connectedAccountHandlers,
  ...connectionLogHandlers,
  ...outboundProxyHandlers,
  ...connectionLifecycleHandlers,
  // external-access
  ...externalAccessHandlers,
  ...externalAccessTokenHandlers,
  ...externalAccessLibraryHandlers,
  // search-admin
  ...searchHandlers,
  ...searchAdminHandlers,
  ...diagnosticAccessHandlers,
  // notification
  ...notificationHandlers,
  // compliance
  ...selfDisclosureHandlers,
  ...retentionHandlers,
  // auth
  ...authHandlers,
  ...localAuthHandlers,
  // installation
  ...installationHandlers,
  // app
  ...healthHandlers,
]
