import type { LibraryResponse } from '../types/api'

/**
 * The connector settings of an SMB library as the API carries them in `sourceSettings`
 * (ADR-0040, Nachtrag SMB): the folders of the share that are read, never how it signs in.
 */
export type SmbSettings = {
  folders?: string[] | null
}

/**
 * Everything the SMB source configuration consists of: the share address, the service account
 * with its password and the folders - one per line in the form. Owned by the wizard or the edit
 * dialog; the form only proposes changes through onChange.
 */
export interface SmbSourceValues {
  sourceUrl: string
  account: string
  password: string
  folders: string
}

/** Mirrors SmbSourceSettings.MAX_FOLDERS - the backend rejects more with 400. */
export const MAX_SMB_FOLDERS = 50

export const EMPTY_SMB_VALUES: SmbSourceValues = {
  sourceUrl: '',
  account: '',
  password: '',
  folders: '/',
}

/** The SMB settings a library response carries, empty for any other library. */
export function smbSettingsOf(
  library: Pick<LibraryResponse, 'sourceType' | 'sourceSettings'> | null | undefined,
): SmbSettings {
  if (library?.sourceType !== 'SMB' || !library.sourceSettings) return {}
  return library.sourceSettings as SmbSettings
}

/** The folders of the form, one per line, without blanks and duplicates. */
export function smbFoldersOf(values: Pick<SmbSourceValues, 'folders'>): string[] {
  const folders: string[] = []
  for (const line of values.folders.split('\n')) {
    const folder = line.trim()
    if (folder !== '' && !folders.includes(folder)) folders.push(folder)
  }
  return folders
}

/** `DOMÄNE\Benutzer:Passwort` once both are typed, undefined to keep the stored ones. */
export function smbCredentialsOf(values: SmbSourceValues): string | undefined {
  const account = values.account.trim()
  return account !== '' && values.password !== '' ? `${account}:${values.password}` : undefined
}

/**
 * Server and port of a share address (`smb://server[:port]/freigabe` or `\\server\freigabe`) in
 * lower case, null when the address names none. The browser's URL parser knows no origin for
 * `smb:`, so this compares what the backend binds stored credentials to.
 */
export function smbServerOf(url: string | null | undefined): string | null {
  const text = (url ?? '').trim()
  const match = /^(?:smb:\/\/|\\\\)([^/\\]+)/i.exec(text)
  if (!match) return null
  const server = match[1].toLowerCase()
  return server.endsWith(':445') ? server.slice(0, -4) : server
}

/** The share a share address names, decoded and in lower case, null when it names none. */
export function smbShareOf(url: string | null | undefined): string | null {
  const match = /^(?:smb:\/\/|\\\\)[^/\\]+[/\\]+([^/\\]+)/i.exec((url ?? '').trim())
  if (!match) return null
  try {
    return decodeURIComponent(match[1]).toLowerCase()
  } catch {
    return match[1].toLowerCase()
  }
}

/**
 * Whether stored credentials for `previousUrl` still stand for `nextUrl`: the same server and the
 * same share, as the backend binds them - a share is a boundary of rights.
 */
export function sameSmbShare(previousUrl: string | null | undefined, nextUrl: string): boolean {
  const previous = smbServerOf(previousUrl)
  const share = smbShareOf(previousUrl)
  return (
    previous !== null &&
    share !== null &&
    previous === smbServerOf(nextUrl) &&
    share === smbShareOf(nextUrl)
  )
}

/**
 * The German rejection of the values, or null when they may be sent. `credentialsKept` is true
 * while stored credentials stand for the same server (edit mode).
 */
export function validateSmbValues(
  values: SmbSourceValues,
  credentialsKept: boolean,
): string | null {
  const url = values.sourceUrl.trim()
  if (url === '') return 'Bitte die Adresse der Freigabe eingeben'
  if (smbServerOf(url) === null) {
    return 'Die Adresse muss mit smb:// beginnen, etwa smb://dateiserver/Freigabe'
  }
  const path = url.replace(/^(?:smb:\/\/|\\\\)[^/\\]+/i, '').replace(/\\/g, '/')
  const segments = path.split('/').filter(Boolean)
  if (segments.length === 0) return 'Die Adresse nennt keine Freigabe (smb://server/freigabe)'
  if (segments.length > 1) {
    return 'Die Adresse nennt nur Server und Freigabe; Ordner darin werden unten eingetragen'
  }
  if (segments[0].endsWith('$')) {
    return 'Administrative Freigaben wie C$ werden nicht gelesen; bitte eine gewöhnliche Freigabe angeben'
  }
  const account = values.account.trim()
  if (account.includes(':')) return 'Das Dienstkonto darf keinen Doppelpunkt enthalten'
  const partial = (account === '') !== (values.password === '')
  if (partial) return 'Bitte Dienstkonto und Passwort zusammen eingeben'
  if (account === '' && !credentialsKept) {
    return 'Bitte Dienstkonto und Passwort eingeben'
  }
  const folders = smbFoldersOf(values)
  if (folders.length > MAX_SMB_FOLDERS) return `Höchstens ${MAX_SMB_FOLDERS} Ordner je Bibliothek`
  if (folders.some((folder) => folder.includes(',')))
    return 'Ein Ordnerpfad darf kein Komma enthalten'
  return null
}
