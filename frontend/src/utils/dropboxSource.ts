import { issueAt, messageOf, type SourceIssue } from './sourceIssue'
import type { LibraryResponse } from '../types/api'

/**
 * The connector settings of a DROPBOX library as the API carries them in `sourceSettings`: the
 * folders of the connected account that are read and whether they lie in the team space - never
 * how it signs in, which is the library's own consent through its profile.
 */
export type DropboxSettings = {
  folders?: string[] | null
  teamSpace?: boolean | null
  fullSyncIntervalDays?: number | null
}

/**
 * Everything the Dropbox source configuration consists of: the folders - one per line in the form -
 * and the team space switch. The address is fixed and the sign-in belongs to the profile.
 */
export interface DropboxSourceValues {
  folders: string
  teamSpace: boolean
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
  fullSyncIntervalDays: null,
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

/** The `sourceSettings` the values send, the stored rhythm included. */
export function dropboxSettingsFrom(values: DropboxSourceValues): Record<string, unknown> {
  return {
    folders: dropboxFoldersOf(values),
    ...(values.teamSpace ? { teamSpace: true } : {}),
    ...(values.fullSyncIntervalDays != null
      ? { fullSyncIntervalDays: values.fullSyncIntervalDays }
      : {}),
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
