import {
  EMPTY_DROPBOX_VALUES,
  dropboxFoldersOf,
  dropboxSettingsFrom,
  dropboxSettingsOf,
  dropboxIssue,
  type DropboxSourceValues,
} from '../../utils/dropboxSource'
import DropboxForm from '../sourceForms/DropboxForm'
import { folderSummary } from '../sourceKit/summaries'
import { DropboxStoredView } from './DropboxSourceViews'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import { folderItem } from '../sourceKit/selectionSources'
import StoredSelection from '../sourceKit/StoredSelection'
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
    issues: (values: DropboxSourceValues) => {
      const issue = dropboxIssue(values)
      return issue ? [{ message: issue.message, field: 'selection' }] : []
    },
    validate: (values: DropboxSourceValues) => {
      const issue = dropboxIssue(values)
      return issue && { message: issue.message, field: 'selection' }
    },
    summary: (values: DropboxSourceValues) => [
      folderSummary(values.folders),
      { label: 'Team-Bereich', value: values.teamSpace ? 'Ja' : 'Nein' },
    ],
    toPayload: (values: DropboxSourceValues) => ({
      sourceSettings: dropboxSettingsFrom(values),
    }),
    nameFromSource: (values: DropboxSourceValues) => {
      const folders = dropboxFoldersOf(values)
      if (folders.length !== 1 || folders[0] === '/') return ''
      const segments = folders[0].split('/').filter(Boolean)
      return segments[segments.length - 1] ?? ''
    },
    Form: DropboxForm,
    StoredView: DropboxStoredView,
    Scope: ({ library }) => (
      <StoredSelection
        label="Ordner"
        items={(dropboxSettingsOf(library).folders ?? []).filter((f) => f !== '/').map(folderItem)}
        emptyText="Alle Ordner (/)"
      />
    ),
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
    runsHint:
      '„Jetzt indizieren“ liest in der Regel nur das Änderungsprotokoll seit dem letzten Lauf. Nach einer Änderung der Ordner, nach einem in Dropbox gelöschten, umbenannten oder verschobenen Ordner und im eingestellten Abstand läuft es automatisch als Vollabgleich, der alle Ordner vollständig listet und entfernt, was in Dropbox nicht mehr vorhanden ist. „Vollabgleich starten“ erzwingt ihn sofort.',
  },
}
