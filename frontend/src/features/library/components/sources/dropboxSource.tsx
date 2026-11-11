import {
  EMPTY_DROPBOX_VALUES,
  dropboxSettingsFrom,
  dropboxSettingsOf,
  type DropboxSourceValues,
} from '../../utils/dropboxSource'
import { dropboxSetup } from '../setup/connectors/dropboxSetup'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from '../sourceCore/types'

export const dropboxSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.DROPBOX,
  containerLabel: 'Ordner',
  configuration: {
    empty: EMPTY_DROPBOX_VALUES,
    draftFields: ['folders', 'teamSpace', 'teamFolders', 'fullSyncIntervalDays'],
    fromLibrary: (library): DropboxSourceValues => {
      const settings = dropboxSettingsOf(library)
      return {
        folders: (settings.folders ?? ['/']).join('\n'),
        teamSpace: Boolean(settings.teamSpace),
        teamFolders: settings.teamFolders ?? [],
        fullSyncIntervalDays: settings.fullSyncIntervalDays ?? null,
      }
    },
    isDirty: (values: DropboxSourceValues) =>
      values.folders.trim() !== '/' || values.teamSpace || values.teamFolders.length > 0,
    toPayload: (values: DropboxSourceValues) => ({
      sourceSettings: dropboxSettingsFrom(values),
    }),
    scopeNoteId: 'dropbox-sharing-consequence',
    fullSyncRhythm: true,
    rhythmSettingsBase: (library) => library.sourceSettings ?? null,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle Ordner; danach liest jeder Lauf nur das Änderungsprotokoll von Dropbox.',
    runsHint: '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ alle Ordner neu.',
    setup: dropboxSetup,
  },
}
