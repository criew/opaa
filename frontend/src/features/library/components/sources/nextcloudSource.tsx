import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import {
  EMPTY_NEXTCLOUD_VALUES,
  nextcloudCredentialsOf,
  nextcloudFoldersOf,
  nextcloudSettingsOf,
  nextcloudIssue,
  type NextcloudSourceValues,
} from '../../utils/nextcloudSource'
import AccountFolderForm from '../setup/forms/AccountFolderForm'
import { collectIssues } from '../setup/collectIssues'
import { pickedOfFolders } from '../setup/pathSelection'
import { MAX_NEXTCLOUD_FOLDERS } from '../../utils/nextcloudSource'
import { NextcloudScope, NextcloudStoredView } from './NextcloudSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

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
    validate: (values: NextcloudSourceValues, context) =>
      nextcloudIssue(
        values,
        !connectionFields(context).asksSecret ||
          (context.credentialsStored &&
            sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)),
      ),
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
    validateAll: (values: NextcloudSourceValues, context) =>
      collectIssues(values, (probe) => nextcloudSource.configuration!.validate(probe, context), {
        sourceUrl: 'https://cloud.example.org',
        username: 'konto',
        appPassword: 'passwort',
      }),
    summary: (values: NextcloudSourceValues, context) => ({
      connection: [
        { label: 'Adresse', value: values.sourceUrl.trim(), mono: true },
        {
          label: 'Konto',
          value: values.username.trim() || (context.credentialsStored ? 'gespeichert' : ''),
        },
      ],
      selection: {
        rootLabel: 'Nextcloud',
        noun: { one: 'Ordner', many: 'Ordner' },
        items: pickedOfFolders(nextcloudFoldersOf(values)),
        empty: 'Die ganze Nextcloud',
      },
    }),
    Form: ({ values, onChange, context }) => (
      <AccountFolderForm<NextcloudSourceValues>
        sourceType="NEXTCLOUD"
        values={values}
        onChange={onChange}
        context={context}
        accountKey="username"
        secretKey="appPassword"
        secretLabel="App-Passwort"
        addressPlaceholder="https://cloud.example.org"
        addressHint="Auch die WebDAV-Adresse wird angenommen."
        rootLabel="Nextcloud"
        credentialsOf={nextcloudCredentialsOf}
        foldersOf={nextcloudFoldersOf}
        keepsSecret={(current) =>
          sameLibrarySourceOrigin(context.originalSourceUrl, current.sourceUrl)
        }
        max={MAX_NEXTCLOUD_FOLDERS}
      />
    ),
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
