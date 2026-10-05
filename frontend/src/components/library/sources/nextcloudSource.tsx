import CloudQueueIcon from '@mui/icons-material/CloudQueue'
import { sameLibrarySourceOrigin } from '../../../utils/librarySourceConfig'
import {
  EMPTY_NEXTCLOUD_VALUES,
  nextcloudCredentialsOf,
  nextcloudFoldersOf,
  nextcloudSettingsOf,
  validateNextcloudValues,
  type NextcloudSourceValues,
} from '../../../utils/nextcloudSource'
import NextcloudSourceForm from '../NextcloudSourceForm'
import { NextcloudScope, NextcloudStoredView } from './NextcloudSourceViews'
import { connectionFields } from './sourceConnection'
import type { SourceRegistration } from './types'

export const nextcloudSource: SourceRegistration = {
  label: 'Nextcloud',
  shortLabel: 'Nextcloud',
  description:
    'Ordner einer Nextcloud (auch openDesk) werden über einen technischen Nutzer eingelesen.',
  Icon: CloudQueueIcon,
  containerLabel: 'Ordner',
  configuration: {
    empty: EMPTY_NEXTCLOUD_VALUES,
    draftFields: ['sourceUrl', 'sourceProxy', 'sourceInsecureSsl', 'username', 'folders'],
    // the stored credentials stand until new ones are typed; they are never returned
    fromLibrary: (library): NextcloudSourceValues => ({
      ...EMPTY_NEXTCLOUD_VALUES,
      sourceUrl: library.sourceUrl ?? '',
      sourceProxy: library.sourceProxy ?? '',
      sourceInsecureSsl: Boolean(library.sourceInsecureSsl),
      folders: (nextcloudSettingsOf(library).folders ?? ['/']).join('\n'),
    }),
    isDirty: (values: NextcloudSourceValues) =>
      values.sourceUrl !== '' ||
      values.username !== '' ||
      values.appPassword !== '' ||
      values.folders.trim() !== '/',
    validate: (values: NextcloudSourceValues, context) =>
      validateNextcloudValues(
        values,
        !connectionFields(context).asksSecret ||
          (context.credentialsStored &&
            sameLibrarySourceOrigin(context.originalSourceUrl, values.sourceUrl)),
      ),
    toPayload: (values: NextcloudSourceValues) => ({
      sourceUrl: values.sourceUrl.trim(),
      sourceProxy: values.sourceProxy.trim() || undefined,
      sourceCredentials: nextcloudCredentialsOf(values),
      sourceInsecureSsl: values.sourceInsecureSsl,
      sourceSettings: { folders: nextcloudFoldersOf(values) },
    }),
    nameFromSource: (values: NextcloudSourceValues) => {
      const folders = nextcloudFoldersOf(values)
      if (folders.length !== 1 || folders[0] === '/') return ''
      const segments = folders[0].split('/').filter(Boolean)
      return segments[segments.length - 1] ?? ''
    },
    Form: ({ values, onChange, context }) => (
      <NextcloudSourceForm
        mode={context.mode}
        idPrefix={`${context.idPrefix}-nextcloud`}
        libraryId={context.libraryId}
        credentialsStored={context.credentialsStored}
        originalSourceUrl={context.originalSourceUrl}
        connection={connectionFields(context)}
        values={values}
        onChange={onChange}
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
