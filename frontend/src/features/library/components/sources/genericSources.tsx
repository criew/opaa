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
import FilesystemSetup from '../setup/FilesystemSetup'
import { collectIssues } from '../setup/setupContext'
import { filesystemPatternsOf } from '../../utils/filesystemSource'
import { listed } from '../setup/summaries'
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
      collectIssues(
        genericSourceIssue('path', values),
        filesystemIssue({ ...values, sourcePath: '/x' }),
      ),
    checkStages: ['address', 'access'],
    summary: (values) => {
      const patterns = filesystemPatternsOf(values.excludePatterns)
      return {
        connection: [{ label: 'Verzeichnispfad', value: values.sourcePath.trim(), mono: true }],
        scope: [
          { label: 'Ausschlussmuster', value: patterns.length ? listed(patterns) : 'keine', mono: patterns.length > 0 },
        ],
      }
    },
    Form: FilesystemSetup,
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
