import type { SourceTypeKey } from '@/shared/api/types'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import { confluenceSource } from './confluenceSource'
import { dropboxSource } from './dropboxSource'
import { filesystemSource, httpDirectorySource, rssFeedSource } from './genericSources'
import { googleDriveSource } from './googleDriveSource'
import { nextcloudSource } from './nextcloudSource'
import { s3Source } from './s3Source'
import { sharePointSource } from './sharePointSource'
import { smbSource } from './smbSource'
import type { SourceRegistration } from './types'
import type { SourceSetupSpec } from '../setup/setupTypes'
import { confluenceSetup } from '../setup/connectors/confluenceSetup'
import { dropboxSetup } from '../setup/connectors/dropboxSetup'
import { filesystemSetup, urlSetup } from '../setup/connectors/genericSetups'
import { googleDriveSetup } from '../setup/connectors/googleDriveSetup'
import { nextcloudSetup } from '../setup/connectors/nextcloudSetup'
import { s3Setup } from '../setup/connectors/s3Setup'
import { sharePointSetup } from '../setup/connectors/sharePointSetup'
import { smbSetup } from '../setup/connectors/smbSetup'

/** The registration with the rows of the summary-row setup attached to its configuration. */
function withSetup<V>(
  registration: SourceRegistration,
  setup: SourceSetupSpec<V>,
): SourceRegistration {
  return registration.configuration
    ? { ...registration, configuration: { ...registration.configuration, setup } }
    : registration
}

const uploadSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.UPLOAD,
  configuration: null,
}

/**
 * The one place a source type's frontend is registered (ADR-0038): its names and symbol from the
 * connection directory and - for a type with a source - its form with starting values, validation
 * and request fields plus the read views of the Reiter „Quelle". The order is the wizard's. A type
 * the backend lists (GET /source-types) without an entry here is shown under its own display name
 * and cannot be configured in this client.
 */
const registrations: Record<SourceTypeKey, SourceRegistration> = {
  UPLOAD: uploadSource,
  FILESYSTEM: withSetup(filesystemSource, filesystemSetup),
  HTTP_DIRECTORY: withSetup(httpDirectorySource, urlSetup),
  RSS_FEED: withSetup(rssFeedSource, urlSetup),
  CONFLUENCE: withSetup(confluenceSource, confluenceSetup),
  S3: withSetup(s3Source, s3Setup),
  GOOGLE_DRIVE: withSetup(googleDriveSource, googleDriveSetup),
  SHAREPOINT: withSetup(sharePointSource, sharePointSetup),
  NEXTCLOUD: withSetup(nextcloudSource, nextcloudSetup),
  DROPBOX: withSetup(dropboxSource, dropboxSetup),
  SMB: withSetup(smbSource, smbSetup),
}

/** The registration of {@code sourceType}, undefined for a type this client has none for. */
export function sourceRegistration(
  sourceType: SourceTypeKey | null | undefined,
): SourceRegistration | undefined {
  return sourceType ? registrations[sourceType] : undefined
}

/** The source types with a registration, in the wizard's order. */
export const registeredSourceTypes: SourceTypeKey[] = Object.keys(registrations)
