import { issueAt, messageOf, type SourceIssue } from './sourceIssue'
import type { LibraryResponse } from '@/shared/api/types'

/** A team folder of a team app: its namespace id and the name it was chosen under. */
export type DropboxTeamFolder = { id: string; name?: string | null }

/**
 * The connector settings of a DROPBOX library as the API carries them in `sourceSettings`: the
 * folders of the connected account that are read and whether they lie in the team space, or under
 * a team app the team folders - never how it signs in, which is the library's own consent through
 * its profile. `teamApp` is the profile's default, never sent by the form.
 */
export type DropboxSettings = {
  folders?: string[] | null
  teamSpace?: boolean | null
  teamFolders?: DropboxTeamFolder[] | null
  teamApp?: boolean | null
  fullSyncIntervalDays?: number | null
}

/**
 * Everything the Dropbox source configuration consists of: the folders - one per line in the form -
 * and the team space switch. The address is fixed and the sign-in belongs to the profile.
 */
export interface DropboxSourceValues {
  folders: string
  teamSpace: boolean
  /** Under a team app: the chosen team folders, read instead of `folders`. */
  teamFolders: DropboxTeamFolder[]
  /** The library's own full-sync rhythm, resent as it is: the connector replaces its settings whole. */
  fullSyncIntervalDays: number | null
}

/** The fixed address of the connector (its API host), the profile's server address. */
export const DROPBOX_API = 'https://api.dropboxapi.com'

/** Mirrors DropboxSettings.MAX_FOLDERS - the backend rejects more with 400. */
export const MAX_DROPBOX_FOLDERS = 50

export const EMPTY_DROPBOX_VALUES: DropboxSourceValues = {
  folders: '/',
  teamSpace: false,
  teamFolders: [],
  fullSyncIntervalDays: null,
}

/** Whether the Zugang is a team app: its libraries read team folders as the team admin. */
export function isDropboxTeamApp(
  connection: { defaults: Record<string, unknown> } | null | undefined,
): boolean {
  return connection?.defaults.teamApp === true
}

/** The name a team folder is shown under, its namespace id where it has none. */
export function dropboxTeamFolderLabel(folder: DropboxTeamFolder): string {
  return folder.name?.trim() || `Team-Ordner ${folder.id}`
}

/** The Dropbox settings a library response carries, empty for any other library. */
export function dropboxSettingsOf(
  library: Pick<LibraryResponse, 'sourceType' | 'sourceSettings'> | null | undefined,
): DropboxSettings {
  if (library?.sourceType !== 'DROPBOX' || !library.sourceSettings) return {}
  return library.sourceSettings as DropboxSettings
}

/** The folders of the form, one per line, without blanks and duplicates. */
export function dropboxFoldersOf(values: Pick<DropboxSourceValues, 'folders'>): string[] {
  const folders: string[] = []
  for (const line of values.folders.split('\n')) {
    const folder = line.trim()
    if (folder !== '' && !folders.includes(folder)) folders.push(folder)
  }
  return folders
}

/**
 * The `sourceSettings` the values send, the stored rhythm included: the team folders where any are
 * chosen, the folders otherwise.
 */
export function dropboxSettingsFrom(values: DropboxSourceValues): Record<string, unknown> {
  const rhythm =
    values.fullSyncIntervalDays != null ? { fullSyncIntervalDays: values.fullSyncIntervalDays } : {}
  if (values.teamFolders.length > 0) {
    return {
      teamFolders: values.teamFolders.map((folder) => ({
        id: folder.id,
        ...(folder.name ? { name: folder.name } : {}),
      })),
      ...rhythm,
    }
  }
  return {
    folders: dropboxFoldersOf(values),
    ...(values.teamSpace ? { teamSpace: true } : {}),
    ...rhythm,
  }
}

/** The German rejection of the values, or null when they may be sent. */
export function validateDropboxValues(values: DropboxSourceValues): string | null {
  return messageOf(dropboxIssue(values))
}

/** {@link validateDropboxValues} with the field the message is about. */
export function dropboxIssue(values: DropboxSourceValues): SourceIssue | null {
  const folders = dropboxFoldersOf(values)
  const at = (message: string) => issueAt('folders', message)
  if (folders.length === 0) return at('Bitte mindestens einen Ordner angeben, / für alles')
  if (folders.length > MAX_DROPBOX_FOLDERS) {
    return at(`Höchstens ${MAX_DROPBOX_FOLDERS} Ordner je Bibliothek`)
  }
  if (folders.some((folder) => folder.includes(','))) {
    return at('Ein Ordnerpfad darf kein Komma enthalten')
  }
  return null
}
