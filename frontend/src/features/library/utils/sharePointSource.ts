import type { LibraryResponse } from '@/shared/api/types'
import type { LibrarySourceConfigPayload } from './librarySourceConfig'

/** The fixed address of a SharePoint library, Microsoft Graph (ADR-0047); the form never shows it. */
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
  /**
   * The site the library was chosen in, stored with it so the selection dialog reopens there;
   * none for a library saved before the settings kept the site, until it is chosen anew.
   */
  site?: SharePointSite | null
}

/** A SharePoint site; `id` is the Graph site id the listing keys as `site:<id>`. */
export interface SharePointSite {
  id: string
  name: string | null
}

/**
 * Everything the form edits (ADR-0047): the document libraries with their
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
    site?: { id?: string; name?: string | null } | null
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
    const site = library.site?.id ? { id: library.site.id, name: library.site.name ?? null } : null
    return [
      { driveId: library.driveId, name: library.name ?? null, folders, ...(site && { site }) },
    ]
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

/** The name a document library listed for a site is stored under: its own and the site's. */
export function sharePointLibraryName(libraryName: string | null, siteName: string | null) {
  const name = libraryName?.trim() || null
  const site = siteName?.trim() || null
  const joined = name && site ? `${name} (${site})` : (name ?? site)
  return joined ? joined.slice(0, MAX_SHAREPOINT_NAME_LENGTH) : null
}

/** A name as the connector stores it: at most {@link MAX_SHAREPOINT_NAME_LENGTH} characters. */
export function graphShownName(name: string) {
  return name.slice(0, MAX_SHAREPOINT_NAME_LENGTH)
}

/** A folder path below the library's root, as its shown name. */
export function sharePointFolderPath(trail: string[], name: string | null) {
  return graphShownName([...trail, name?.trim() || '…'].join(' / '))
}

/** Whether the connector accepts the Graph ids of the library and of its folders. */
export function sharePointIdsValid(library: Pick<SharePointLibraryChoice, 'driveId' | 'folders'>) {
  return GRAPH_ID.test(library.driveId) && library.folders.every((f) => GRAPH_ID.test(f.id))
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
      ...(library.site
        ? {
            site: {
              id: library.site.id,
              ...(library.site.name ? { name: graphShownName(library.site.name) } : {}),
            },
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
    sourceSettings: sharePointSettingsOf(values),
  }
}
