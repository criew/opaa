import {
  EMPTY_GOOGLE_DRIVE_VALUES,
  googleDrivePayloadOf,
  googleDriveScopeLabel,
  googleDriveScopesOf,
  googleDriveSettingsFromLibrary,
  googleDriveIssue,
  type GoogleDriveSourceValues,
} from '../../utils/googleDriveSource'
import DriveForm from '../sourceForms/DriveForm'
import { listSummary } from '../sourceKit/summaries'
import { driveItem } from '../sourceKit/selectionSources'
import StoredSelection from '../sourceKit/StoredSelection'
import type { SourceIssue } from '../../utils/sourceIssue'
import { GoogleDriveStoredView } from './GoogleDriveSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceFormContext, SourceRegistration } from './types'

/** Every issue at once: key, areas and imitated account are independent of one another. */
function driveIssues(values: GoogleDriveSourceValues, context: SourceFormContext): SourceIssue[] {
  const asks = connectionFields(context).asksSecret
  const check = (v: GoogleDriveSourceValues) =>
    asks
      ? googleDriveIssue(v, context.credentialsStored)
      : googleDriveIssue({ ...v, storedSubject: v.subject }, true)
  const issues: SourceIssue[] = []
  const key = check({
    ...values,
    scopes: [{ kind: 'drive', id: 'x', name: null }],
    subject: values.subject,
  })
  if (key && !key.field) issues.push({ message: key.message, field: 'keyFile' })
  const withKey = { ...values, keyFile: values.keyFile || 'stand-in' }
  const scope = check({ ...withKey, subject: '' })
  if (scope && !scope.field) issues.push({ message: scope.message, field: 'selection' })
  const subject = check(withKey)
  if (subject?.field === 'subject') issues.push(subject)
  return issues
}

export const googleDriveSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.GOOGLE_DRIVE,
  containerLabel: 'Bereich',
  configuration: {
    empty: EMPTY_GOOGLE_DRIVE_VALUES,
    draftFields: ['subject', 'storedSubject', 'scopes', 'fullSyncIntervalDays'],
    // ADR-0040: the stored key stands while the imitated account stays the same.
    fromLibrary: (library): GoogleDriveSourceValues => {
      const settings = googleDriveSettingsFromLibrary(library)
      return {
        ...EMPTY_GOOGLE_DRIVE_VALUES,
        subject: settings?.subject ?? '',
        storedSubject: settings?.subject ?? '',
        scopes: googleDriveScopesOf(settings),
        fullSyncIntervalDays: settings?.fullSyncIntervalDays ?? null,
      }
    },
    isDirty: (values: GoogleDriveSourceValues) =>
      values.keyFile !== '' || values.subject !== '' || values.scopes.length > 0,
    issues: driveIssues,
    validate: (values: GoogleDriveSourceValues, context) => driveIssues(values, context)[0] ?? null,
    summary: (values: GoogleDriveSourceValues) => [
      ...(values.subject.trim()
        ? [{ label: 'Imitiertes Konto', value: values.subject.trim() }]
        : []),
      listSummary('Ablagen und Ordner', values.scopes.map(googleDriveScopeLabel), 'Keine'),
    ],
    toPayload: googleDrivePayloadOf,
    nameFromSource: (values: GoogleDriveSourceValues) =>
      values.scopes.length === 1 ? googleDriveScopeLabel(values.scopes[0]) : '',
    Form: DriveForm,
    StoredView: GoogleDriveStoredView,
    Scope: ({ library }) => (
      <StoredSelection
        label="Ablagen und Ordner"
        items={googleDriveScopesOf(googleDriveSettingsFromLibrary(library)).map(driveItem)}
        emptyText="Keine"
      />
    ),
    scopeHero: {
      summary: (library) => {
        const count = googleDriveScopesOf(googleDriveSettingsFromLibrary(library)).length
        return count === 1 ? '1 Bereich' : `${count} Bereiche`
      },
      unlistedWarning: (keys) =>
        (keys.length === 1
          ? `Der letzte Lauf konnte den Bereich „${keys[0]}“ nicht erreichen; sein Bestand ist möglicherweise veraltet.`
          : `Der letzte Lauf konnte die Bereiche ${keys.map((key) => `„${key}“`).join(', ')} nicht erreichen; ihr Bestand ist möglicherweise veraltet.`) +
        ' Der Hinweis bleibt, bis ein Vollabgleich wieder alle Bereiche auflisten kann.',
      unlistedTestId: 'google-drive-incomplete-listing-warning',
    },
    fullSyncRhythm: true,
    rhythmSettingsBase: (library) => library.sourceSettings ?? null,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle Bereiche; danach liest jeder Lauf nur das Änderungsprotokoll von Google Drive.',
    runsHint:
      '„Jetzt indizieren“ liest in der Regel nur das Änderungsprotokoll seit dem letzten Lauf. Nach einer Änderung der Bereiche, einer Ordneränderung in Drive und im eingestellten Abstand läuft es automatisch als Vollabgleich, der alle Bereiche vollständig listet und entfernt, was in Drive nicht mehr vorhanden ist. „Vollabgleich starten“ erzwingt ihn sofort.',
  },
}
