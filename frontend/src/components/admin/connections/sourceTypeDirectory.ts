import AccountTreeIcon from '@mui/icons-material/AccountTree'
import CloudQueueIcon from '@mui/icons-material/CloudQueue'
import FolderIcon from '@mui/icons-material/Folder'
import FolderSharedIcon from '@mui/icons-material/FolderShared'
import InboxIcon from '@mui/icons-material/Inbox'
import LanguageIcon from '@mui/icons-material/Language'
import LibraryBooksIcon from '@mui/icons-material/LibraryBooks'
import RssFeedIcon from '@mui/icons-material/RssFeed'
import StorageIcon from '@mui/icons-material/Storage'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type { ComponentType } from 'react'
import type { SourceTypeKey } from '@/shared/api/types'

/** How every area names and marks one source type: the library wizard as the connections area. */
export interface SourceTypeEntry {
  label: string
  shortLabel: string
  description: string
  Icon: ComponentType<{ sx?: object }>
}

/**
 * Names, description and symbol of each source type this client knows (ADR-0038). The library
 * registers its forms on top of these entries; a type the backend lists (GET /source-types)
 * without an entry here has none.
 */
export const SOURCE_TYPE_DIRECTORY: Record<SourceTypeKey, SourceTypeEntry> = {
  UPLOAD: {
    label: 'Upload',
    shortLabel: 'Upload',
    description: 'Dateien auswählen oder hineinziehen; einzelne Dokumente pflegen.',
    Icon: UploadFileIcon,
  },
  FILESYSTEM: {
    label: 'Dateisystem',
    shortLabel: 'Dateisystem',
    description: 'Ein Pfad im Hausnetz wird regelmäßig eingelesen.',
    Icon: FolderIcon,
  },
  HTTP_DIRECTORY: {
    label: 'Webverzeichnis',
    shortLabel: 'Web',
    description: 'Eine interne Webadresse wird durchlaufen und indiziert.',
    Icon: LanguageIcon,
  },
  RSS_FEED: {
    label: 'RSS-Feed',
    shortLabel: 'Feed',
    description: 'Neue Beiträge werden laufend übernommen, Anhänge wahlweise.',
    Icon: RssFeedIcon,
  },
  CONFLUENCE: {
    label: 'Confluence',
    shortLabel: 'Confluence',
    description: 'Ausgewählte Spaces eines Confluence (Cloud oder Data Center) werden eingelesen.',
    Icon: AccountTreeIcon,
  },
  S3: {
    label: 'S3-Objektspeicher',
    shortLabel: 'S3',
    description: 'Buckets und Präfixe eines S3-kompatiblen Objektspeichers werden eingelesen.',
    Icon: StorageIcon,
  },
  GOOGLE_DRIVE: {
    label: 'Google Drive',
    shortLabel: 'Google Drive',
    description: 'Geteilte Ablagen und Ordner eines Google Workspace werden eingelesen.',
    Icon: FolderSharedIcon,
  },
  SHAREPOINT: {
    label: 'SharePoint',
    shortLabel: 'SharePoint',
    description:
      'Dokumentbibliotheken von SharePoint Online werden über einen Zugang mit App-Registrierung eingelesen.',
    Icon: LibraryBooksIcon,
  },
  NEXTCLOUD: {
    label: 'Nextcloud',
    shortLabel: 'Nextcloud',
    description:
      'Ordner einer Nextcloud (auch openDesk) werden über einen technischen Nutzer eingelesen.',
    Icon: CloudQueueIcon,
  },
  DROPBOX: {
    label: 'Dropbox',
    shortLabel: 'Dropbox',
    description:
      'Ordner einer Dropbox (auch eines Teams) werden über eine eigene Zustimmung der Bibliothek eingelesen.',
    Icon: InboxIcon,
  },
  SMB: {
    label: 'Windows-Dateifreigabe (SMB)',
    shortLabel: 'Dateifreigabe',
    description: 'Ordner einer Windows-Dateifreigabe werden über ein Dienstkonto eingelesen.',
    Icon: FolderSharedIcon,
  },
}

/** The entry of {@code sourceType}, undefined for a type this client does not know. */
export function sourceTypeEntry(
  sourceType: SourceTypeKey | null | undefined,
): SourceTypeEntry | undefined {
  return sourceType ? SOURCE_TYPE_DIRECTORY[sourceType] : undefined
}
