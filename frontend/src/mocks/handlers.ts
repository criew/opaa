// chat
import { chatHandlers } from './chatHandlers'
import { queryHandlers } from './queryHandlers'
import { transcriptionHandlers } from './transcriptionHandlers'
// space
import { spaceHandlers } from './spaceHandlers'
// asset
import { assetHandlers } from './assetHandlers'
import { catalogHandlers } from './catalogHandlers'
// library
import { libraryHandlers } from './libraryHandlers'
import { libraryDocumentHandlers } from './libraryDocumentHandlers'
import { libraryMetadataHandlers } from './libraryMetadataHandlers'
import { documentMetadataHandlers } from './documentMetadataHandlers'
import { libraryFolderHandlers } from './libraryFolderHandlers'
import { indexingHandlers } from './indexingHandlers'
import { privateStorageHandlers } from './privateStorageHandlers'
// prompt
import { promptLibraryHandlers } from './promptLibraryHandlers'
// group
import { groupHandlers } from './groupHandlers'
import { groupAdminHandlers } from './groupAdminHandlers'
// succession
import { successionHandlers } from './successionHandlers'
// capability
import { capabilityHandlers } from './capabilityHandlers'
// user
import { userHandlers } from './userHandlers'
import { localUserHandlers } from './localUserHandlers'
import { accountHandlers } from './accountHandlers'
// identity-provider
import { identityProviderHandlers } from '@/features/identity-provider/mocks/identityProviderHandlers'
// model
import { modelHandlers } from '@/features/model/mocks/modelHandlers'
// mail
import { mailHandlers } from '@/features/mail/mocks/mailHandlers'
// branding
import { brandingHandlers } from '@/features/branding/mocks/brandingHandlers'
// connection
import { connectionProfileHandlers } from './connectionProfileHandlers'
import { connectedAccountHandlers } from './connectedAccountHandlers'
import { connectionLogHandlers } from '@/features/audit/mocks/connectionLogHandlers'
import { outboundProxyHandlers } from './outboundProxyHandlers'
// external-access
import { externalAccessHandlers } from './externalAccessHandlers'
import { externalAccessTokenHandlers } from './externalAccessTokenHandlers'
import { externalAccessLibraryHandlers } from './externalAccessLibraryHandlers'
// search-admin
import { searchHandlers } from './searchHandlers'
import { searchAdminHandlers } from './searchAdminHandlers'
import { diagnosticAccessHandlers } from './diagnosticAccessHandlers'
// auth
import { authHandlers } from './authHandlers'
import { localAuthHandlers } from './localAuthHandlers'
// app
import { healthHandlers } from './healthHandlers'

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
  // prompt
  ...promptLibraryHandlers,
  // group
  ...groupHandlers,
  ...groupAdminHandlers,
  // succession
  ...successionHandlers,
  // capability
  ...capabilityHandlers,
  // user
  ...userHandlers,
  ...localUserHandlers,
  ...accountHandlers,
  // identity-provider
  ...identityProviderHandlers,
  // model
  ...modelHandlers,
  // mail
  ...mailHandlers,
  // branding
  ...brandingHandlers,
  // connection
  ...connectionProfileHandlers,
  ...connectedAccountHandlers,
  ...connectionLogHandlers,
  ...outboundProxyHandlers,
  // external-access
  ...externalAccessHandlers,
  ...externalAccessTokenHandlers,
  ...externalAccessLibraryHandlers,
  // search-admin
  ...searchHandlers,
  ...searchAdminHandlers,
  ...diagnosticAccessHandlers,
  // auth
  ...authHandlers,
  ...localAuthHandlers,
  // app
  ...healthHandlers,
]
