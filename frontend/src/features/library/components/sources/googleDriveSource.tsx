import {
  EMPTY_GOOGLE_DRIVE_VALUES,
  googleDrivePayloadOf,
  googleDriveScopeLabel,
  googleDriveScopesOf,
  googleDriveSettingsFromLibrary,
  type GoogleDriveSourceValues,
} from '../../utils/googleDriveSource'
import { googleDriveSetup } from '../setup/connectors/googleDriveSetup'
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
    toPayload: googleDrivePayloadOf,
    nameFromSource: (values: GoogleDriveSourceValues) =>
      values.scopes.length === 1 ? googleDriveScopeLabel(values.scopes[0]) : '',
    scopeNoteId: 'google-drive-sharing-consequence',
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
      '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ alle Ablagen und Ordner neu.',
    setup: googleDriveSetup,
  },
}
