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
import FilesystemForm from '../sourceForms/FilesystemForm'
import { collectIssues, unifyMissing } from '../sourceKit/collectIssues'
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
    issues: (values) =>
      unifyMissing(collectIssues(values, filesystemIssue, { sourcePath: '/stand-in' }), values, {
        sourcePath: 'das Verzeichnis',
      }),
    validate: (values) => filesystemIssue(values),
    summary: (values) => [
      { label: 'Verzeichnis', value: values.sourcePath.trim() },
      {
        label: 'Ausschlussmuster',
        value: filesystemPatternsOf(values.excludePatterns).join(', ') || 'Keine',
      },
    ],
    toPayload: filesystemPayload,
    nameFromSource: (values) => {
      const segments = values.sourcePath.split(/[\\/]+/).filter(Boolean)
      return segments.length > 0 ? segments[segments.length - 1] : ''
    },
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
