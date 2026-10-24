import {
  EMPTY_DROPBOX_VALUES,
  dropboxFoldersOf,
  dropboxSettingsFrom,
  dropboxSettingsOf,
  dropboxIssue,
  type DropboxSourceValues,
} from '../../utils/dropboxSource'
import DropboxSourceForm from '../DropboxSourceForm'
import { DropboxScope, DropboxStoredView } from './DropboxSourceViews'
import { connectionFields } from './sourceConnection'
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
    validate: (values: DropboxSourceValues) => dropboxIssue(values),
    toPayload: (values: DropboxSourceValues) => ({
      sourceSettings: dropboxSettingsFrom(values),
    }),
    nameFromSource: (values: DropboxSourceValues) => {
      const folders = dropboxFoldersOf(values)
      if (folders.length !== 1 || folders[0] === '/') return ''
      const segments = folders[0].split('/').filter(Boolean)
      return segments[segments.length - 1] ?? ''
    },
    Form: ({ values, onChange, context }) => (
      <DropboxSourceForm
        mode={context.mode}
        idPrefix={`${context.idPrefix}-dropbox`}
        connection={connectionFields(context)}
        values={values}
        onChange={onChange}
      />
    ),
    StoredView: DropboxStoredView,
    Scope: DropboxScope,
    scopeNoteId: 'dropbox-sharing-consequence',
    scopeHero: {
      summary: (library) => {
        const count = dropboxSettingsOf(library).folders?.length ?? 0
        return count === 1 ? '1 Ordner' : `${count} Ordner`
      },
      unlistedWarning: (keys) =>
        (keys.length === 1
          ? `Der letzte Lauf konnte den Ordner „${keys[0]}“ nicht auflisten; sein Bestand ist möglicherweise veraltet.`
          : `Der letzte Lauf konnte die Ordner ${keys.map((key) => `„${key}“`).join(', ')} nicht auflisten; ihr Bestand ist möglicherweise veraltet.`) +
        ' Der Hinweis bleibt, bis ein Vollabgleich wieder alle Ordner auflisten kann.',
      unlistedTestId: 'dropbox-incomplete-listing-warning',
    },
    fullSyncRhythm: true,
    rhythmSettingsBase: (library) => library.sourceSettings ?? null,
    firstRunHint:
      'Der erste Lauf ist ein Vollabgleich über alle Ordner; danach liest jeder Lauf nur das Änderungsprotokoll von Dropbox.',
    runsHint: '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ alle Ordner neu.',
  },
}
