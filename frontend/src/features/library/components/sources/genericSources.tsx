import {
  EMPTY_GENERIC_SOURCE_VALUES,
  genericSourcePayload,
  storedGenericSourceValues,
  type GenericSourceValues,
} from '../../utils/librarySourceConfig'
import {
  EMPTY_FILESYSTEM_VALUES,
  filesystemPayload,
  storedFilesystemValues,
  type FilesystemSourceValues,
} from '../../utils/filesystemSource'
import { filesystemSetup, urlSetup } from '../setup/connectors/genericSetups'
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
    toPayload: (values) => genericSourcePayload('url', values),
    nameFromSource: (values) => {
      try {
        return new URL(values.sourceUrl).hostname
      } catch {
        return ''
      }
    },
    setup: urlSetup,
    firstRunHint: FIRST_RUN_HINT,
  }
}

/** The path plus the exclusion patterns. */
function filesystemConfiguration(): SourceConfiguration<FilesystemSourceValues> {
  return {
    empty: EMPTY_FILESYSTEM_VALUES,
    draftFields: ['sourcePath', 'sourceUrl', 'excludePatterns'],
    fromLibrary: storedFilesystemValues,
    isDirty: (values) => values.sourcePath !== '' || values.excludePatterns.trim() !== '',
    toPayload: filesystemPayload,
    nameFromSource: (values) => {
      const segments = values.sourcePath.split(/[\\/]+/).filter(Boolean)
      return segments.length > 0 ? segments[segments.length - 1] : ''
    },
    setup: filesystemSetup,
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
