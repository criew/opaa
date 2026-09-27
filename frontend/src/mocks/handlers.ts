import { healthHandlers } from './healthHandlers'
import { indexingHandlers } from './indexingHandlers'
import { libraryHandlers } from './libraryHandlers'
import { queryHandlers } from './queryHandlers'
import { chatHandlers } from './chatHandlers'
import { spaceHandlers } from './spaceHandlers'
import { assetHandlers } from './assetHandlers'
import { userHandlers } from './userHandlers'
import { groupHandlers } from './groupHandlers'
import { modelHandlers } from './modelHandlers'
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

/**
 * All MSW handlers, one module per topic of the OpenAPI spec, for the browser worker and the test
 * server. MSW answers with the first matching handler, so the order is part of the contract: a
 * topic whose paths overlap with another's must stay ahead of it.
 */
export const handlers = [
  ...healthHandlers,
  ...indexingHandlers,
  ...libraryHandlers,
  ...queryHandlers,
  ...chatHandlers,
  ...spaceHandlers,
  ...assetHandlers,
  ...userHandlers,
  ...groupHandlers,
  ...modelHandlers,
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
]
