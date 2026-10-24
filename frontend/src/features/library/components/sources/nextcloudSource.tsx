import { sameLibrarySourceOrigin } from '../../utils/librarySourceConfig'
import {
  EMPTY_NEXTCLOUD_VALUES,
  nextcloudCredentialsOf,
  nextcloudFoldersOf,
  nextcloudSettingsOf,
  nextcloudIssue,
  type NextcloudSourceValues,
} from '../../utils/nextcloudSource'
import ServerFolderSetup from '../setup/ServerFolderSetup'
import { accountIssues, addressIssue, folderListIssue } from '../setup/validation'
import { collectIssues } from '../setup/setupContext'
import { folderSummary } from '../setup/summaries'
import { MAX_NEXTCLOUD_FOLDERS } from '../../utils/nextcloudSource'
import type { SourceFormContext } from './types'
import { NextcloudScope, NextcloudStoredView } from './NextcloudSourceViews'
import { connectionFields } from './sourceConnection'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceRegistration } from './types'

function credentialsKept(values: NextcloudSourceValues, context: SourceFormContext) {
  return (
    !connectionFields(context).asksSecret ||
    (context.credentialsStored && sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl))
  )
}

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
    validateAll: (values: NextcloudSourceValues, context) => [
      ...collectIssues(addressIssue('sourceUrl', values.sourceUrl)),
      ...(connectionFields(context).asksSecret
        ? accountIssues(
            { field: 'username', value: values.username },
            { field: 'appPassword', value: values.appPassword },
            credentialsKept(values, context),
          )
        : []),
      ...collectIssues(folderListIssue(values.folders, MAX_NEXTCLOUD_FOLDERS)),
    ],
    checkStages: ['address', 'signIn', 'access'],
    summary: (values: NextcloudSourceValues, context) => ({
      connection: [{ label: 'Adresse', value: values.sourceUrl.trim(), mono: true }],
      credentials: connectionFields(context).asksSecret
        ? [{ label: 'Kennung und Kennwort', value: values.username ? `${values.username} · Kennwort gesetzt` : 'gesetzt' }]
        : undefined,
      scope: folderSummary(values.folders),
    }),
    Form: ({ values, onChange, context }) => (
      <ServerFolderSetup<NextcloudSourceValues>
        values={values}
        onChange={onChange}
        context={context}
        addressLabel="Adresse der Nextcloud"
        addressPlaceholder="https://cloud.example.de"
        userField="username"
        secretField="appPassword"
        secretHint="Ein App-Kennwort aus den Nextcloud-Einstellungen."
        credentialsOf={nextcloudCredentialsOf}
        credentialsKept={credentialsKept(values, context)}
        payloadOf={(v) => ({
          sourceUrl: v.sourceUrl.trim(),
          sourceCredentials: nextcloudCredentialsOf(v),
          sourceSettings: { folders: nextcloudFoldersOf(v) },
        })}
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
