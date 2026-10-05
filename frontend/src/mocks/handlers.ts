import { healthHandlers } from './healthHandlers'
import { indexingHandlers } from './indexingHandlers'
import { libraryHandlers } from './libraryHandlers'
import { queryHandlers } from './queryHandlers'
import { searchHandlers } from './searchHandlers'
import { chatHandlers } from './chatHandlers'
import { spaceHandlers } from './spaceHandlers'
import { assetHandlers } from './assetHandlers'
import { userHandlers } from './userHandlers'
import { groupHandlers } from './groupHandlers'
import { modelHandlers } from './modelHandlers'
import { connectionProfileHandlers } from './connectionProfileHandlers'
import { connectedAccountHandlers } from './connectedAccountHandlers'
import { connectionLogHandlers } from './connectionLogHandlers'
import { identityProviderHandlers } from './identityProviderHandlers'
import { searchAdminHandlers } from './searchAdminHandlers'
import { diagnosticAccessHandlers } from './diagnosticAccessHandlers'
import { externalAccessLibraryHandlers } from './externalAccessLibraryHandlers'
import { libraryDocumentHandlers } from './libraryDocumentHandlers'
import { libraryMetadataHandlers } from './libraryMetadataHandlers'
import { documentMetadataHandlers } from './documentMetadataHandlers'
import { libraryFolderHandlers } from './libraryFolderHandlers'
import { capabilityHandlers } from './capabilityHandlers'
import { authHandlers } from './authHandlers'
import { brandingHandlers } from './brandingHandlers'
import { mailHandlers } from './mailHandlers'
import { localUserHandlers } from './localUserHandlers'
import { accountHandlers } from './accountHandlers'
import { localAuthHandlers } from './localAuthHandlers'
import { externalAccessHandlers } from './externalAccessHandlers'
import { externalAccessTokenHandlers } from './externalAccessTokenHandlers'
import { groupAdminHandlers } from './groupAdminHandlers'
import { successionHandlers } from './successionHandlers'
import { promptLibraryHandlers } from './promptLibraryHandlers'
import { catalogHandlers } from './catalogHandlers'
import { privateStorageHandlers } from './privateStorageHandlers'

/**
 * All MSW handlers, one module per topic of the OpenAPI spec, for the browser worker and the test
 * server. No two topics share a method and path pattern, so the order of the modules below is free.
 * MSW answers with the first match, so order matters inside a module: a literal segment stays ahead
 * of a parameter at the same position (`GET /groups/selectable` before `GET /groups/:groupId`). A
 * handler whose path could match another topic's belongs into that topic's module.
 */
export const handlers = [
  ...healthHandlers,
  ...indexingHandlers,
  ...libraryHandlers,
  ...queryHandlers,
  ...searchHandlers,
  ...chatHandlers,
  ...spaceHandlers,
  ...assetHandlers,
  ...userHandlers,
  ...groupHandlers,
  ...modelHandlers,
  ...connectionProfileHandlers,
  ...connectedAccountHandlers,
  ...connectionLogHandlers,
  ...identityProviderHandlers,
  ...searchAdminHandlers,
  ...diagnosticAccessHandlers,
  ...externalAccessLibraryHandlers,
  ...libraryDocumentHandlers,
  ...libraryMetadataHandlers,
  ...documentMetadataHandlers,
  ...libraryFolderHandlers,
  ...capabilityHandlers,
  ...authHandlers,
  ...brandingHandlers,
  ...mailHandlers,
  ...localUserHandlers,
  ...accountHandlers,
  ...localAuthHandlers,
  ...externalAccessHandlers,
  ...externalAccessTokenHandlers,
  ...groupAdminHandlers,
  ...successionHandlers,
  ...promptLibraryHandlers,
  ...catalogHandlers,
  ...privateStorageHandlers,
]
