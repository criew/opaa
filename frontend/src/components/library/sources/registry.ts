import UploadFileIcon from '@mui/icons-material/UploadFile'
import type { SourceTypeKey } from '../../../types/api'
import { confluenceSource } from './confluenceSource'
import { filesystemSource, httpDirectorySource, rssFeedSource } from './genericSources'
import { googleDriveSource } from './googleDriveSource'
import { nextcloudSource } from './nextcloudSource'
import { s3Source } from './s3Source'
import type { SourceRegistration } from './types'

const uploadSource: SourceRegistration = {
  label: 'Upload',
  shortLabel: 'Upload',
  description: 'Dateien auswählen oder hineinziehen; einzelne Dokumente pflegen.',
  Icon: UploadFileIcon,
  configuration: null,
}

/**
 * The one place a source type's frontend is registered (ADR-0038): its names, its symbol, and -
 * for a type with a source - its form with starting values, validation and request fields plus the
 * read views of the Reiter „Quelle". The order is the wizard's. A type the backend lists
 * (GET /source-types) without an entry here is shown under its own display name and cannot be
 * configured in this client.
 */
const registrations: Record<SourceTypeKey, SourceRegistration> = {
  UPLOAD: uploadSource,
  FILESYSTEM: filesystemSource,
  HTTP_DIRECTORY: httpDirectorySource,
  RSS_FEED: rssFeedSource,
  CONFLUENCE: confluenceSource,
  S3: s3Source,
  GOOGLE_DRIVE: googleDriveSource,
  NEXTCLOUD: nextcloudSource,
}

/** The registration of {@code sourceType}, undefined for a type this client has none for. */
export function sourceRegistration(
  sourceType: SourceTypeKey | null | undefined,
): SourceRegistration | undefined {
  return sourceType ? registrations[sourceType] : undefined
}

/** The source types with a registration, in the wizard's order. */
export const registeredSourceTypes: SourceTypeKey[] = Object.keys(registrations)
