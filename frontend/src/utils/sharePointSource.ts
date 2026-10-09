import type { LibraryResponse } from '../types/api'
import type { LibrarySourceConfigPayload } from './librarySourceConfig'

/** The fixed address of a SharePoint library, Microsoft Graph (ADR-0040); the form never shows it. */
export const SHAREPOINT_API = 'https://graph.microsoft.com'

export const MAX_SHAREPOINT_LIBRARIES = 50
export const MAX_SHAREPOINT_FOLDERS = 50

/** The upper bound of a name shown for a document library or a folder, in characters. */
export const MAX_SHAREPOINT_NAME_LENGTH = 200

/** Graph drive and item ids as the connector accepts them. */
const GRAPH_ID = /^[A-Za-z0-9!_-]{1,200}$/

/** A folder of a document library; `name` is its path below the library's root, only shown. */
export interface SharePointFolder {
  id: string
  name: string | null
}

/** One chosen document library, with the folders it is narrowed to - none for all of it. */
export interface SharePointLibraryChoice {
  driveId: string
  name: string | null
  folders: SharePointFolder[]
}

/**
 * Everything the form edits (ADR-0040, Nachtrag „SharePoint“): the document libraries with their
 * folders and the library's own full-sync rhythm. No secret - the profile signs in.
 */
export interface SharePointSourceValues {
  libraries: SharePointLibraryChoice[]
  /** The library's own full-sync rhythm, resent as it is: the connector replaces its settings whole. */
  fullSyncIntervalDays: number | null
}

export const EMPTY_SHAREPOINT_VALUES: SharePointSourceValues = {
  libraries: [],
  fullSyncIntervalDays: null,
}

/** The connector settings of a SharePoint library as `sourceSettings` carries them. */
export type SharePointSettings = {
  libraries?: Array<{
    driveId?: string
    name?: string | null
    folders?: Array<string | { id?: string; name?: string | null }>
  }>
  fullSyncIntervalDays?: number | null
}

/** The SharePoint settings a library response carries, null for any other library. */
export function sharePointSettingsFromLibrary(
  library: Pick<LibraryResponse, 'sourceType' | 'sourceSettings'> | null | undefined,
): SharePointSettings | null {
  if (library?.sourceType !== 'SHAREPOINT' || !library.sourceSettings) return null
  return library.sourceSettings as unknown as SharePointSettings
}

/** The document libraries of stored settings in the form's shape. */
export function sharePointLibrariesOf(
  settings: SharePointSettings | null,
): SharePointLibraryChoice[] {
  return (settings?.libraries ?? []).flatMap((library): SharePointLibraryChoice[] => {
    if (!library.driveId) return []
    const folders = (library.folders ?? []).flatMap((folder): SharePointFolder[] => {
      if (typeof folder === 'string') return [{ id: folder, name: null }]
      return folder.id ? [{ id: folder.id, name: folder.name ?? null }] : []
    })
    return [{ driveId: library.driveId, name: library.name ?? null, folders }]
  })
}

/** The name a document library is shown under; its id when none is known. */
export function sharePointLibraryLabel(library: Pick<SharePointLibraryChoice, 'driveId' | 'name'>) {
  return library.name?.trim() || library.driveId
}

/** The name a folder is shown under; a placeholder rather than the long Graph id. */
export function sharePointFolderLabel(folder: SharePointFolder) {
  return folder.name?.trim() || `Ordner ${folder.id}`
}

/** What a chosen library covers, in words: all of it or its folders. */
export function sharePointCoverageLabel(library: SharePointLibraryChoice) {
  if (library.folders.length === 0) return 'ganze Dokumentbibliothek'
  return `${library.folders.length === 1 ? 'Ordner' : `${library.folders.length} Ordner`}: ${library.folders
    .map(sharePointFolderLabel)
    .join(', ')}`
}

/** The name a document library listed for a site is stored under: its own and the site's. */
export function sharePointLibraryName(libraryName: string | null, siteName: string | null) {
  const name = libraryName?.trim() || null
  const site = siteName?.trim() || null
  const joined = name && site ? `${name} (${site})` : (name ?? site)
  return joined ? joined.slice(0, MAX_SHAREPOINT_NAME_LENGTH) : null
}

/** A folder path below the library's root, as its shown name. */
export function sharePointFolderPath(trail: string[], name: string | null) {
  const path = [...trail, name?.trim() || '…'].join(' / ')
  return path.slice(0, MAX_SHAREPOINT_NAME_LENGTH)
}

/** A listing key (`site:<id>`, `drive:<id>`, `folder:<id>`) split into its kind and id. */
export function sharePointKeyOf(
  key: string,
): { kind: 'site' | 'drive' | 'folder'; id: string } | null {
  const match = /^(site|drive|folder):(.+)$/.exec(key)
  if (!match) return null
  return { kind: match[1] as 'site' | 'drive' | 'folder', id: match[2] }
}

/** The German rejection of the values, null when they may be sent. */
export function validateSharePointValues(values: SharePointSourceValues): string | null {
  if (values.libraries.length === 0) {
    return 'Bitte mindestens eine Dokumentbibliothek wählen.'
  }
  if (values.libraries.length > MAX_SHAREPOINT_LIBRARIES) {
    return `Höchstens ${MAX_SHAREPOINT_LIBRARIES} Dokumentbibliotheken sind möglich.`
  }
  for (const library of values.libraries) {
    if (!GRAPH_ID.test(library.driveId) || library.folders.some((f) => !GRAPH_ID.test(f.id))) {
      return 'Eine Dokumentbibliothek oder ein Ordner hat keine gültige Kennung; bitte neu aus der Auflistung wählen.'
    }
    if (library.folders.length > MAX_SHAREPOINT_FOLDERS) {
      return `Höchstens ${MAX_SHAREPOINT_FOLDERS} Ordner je Dokumentbibliothek sind möglich („${sharePointLibraryLabel(library)}“).`
    }
  }
  return null
}

/** The settings part of a request. */
export function sharePointSettingsOf(values: SharePointSourceValues): Record<string, unknown> {
  return {
    libraries: values.libraries.map((library) => ({
      driveId: library.driveId,
      ...(library.name ? { name: library.name } : {}),
      ...(library.folders.length > 0
        ? {
            folders: library.folders.map((folder) =>
              folder.name ? { id: folder.id, name: folder.name } : folder.id,
            ),
          }
        : {}),
    })),
    ...(values.fullSyncIntervalDays != null
      ? { fullSyncIntervalDays: values.fullSyncIntervalDays }
      : {}),
  }
}

/** The source fields of a request: the fixed address and the settings, never a secret. */
export function sharePointPayloadOf(values: SharePointSourceValues): LibrarySourceConfigPayload {
  return {
    sourceUrl: SHAREPOINT_API,
    sourceInsecureSsl: false,
    sourceSettings: sharePointSettingsOf(values),
  }
}

/** One finding of the connection test per document library, from its `details.libraries`. */
export interface SharePointLibraryCheck {
  driveId: string
  reachable: boolean
  message?: string | null
}

export function sharePointLibraryChecksOf(
  details: Record<string, unknown> | null | undefined,
): SharePointLibraryCheck[] | null {
  const libraries = details?.libraries
  return Array.isArray(libraries) ? (libraries as SharePointLibraryCheck[]) : null
}
