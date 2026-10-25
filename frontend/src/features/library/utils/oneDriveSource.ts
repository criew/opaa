import type { LibraryResponse } from '@/shared/api/types'
import type { LibrarySourceConfigPayload } from './librarySourceConfig'
import {
  SHAREPOINT_API,
  sharePointFolderLabel,
  sharePointIdsValid,
  type SharePointFolder,
} from './sharePointSource'

/** The fixed address of a OneDrive library, Microsoft Graph (ADR-0058); the form never shows it. */
export const ONEDRIVE_API = SHAREPOINT_API

/** Mirrors SharePointLibrary.MAX_FOLDERS - the backend rejects more with 400. */
export const MAX_ONEDRIVE_FOLDERS = 50

/** A folder of the OneDrive; `name` is its path below the root, only shown. */
export type OneDriveFolder = SharePointFolder

/**
 * Everything the form edits (ADR-0058): the one OneDrive of the connected account, found by the
 * listing rather than typed, the folders it is narrowed to and the library's own full-sync rhythm.
 * No address, no secret - the person's connected account signs in.
 */
export interface OneDriveSourceValues {
  driveId: string
  driveName: string | null
  /** The way of connection the OneDrive was found under; null for a stored one. Never sent. */
  driveFoundWith: string | null
  /** „Alle Ordner“: the folders below are kept while it is chosen, but not sent. */
  allFolders: boolean
  folders: OneDriveFolder[]
  /** The library's own full-sync rhythm, resent as it is: the connector replaces its settings whole. */
  fullSyncIntervalDays: number | null
}

export const EMPTY_ONEDRIVE_VALUES: OneDriveSourceValues = {
  driveId: '',
  driveName: null,
  driveFoundWith: null,
  allFolders: true,
  folders: [],
  fullSyncIntervalDays: null,
}

/** The connector settings of a OneDrive library as `sourceSettings` carries them. */
export type OneDriveSettings = {
  drive?: {
    driveId?: string
    name?: string | null
    folders?: Array<string | { id?: string; name?: string | null }>
  }
  fullSyncIntervalDays?: number | null
}

/** The OneDrive settings a library response carries, null for any other library. */
export function oneDriveSettingsFromLibrary(
  library: Pick<LibraryResponse, 'sourceType' | 'sourceSettings'> | null | undefined,
): OneDriveSettings | null {
  if (library?.sourceType !== 'ONEDRIVE' || !library.sourceSettings) return null
  return library.sourceSettings as unknown as OneDriveSettings
}

/** Stored settings in the form's shape; no folders means the whole OneDrive. */
export function oneDriveValuesOf(settings: OneDriveSettings | null): OneDriveSourceValues {
  const drive = settings?.drive
  const folders = (drive?.folders ?? []).flatMap((folder): OneDriveFolder[] => {
    if (typeof folder === 'string') return [{ id: folder, name: null }]
    return folder.id ? [{ id: folder.id, name: folder.name ?? null }] : []
  })
  return {
    driveId: drive?.driveId ?? '',
    driveName: drive?.name ?? null,
    driveFoundWith: null,
    allFolders: folders.length === 0,
    folders,
    fullSyncIntervalDays: settings?.fullSyncIntervalDays ?? null,
  }
}

/** The name the OneDrive is shown under; Graph's own name, else „OneDrive“. */
export function oneDriveLabel(values: Pick<OneDriveSourceValues, 'driveName'>): string {
  return values.driveName?.trim() || 'OneDrive'
}

/** The name a folder is shown under; a placeholder rather than the long Graph id. */
export function oneDriveFolderLabel(folder: OneDriveFolder): string {
  return sharePointFolderLabel(folder)
}

/** The folders a request names: none while „Alle Ordner“ is chosen. */
export function oneDriveChosenFolders(values: OneDriveSourceValues): OneDriveFolder[] {
  return values.allFolders ? [] : values.folders
}

/** Whether the connector accepts the Graph ids of the OneDrive and of its chosen folders. */
export function oneDriveIdsValid(values: OneDriveSourceValues): boolean {
  return sharePointIdsValid({ driveId: values.driveId, folders: oneDriveChosenFolders(values) })
}

/** The settings part of a request; null while no OneDrive is known yet. */
export function oneDriveSettingsOf(values: OneDriveSourceValues): Record<string, unknown> | null {
  if (values.driveId === '') return null
  const folders = oneDriveChosenFolders(values)
  return {
    drive: {
      driveId: values.driveId,
      ...(values.driveName ? { name: values.driveName } : {}),
      ...(folders.length > 0
        ? {
            folders: folders.map((folder) =>
              folder.name ? { id: folder.id, name: folder.name } : folder.id,
            ),
          }
        : {}),
    },
    ...(values.fullSyncIntervalDays != null
      ? { fullSyncIntervalDays: values.fullSyncIntervalDays }
      : {}),
  }
}

/** The source fields of a request: the fixed address and the settings, never a secret. */
export function oneDrivePayloadOf(values: OneDriveSourceValues): LibrarySourceConfigPayload {
  const settings = oneDriveSettingsOf(values)
  return { sourceUrl: ONEDRIVE_API, ...(settings ? { sourceSettings: settings } : {}) }
}
