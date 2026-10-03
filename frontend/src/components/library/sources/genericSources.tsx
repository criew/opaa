import FolderIcon from '@mui/icons-material/Folder'
import LanguageIcon from '@mui/icons-material/Language'
import RssFeedIcon from '@mui/icons-material/RssFeed'
import {
  EMPTY_GENERIC_SOURCE_VALUES,
  genericSourcePayload,
  storedGenericSourceValues,
  validateGenericSource,
  type GenericSourceValues,
} from '../../../utils/librarySourceConfig'
import {
  EMPTY_FILESYSTEM_VALUES,
  filesystemPayload,
  storedFilesystemValues,
  validateFilesystemValues,
  type FilesystemSourceValues,
} from '../../../utils/filesystemSource'
import { PathForm, StoredConnection, UrlForm } from './GenericSourceViews'
import type { SourceConfiguration, SourceRegistration } from './types'

const FIRST_RUN_HINT =
  'Der erste Lauf liest die Quelle vollständig ein; sein Stand bleibt auf der Detailseite sichtbar.'

function urlConfiguration(): SourceConfiguration<GenericSourceValues> {
  return {
    empty: EMPTY_GENERIC_SOURCE_VALUES,
    valuesKey: 'generic',
    fromLibrary: storedGenericSourceValues,
    isDirty: (values) => values.sourcePath !== '' || values.sourceUrl !== '',
    validate: (values) => validateGenericSource('url', values),
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
    fromLibrary: storedFilesystemValues,
    isDirty: (values) => values.sourcePath !== '' || values.excludePatterns.trim() !== '',
    validate: (values) => validateFilesystemValues(values),
    toPayload: filesystemPayload,
    nameFromSource: (values) => {
      const segments = values.sourcePath.split(/[\\/]+/).filter(Boolean)
      return segments.length > 0 ? segments[segments.length - 1] : ''
    },
    Form: PathForm,
    StoredView: ({ library, libraryId }) => (
      <StoredConnection library={library} libraryId={libraryId} kind="path" />
    ),
    firstRunHint: FIRST_RUN_HINT,
  }
}

export const filesystemSource: SourceRegistration = {
  label: 'Dateisystem',
  shortLabel: 'Dateisystem',
  description: 'Ein Pfad im Hausnetz wird regelmäßig eingelesen.',
  Icon: FolderIcon,
  configuration: filesystemConfiguration(),
}

export const httpDirectorySource: SourceRegistration = {
  label: 'Webverzeichnis',
  shortLabel: 'Web',
  description: 'Eine interne Webadresse wird durchlaufen und indiziert.',
  Icon: LanguageIcon,
  configuration: urlConfiguration(),
}

export const rssFeedSource: SourceRegistration = {
  label: 'RSS-Feed',
  shortLabel: 'Feed',
  description: 'Neue Beiträge werden laufend übernommen, Anhänge wahlweise.',
  Icon: RssFeedIcon,
  runCountsEntries: true,
  configuration: urlConfiguration(),
}
