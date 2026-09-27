import FolderIcon from '@mui/icons-material/Folder'
import LanguageIcon from '@mui/icons-material/Language'
import RssFeedIcon from '@mui/icons-material/RssFeed'
import {
  EMPTY_GENERIC_SOURCE_VALUES,
  genericSourcePayload,
  storedGenericSourceValues,
  validateGenericSource,
  type GenericSourceKind,
  type GenericSourceValues,
} from '../../../utils/librarySourceConfig'
import { PathForm, StoredConnection, UrlForm } from './GenericSourceViews'
import type { SourceConfiguration, SourceRegistration } from './types'

function genericConfiguration(kind: GenericSourceKind): SourceConfiguration<GenericSourceValues> {
  return {
    empty: EMPTY_GENERIC_SOURCE_VALUES,
    valuesKey: 'generic',
    fromLibrary: storedGenericSourceValues,
    isDirty: (values) => values.sourcePath !== '' || values.sourceUrl !== '',
    validate: (values) => validateGenericSource(kind, values),
    toPayload: (values) => genericSourcePayload(kind, values),
    nameFromSource: (values) => {
      if (kind === 'path') {
        const segments = values.sourcePath.split(/[\\/]+/).filter(Boolean)
        return segments.length > 0 ? segments[segments.length - 1] : ''
      }
      try {
        return new URL(values.sourceUrl).hostname
      } catch {
        return ''
      }
    },
    Form: kind === 'path' ? PathForm : UrlForm,
    StoredView: ({ library, libraryId }) => (
      <StoredConnection library={library} libraryId={libraryId} kind={kind} />
    ),
    firstRunHint:
      'Der erste Lauf liest die Quelle vollständig ein; sein Stand bleibt auf der Detailseite sichtbar.',
  }
}

export const filesystemSource: SourceRegistration = {
  label: 'Dateisystem',
  shortLabel: 'Dateisystem',
  description: 'Ein Pfad im Hausnetz wird regelmäßig eingelesen.',
  Icon: FolderIcon,
  configuration: genericConfiguration('path'),
}

export const httpDirectorySource: SourceRegistration = {
  label: 'Webverzeichnis',
  shortLabel: 'Web',
  description: 'Eine interne Webadresse wird durchlaufen und indiziert.',
  Icon: LanguageIcon,
  configuration: genericConfiguration('url'),
}

export const rssFeedSource: SourceRegistration = {
  label: 'RSS-Feed',
  shortLabel: 'Feed',
  description: 'Neue Beiträge werden laufend übernommen, Anhänge wahlweise.',
  Icon: RssFeedIcon,
  configuration: genericConfiguration('url'),
}
