import FolderSharedIcon from '@mui/icons-material/FolderShared'
import {
  EMPTY_GOOGLE_DRIVE_VALUES,
  googleDrivePayloadOf,
  googleDriveScopeLabel,
  googleDriveScopesOf,
  googleDriveSettingsFromLibrary,
  validateGoogleDriveValues,
  type GoogleDriveSourceValues,
} from '../../../utils/googleDriveSource'
import GoogleDriveSourceForm from '../GoogleDriveSourceForm'
import { GoogleDriveScopeView, GoogleDriveStoredView } from './GoogleDriveSourceViews'
import { connectionFields } from './sourceConnection'
import type { SourceRegistration } from './types'

export const googleDriveSource: SourceRegistration = {
  label: 'Google Drive',
  shortLabel: 'Google Drive',
  description: 'Geteilte Ablagen und Ordner eines Google Workspace werden eingelesen.',
  Icon: FolderSharedIcon,
  containerLabel: 'Bereich',
  configuration: {
    empty: EMPTY_GOOGLE_DRIVE_VALUES,
    // ADR-0040: the stored key stands while the imitated account stays the same.
    fromLibrary: (library): GoogleDriveSourceValues => {
      const settings = googleDriveSettingsFromLibrary(library)
      return {
        ...EMPTY_GOOGLE_DRIVE_VALUES,
        subject: settings?.subject ?? '',
        storedSubject: settings?.subject ?? '',
        scopes: googleDriveScopesOf(settings),
        sourceProxy: library.sourceProxy ?? '',
        fullSyncIntervalDays: settings?.fullSyncIntervalDays ?? null,
      }
    },
    isDirty: (values: GoogleDriveSourceValues) =>
      values.keyFile !== '' || values.subject !== '' || values.scopes.length > 0,
    validate: (values: GoogleDriveSourceValues, context) =>
      connectionFields(context).asksSecret
        ? validateGoogleDriveValues(values, context.credentialsStored)
        : // without a key of the library's own, no key has to serve the imitated account
          validateGoogleDriveValues({ ...values, storedSubject: values.subject }, true),
    toPayload: googleDrivePayloadOf,
    nameFromSource: (values: GoogleDriveSourceValues) =>
      values.scopes.length === 1 ? googleDriveScopeLabel(values.scopes[0]) : '',
    Form: ({ values, onChange, context }) => (
      <GoogleDriveSourceForm
        mode={context.mode}
        idPrefix={`${context.idPrefix}-google-drive`}
        libraryId={context.libraryId}
        credentialsStored={context.credentialsStored}
        connection={connectionFields(context)}
        values={values}
        onChange={onChange}
      />
    ),
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
