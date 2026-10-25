import type { SourceTypeKey } from '@/shared/api/types'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import { confluenceSource } from './confluenceSource'
import { dropboxSource } from './dropboxSource'
import { filesystemSource, httpDirectorySource, rssFeedSource } from './genericSources'
import { googleDriveSource } from './googleDriveSource'
import { nextcloudSource } from './nextcloudSource'
import { oneDriveSource } from './oneDriveSource'
import { s3Source } from './s3Source'
import { sharePointSource } from './sharePointSource'
import { smbSource } from './smbSource'
import type { SourceRegistration } from './types'

const uploadSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.UPLOAD,
  configuration: null,
}

/**
 * The one place a source type's frontend is registered (ADR-0038): its names and symbol from the
 * connection directory and - for a type with a source - its setup rows with starting values and
 * request fields. The order is the wizard's. A type the backend lists (GET /source-types) without
 * an entry here is shown under its own display name and cannot be configured in this client.
 */
const registrations: Record<SourceTypeKey, SourceRegistration> = {
  UPLOAD: uploadSource,
  FILESYSTEM: filesystemSource,
  HTTP_DIRECTORY: httpDirectorySource,
  RSS_FEED: rssFeedSource,
  CONFLUENCE: confluenceSource,
  S3: s3Source,
  GOOGLE_DRIVE: googleDriveSource,
  SHAREPOINT: sharePointSource,
  ONEDRIVE: oneDriveSource,
  NEXTCLOUD: nextcloudSource,
  DROPBOX: dropboxSource,
  SMB: smbSource,
}

/** The registration of {@code sourceType}, undefined for a type this client has none for. */
export function sourceRegistration(
  sourceType: SourceTypeKey | null | undefined,
): SourceRegistration | undefined {
  return sourceType ? registrations[sourceType] : undefined
}

/** The source types with a registration, in the wizard's order. */
export const registeredSourceTypes: SourceTypeKey[] = Object.keys(registrations)
