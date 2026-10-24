import { formIssue, issueAt, messageOf, type SourceIssue } from './sourceIssue'
import type { LibraryResponse } from '@/shared/api/types'
import type { LibrarySourceConfigPayload } from './librarySourceConfig'

/** The fixed address of a Google Drive library (ADR-0040); the form never shows it. */
export const GOOGLE_DRIVE_API = 'https://www.googleapis.com'

export const MAX_GOOGLE_DRIVE_SCOPES = 50

export type GoogleDriveScopeKind = 'drive' | 'folder' | 'myDrive'

/** One area of a Google Drive library: a shared drive, a folder or the imitated account's drive. */
export interface GoogleDriveScope {
  kind: GoogleDriveScopeKind
  id: string
  name: string | null
}

/** The connector settings of a Google Drive library as `sourceSettings` carries them. */
export type GoogleDriveSettings = {
  scopes?: Array<{ drive?: string; folder?: string; myDrive?: boolean; name?: string | null }>
  subject?: string | null
  fullSyncIntervalDays?: number | null
}

/**
 * Everything the form edits (ADR-0040): the uploaded key file - never shown, never returned -, the
 * account it belongs to, the imitated account and the scopes. `storedSubject` is the
 * imitated account the stored key serves; another one needs the key again.
 */
export interface GoogleDriveSourceValues {
  keyFile: string
  keyAccount: string
  subject: string
  storedSubject: string
  scopes: GoogleDriveScope[]
  /** The library's own full-sync rhythm, resent as it is: the connector replaces its settings whole. */
  fullSyncIntervalDays: number | null
}

export const EMPTY_GOOGLE_DRIVE_VALUES: GoogleDriveSourceValues = {
  keyFile: '',
  keyAccount: '',
  subject: '',
  storedSubject: '',
  scopes: [],
  fullSyncIntervalDays: null,
}

/** The Google Drive settings a library response carries, null for any other library. */
export function googleDriveSettingsFromLibrary(
  library: Pick<LibraryResponse, 'sourceType' | 'sourceSettings'> | null | undefined,
): GoogleDriveSettings | null {
  if (library?.sourceType !== 'GOOGLE_DRIVE' || !library.sourceSettings) return null
  return library.sourceSettings as unknown as GoogleDriveSettings
}

/** The scopes of stored settings in the form's shape. */
export function googleDriveScopesOf(settings: GoogleDriveSettings | null): GoogleDriveScope[] {
  return (settings?.scopes ?? []).flatMap((scope): GoogleDriveScope[] => {
    if (scope.drive) return [{ kind: 'drive', id: scope.drive, name: scope.name ?? null }]
    if (scope.folder) return [{ kind: 'folder', id: scope.folder, name: scope.name ?? null }]
    if (scope.myDrive) return [{ kind: 'myDrive', id: 'root', name: 'Meine Ablage' }]
    return []
  })
}

/** The key a listing entry or a scope is known by: `drive:<id>`, `folder:<id>` or `myDrive`. */
export function googleDriveScopeKey(scope: GoogleDriveScope): string {
  return scope.kind === 'myDrive' ? 'myDrive' : `${scope.kind}:${scope.id}`
}

/** A listing entry (`drive:<id>`, `folder:<id>`, `myDrive`) as a scope, null for another key. */
export function googleDriveScopeFromKey(key: string, name: string | null): GoogleDriveScope | null {
  if (key === 'myDrive') return { kind: 'myDrive', id: 'root', name: name ?? 'Meine Ablage' }
  const match = /^(drive|folder):([A-Za-z0-9_-]{1,200})$/.exec(key)
  if (!match) return null
  return { kind: match[1] as GoogleDriveScopeKind, id: match[2], name }
}

/** The name a scope is shown under; its id when Drive gave none. */
export function googleDriveScopeLabel(scope: GoogleDriveScope): string {
  return scope.name?.trim() || (scope.kind === 'myDrive' ? 'Meine Ablage' : scope.id)
}

/** Whether a stored key still serves the values: a key is stored and the imitated account stays. */
export function storedKeyServes(values: GoogleDriveSourceValues, credentialsStored: boolean) {
  return credentialsStored && values.subject.trim() === values.storedSubject.trim()
}

/** The German rejection of the values, null when they may be sent. */
export function validateGoogleDriveValues(
  values: GoogleDriveSourceValues,
  credentialsStored: boolean,
): string | null {
  return messageOf(googleDriveIssue(values, credentialsStored))
}

/** {@link validateGoogleDriveValues} with the field the message is about. */
export function googleDriveIssue(
  values: GoogleDriveSourceValues,
  credentialsStored: boolean,
): SourceIssue | null {
  if (values.keyFile === '' && !storedKeyServes(values, credentialsStored)) {
    return formIssue(
      credentialsStored
        ? 'Für ein anderes imitiertes Konto muss die Schlüsseldatei neu hochgeladen werden.'
        : 'Bitte die JSON-Schlüsseldatei des Dienstkontos hochladen.',
    )
  }
  if (values.scopes.length === 0) {
    return formIssue('Bitte mindestens einen Bereich wählen.')
  }
  if (values.scopes.length > MAX_GOOGLE_DRIVE_SCOPES) {
    return formIssue(`Höchstens ${MAX_GOOGLE_DRIVE_SCOPES} Bereiche sind möglich.`)
  }
  if (values.scopes.some((scope) => scope.kind === 'myDrive') && values.subject.trim() === '') {
    return issueAt(
      'subject',
      '„Meine Ablage“ setzt ein imitiertes Konto voraus; ein Dienstkonto besitzt keine Dateien.',
    )
  }
  const subject = values.subject.trim()
  if (subject !== '' && !/^[^@\s]+@[^@\s]+$/.test(subject)) {
    return issueAt('subject', 'Das imitierte Konto ist eine E-Mail-Adresse der Domäne.')
  }
  return null
}

/** The settings part of a request. */
export function googleDriveSettingsOf(values: GoogleDriveSourceValues): Record<string, unknown> {
  return {
    scopes: values.scopes.map((scope) =>
      scope.kind === 'myDrive'
        ? { myDrive: true }
        : { [scope.kind]: scope.id, ...(scope.name ? { name: scope.name } : {}) },
    ),
    subject: values.subject.trim() || null,
    ...(values.fullSyncIntervalDays != null
      ? { fullSyncIntervalDays: values.fullSyncIntervalDays }
      : {}),
  }
}

/** The source fields of a request; the key only when a new one was uploaded. */
export function googleDrivePayloadOf(values: GoogleDriveSourceValues): LibrarySourceConfigPayload {
  return {
    sourceUrl: GOOGLE_DRIVE_API,
    sourceCredentials: values.keyFile || undefined,
    sourceSettings: googleDriveSettingsOf(values),
  }
}
