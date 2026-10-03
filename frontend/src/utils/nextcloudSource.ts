import type { LibraryResponse } from '../types/api'

/**
 * The connector settings of a NEXTCLOUD library as the API carries them in `sourceSettings`
 * (ADR-0040): the folders of the technical user that are read, never how it signs in.
 */
export type NextcloudSettings = {
  folders?: string[] | null
}

/**
 * Everything the Nextcloud source configuration consists of: the instance address, the technical
 * user with its app password, proxy, certificate switch and the folders - one per line in the form.
 * Owned by the wizard or the edit dialog; the form only proposes changes through onChange.
 */
export interface NextcloudSourceValues {
  sourceUrl: string
  sourceProxy: string
  sourceInsecureSsl: boolean
  username: string
  appPassword: string
  folders: string
}

/** Mirrors NextcloudSourceSettings.MAX_FOLDERS - the backend rejects more with 400. */
export const MAX_NEXTCLOUD_FOLDERS = 50

export const EMPTY_NEXTCLOUD_VALUES: NextcloudSourceValues = {
  sourceUrl: '',
  sourceProxy: '',
  sourceInsecureSsl: false,
  username: '',
  appPassword: '',
  folders: '/',
}

/** The Nextcloud settings a library response carries, empty for any other library. */
export function nextcloudSettingsOf(
  library: Pick<LibraryResponse, 'sourceType' | 'sourceSettings'> | null | undefined,
): NextcloudSettings {
  if (library?.sourceType !== 'NEXTCLOUD' || !library.sourceSettings) return {}
  return library.sourceSettings as NextcloudSettings
}

/** The folders of the form, one per line, without blanks and duplicates. */
export function nextcloudFoldersOf(values: Pick<NextcloudSourceValues, 'folders'>): string[] {
  const folders: string[] = []
  for (const line of values.folders.split('\n')) {
    const folder = line.trim()
    if (folder !== '' && !folders.includes(folder)) folders.push(folder)
  }
  return folders
}

/** `Benutzername:App-Passwort` once both are typed, undefined to keep the stored ones. */
export function nextcloudCredentialsOf(values: NextcloudSourceValues): string | undefined {
  const username = values.username.trim()
  return username !== '' && values.appPassword !== ''
    ? `${username}:${values.appPassword}`
    : undefined
}

/**
 * The German rejection of the values, or null when they may be sent. `credentialsKept` is true
 * while stored credentials stand for the same address (edit mode).
 */
export function validateNextcloudValues(
  values: NextcloudSourceValues,
  credentialsKept: boolean,
): string | null {
  const url = values.sourceUrl.trim()
  if (url === '') return 'Bitte die Adresse der Nextcloud eingeben'
  if (!/^https?:\/\//i.test(url)) return 'Die Adresse muss mit http:// oder https:// beginnen'
  const username = values.username.trim()
  if (username.includes(':')) return 'Der Benutzername darf keinen Doppelpunkt enthalten'
  const partial = (username === '') !== (values.appPassword === '')
  if (partial) return 'Bitte Benutzername und App-Passwort zusammen eingeben'
  if (username === '' && !credentialsKept) {
    return 'Bitte Benutzername und App-Passwort des technischen Nutzers eingeben'
  }
  const folders = nextcloudFoldersOf(values)
  if (folders.length === 0) return 'Bitte mindestens einen Ordner angeben, / für alles'
  if (folders.length > MAX_NEXTCLOUD_FOLDERS) {
    return `Höchstens ${MAX_NEXTCLOUD_FOLDERS} Ordner je Bibliothek`
  }
  if (folders.some((folder) => folder.includes(',')))
    return 'Ein Ordnerpfad darf kein Komma enthalten'
  return null
}
