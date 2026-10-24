import {
  EMPTY_GENERIC_SOURCE_VALUES,
  genericSourcePayload,
  storedGenericSourceValues,
  genericSourceIssue,
  type GenericSourceValues,
} from '../../utils/librarySourceConfig'
import {
  EMPTY_FILESYSTEM_VALUES,
  filesystemPayload,
  storedFilesystemValues,
  filesystemIssue,
  type FilesystemSourceValues,
} from '../../utils/filesystemSource'
import { StoredConnection, UrlForm } from './GenericSourceViews'
import FilesystemForm from '../setup/forms/FilesystemForm'
import { collectIssues } from '../setup/collectIssues'
import { filesystemPatternsOf } from '../../utils/filesystemSource'
import { SOURCE_TYPE_DIRECTORY } from '@/features/connection'
import type { SourceConfiguration, SourceRegistration } from './types'

const FIRST_RUN_HINT =
  'Der erste Lauf liest die Quelle vollständig ein; sein Stand bleibt auf der Detailseite sichtbar.'

function urlConfiguration(): SourceConfiguration<GenericSourceValues> {
  return {
    empty: EMPTY_GENERIC_SOURCE_VALUES,
    valuesKey: 'generic',
    draftFields: ['sourcePath', 'sourceUrl'],
    fromLibrary: storedGenericSourceValues,
    isDirty: (values) => values.sourcePath !== '' || values.sourceUrl !== '',
    validate: (values) => genericSourceIssue('url', values),
    toPayload: (values) => genericSourcePayload('url', values),
    nameFromSource: (values) => {
      try {
        return new URL(values.sourceUrl).hostname
      } catch {
        return ''
      }
    },
    Form: UrlForm,
    StoredView: ({ library, libraryId }) => (
      <StoredConnection library={library} libraryId={libraryId} kind="url" />
    ),
    firstRunHint: FIRST_RUN_HINT,
  }
}

/** The path form plus the exclusion patterns. */
function filesystemConfiguration(): SourceConfiguration<FilesystemSourceValues> {
  return {
    empty: EMPTY_FILESYSTEM_VALUES,
    draftFields: ['sourcePath', 'sourceUrl', 'excludePatterns'],
    fromLibrary: storedFilesystemValues,
    isDirty: (values) => values.sourcePath !== '' || values.excludePatterns.trim() !== '',
    validate: (values) => filesystemIssue(values),
    toPayload: filesystemPayload,
    nameFromSource: (values) => {
      const segments = values.sourcePath.split(/[\\/]+/).filter(Boolean)
      return segments.length > 0 ? segments[segments.length - 1] : ''
    },
    validateAll: (values) =>
      collectIssues(values, filesystemIssue, { sourcePath: '/pfad', excludePatterns: '' }),
    summary: (values) => ({
      connection: [
        { label: 'Pfad', value: values.sourcePath.trim(), mono: true },
        {
          label: 'Ausschlussmuster',
          value: filesystemPatternsOf(values.excludePatterns).join(' · ') || 'keine',
          mono: true,
        },
      ],
    }),
    Form: FilesystemForm,
    StoredView: ({ library, libraryId }) => (
      <StoredConnection library={library} libraryId={libraryId} kind="path" />
    ),
    firstRunHint: FIRST_RUN_HINT,
  }
}

export const filesystemSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.FILESYSTEM,
  configuration: filesystemConfiguration(),
}

export const httpDirectorySource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.HTTP_DIRECTORY,
  configuration: urlConfiguration(),
}

export const rssFeedSource: SourceRegistration = {
  ...SOURCE_TYPE_DIRECTORY.RSS_FEED,
  runCountsEntries: true,
  configuration: urlConfiguration(),
}
