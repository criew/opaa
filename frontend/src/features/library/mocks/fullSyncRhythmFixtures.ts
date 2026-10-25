import type { FullSyncRhythmResponse } from '@/shared/api/types'

/** The source types with a full-sync rhythm and their factory defaults, as the backend lists them. */
export const mockFullSyncRhythmDefaults: ReadonlyArray<
  Pick<FullSyncRhythmResponse, 'sourceType' | 'displayName' | 'defaultIntervalDays'>
> = [
  { sourceType: 'CONFLUENCE', displayName: 'Confluence', defaultIntervalDays: 7 },
  { sourceType: 'DROPBOX', displayName: 'Dropbox', defaultIntervalDays: 7 },
  { sourceType: 'GOOGLE_DRIVE', displayName: 'Google Drive', defaultIntervalDays: 7 },
  { sourceType: 'ONEDRIVE', displayName: 'OneDrive', defaultIntervalDays: 1 },
  { sourceType: 'SHAREPOINT', displayName: 'SharePoint', defaultIntervalDays: 1 },
]
