import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import {
  EMPTY_NEXTCLOUD_VALUES,
  nextcloudCredentialsOf,
  nextcloudFoldersOf,
  nextcloudSettingsOf,
  nextcloudIssue,
  type NextcloudSourceValues,
} from '../../utils/nextcloudSource'
import FolderShareForm, { type FolderShareShape } from '../sourceForms/FolderShareForm'
import { collectIssues, unifyMissing } from '../sourceKit/collectIssues'
import { folderSummary } from '../sourceKit/summaries'
import { NextcloudScope, NextcloudStoredView } from './NextcloudSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceFormContext, SourceRegistration } from './types'

const nextcloudShape: FolderShareShape<NextcloudSourceValues> = {
  type: 'NEXTCLOUD',
  rootLabel: 'Nextcloud',
  userKey: 'username',
  secretKey: 'appPassword',
  addressPlaceholder: 'https://cloud.example.org',
  addressHint: 'Auch die WebDAV-Adresse wird angenommen.',
  userPlaceholder: 'svc-opaa',
  secretHint: 'App-Passwort aus Einstellungen › Sicherheit',
  credentialsOf: nextcloudCredentialsOf,
  keepsCredentials: sameLibrarySourceOrigin,
}

const credentialsKept = (values: NextcloudSourceValues, context: SourceFormContext) =>
  !connectionFields(context).asksSecret ||
  (context.credentialsStored &&
    sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl))

const nextcloudIssues = (values: NextcloudSourceValues, context: SourceFormContext) =>
  unifyMissing(
    collectIssues(
      values,
      (v) => nextcloudIssue(v, credentialsKept(v, context)),
      { sourceUrl: 'https://stand.in', username: 'stand-in', appPassword: 'stand-in' },
      (field) => (field === 'folders' ? 'selection' : field),
    ),
    values,
    { sourceUrl: 'die Adresse', username: 'die Kennung', appPassword: 'das Kennwort' },
  )

export const nextcloudSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.NEXTCLOUD,
  containerLabel: 'Ordner',
  configuration: {
    empty: EMPTY_NEXTCLOUD_VALUES,
    draftFields: ['sourceUrl', 'username', 'folders'],
    // the stored credentials stand until new ones are typed; they are never returned
    fromLibrary: (library): NextcloudSourceValues => ({
      ...EMPTY_NEXTCLOUD_VALUES,
      sourceUrl: library.sourceUrl ?? '',
      folders: (nextcloudSettingsOf(library).folders ?? ['/']).join('\n'),
    }),
    isDirty: (values: NextcloudSourceValues) =>
      values.sourceUrl !== '' ||
      values.username !== '' ||
      values.appPassword !== '' ||
      values.folders.trim() !== '/',
    issues: nextcloudIssues,
    validate: (values: NextcloudSourceValues, context) =>
      nextcloudIssues(values, context)[0] ?? null,
    summary: (values: NextcloudSourceValues) => [
      { label: 'Adresse', value: values.sourceUrl.trim() },
      folderSummary(values.folders),
    ],
    toPayload: (values: NextcloudSourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceCredentials: nextcloudCredentialsOf(values),
      sourceSettings: { folders: nextcloudFoldersOf(values) },
    }),
    nameFromSource: (values: NextcloudSourceValues) => {
      const folders = nextcloudFoldersOf(values)
      if (folders.length !== 1 || folders[0] === '/') return ''
      const segments = folders[0].split('/').filter(Boolean)
      return segments[segments.length - 1] ?? ''
    },
    Form: (props) => <FolderShareForm {...props} shape={nextcloudShape} />,
    StoredView: NextcloudStoredView,
    Scope: NextcloudScope,
    scopeHero: {
      summary: (library) => {
        const count = nextcloudSettingsOf(library).folders?.length ?? 0
        return count === 1 ? '1 Ordner' : `${count} Ordner`
      },
      unlistedWarning: (keys) =>
        (keys.length === 1
          ? `Der letzte Abgleich konnte den Ordner „${keys[0]}“ nicht auflisten; sein Bestand ist möglicherweise veraltet.`
          : `Der letzte Abgleich konnte die Ordner ${keys.map((key) => `„${key}“`).join(', ')} nicht auflisten; ihr Bestand ist möglicherweise veraltet.`) +
        ' Der Hinweis bleibt, bis ein Lauf wieder alle Ordner auflisten kann.',
      unlistedTestId: 'nextcloud-incomplete-listing-warning',
    },
    firstRunHint:
      'Der erste Lauf liest alle Ordner vollständig ein; danach fragt jeder Lauf nur geänderte Ordner ab.',
  },
}
