import {
  EMPTY_GOOGLE_DRIVE_VALUES,
  googleDrivePayloadOf,
  googleDriveScopeLabel,
  googleDriveScopesOf,
  googleDriveSettingsFromLibrary,
  googleDriveIssue,
  type GoogleDriveSourceValues,
} from '../../utils/googleDriveSource'
import GoogleDriveForm, { pickedOfDriveScopes } from '../setup/forms/GoogleDriveForm'
import { issueAt, type SourceIssue } from '../../utils/sourceIssue'
import { GoogleDriveScopeView, GoogleDriveStoredView } from './GoogleDriveSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

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
    validate: (values: GoogleDriveSourceValues, context) =>
      connectionFields(context).asksSecret
        ? googleDriveIssue(values, context.credentialsStored)
        : // without a key of the library's own, no key has to serve the imitated account
          googleDriveIssue({ ...values, storedSubject: values.subject }, true),
    toPayload: googleDrivePayloadOf,
    nameFromSource: (values: GoogleDriveSourceValues) =>
      values.scopes.length === 1 ? googleDriveScopeLabel(values.scopes[0]) : '',
    validateAll: (values: GoogleDriveSourceValues, context) => {
      const issues: SourceIssue[] = []
      const first = (probe: GoogleDriveSourceValues) =>
        connectionFields(context).asksSecret
          ? googleDriveIssue(probe, context.credentialsStored)
          : googleDriveIssue({ ...probe, storedSubject: probe.subject }, true)
      const keyIssue = first({ ...values, scopes: [{ kind: 'drive', id: 'x', name: null }], subject: '' })
      if (keyIssue && !keyIssue.field) issues.push(issueAt('keyFile', keyIssue.message))
      const rest = first({ ...values, keyFile: values.keyFile || 'key' })
      if (rest) {
        issues.push(rest.field ? rest : issueAt('scopes', rest.message))
        if (rest.field === 'subject' && values.scopes.length === 0) {
          issues.push(issueAt('scopes', 'Bitte mindestens einen Bereich wählen.'))
        }
      }
      return issues
    },
    summary: (values: GoogleDriveSourceValues, context) => ({
      connection: [
        {
          label: 'Dienstkonto',
          value: values.keyAccount || (context.credentialsStored ? 'Schlüssel gespeichert' : context.connection?.name ?? ''),
        },
        { label: 'Im Namen von', value: values.subject.trim() },
      ],
      selection: {
        rootLabel: 'Google Drive',
        noun: { one: 'Bereich', many: 'Bereiche' },
        items: pickedOfDriveScopes(values.scopes),
        empty: 'Kein Bereich gewählt',
      },
    }),
    Form: GoogleDriveForm,
    StoredView: GoogleDriveStoredView,
    Scope: GoogleDriveScopeView,
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
