import {
  EMPTY_DROPBOX_VALUES,
  dropboxFoldersOf,
  dropboxSettingsFrom,
  dropboxSettingsOf,
  type DropboxSourceValues,
} from '../../utils/dropboxSource'
import { dropboxSetup } from '../setup/connectors/dropboxSetup'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

export const dropboxSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.DROPBOX,
  containerLabel: 'Ordner',
  configuration: {
    empty: EMPTY_DROPBOX_VALUES,
    draftFields: ['folders', 'teamSpace', 'fullSyncIntervalDays'],
    fromLibrary: (library): DropboxSourceValues => {
      const settings = dropboxSettingsOf(library)
      return {
        folders: (settings.folders ?? ['/']).join('\n'),
        teamSpace: Boolean(settings.teamSpace),
        fullSyncIntervalDays: settings.fullSyncIntervalDays ?? null,
      }
    },
    isDirty: (values: DropboxSourceValues) => values.folders.trim() !== '/' || values.teamSpace,
    toPayload: (values: DropboxSourceValues) => ({
      sourceSettings: dropboxSettingsFrom(values),
    }),
    nameFromSource: (values: DropboxSourceValues) => {
      const folders = dropboxFoldersOf(values)
      if (folders.length !== 1 || folders[0] === '/') return ''
      const segments = folders[0].split('/').filter(Boolean)
      return segments[segments.length - 1] ?? ''
    },
    scopeNoteId: 'dropbox-sharing-consequence',
    fullSyncRhythm: true,
    rhythmSettingsBase: (library) => library.sourceSettings ?? null,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle Ordner; danach liest jeder Lauf nur das Änderungsprotokoll von Dropbox.',
    runsHint: '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ alle Ordner neu.',
    setup: dropboxSetup,
  },
}
