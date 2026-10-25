import {
  EMPTY_NEXTCLOUD_VALUES,
  nextcloudCredentialsOf,
  nextcloudFoldersOf,
  nextcloudSettingsOf,
  type NextcloudSourceValues,
} from '../../utils/nextcloudSource'
import { nextcloudSetup } from '../setup/connectors/nextcloudSetup'
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
    scopeNoteId: 'nextcloud-sharing-consequence',
    firstRunHint:
      'Der erste Lauf liest alle Ordner vollständig ein; danach fragt jeder Lauf nur geänderte Ordner ab.',
    setup: nextcloudSetup,
  },
}
