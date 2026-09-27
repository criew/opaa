import AccountTreeIcon from '@mui/icons-material/AccountTree'
import ExtensionIcon from '@mui/icons-material/Extension'
import FolderIcon from '@mui/icons-material/Folder'
import LanguageIcon from '@mui/icons-material/Language'
import RssFeedIcon from '@mui/icons-material/RssFeed'
import StorageIcon from '@mui/icons-material/Storage'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type { SourceTypeKey } from '../../types/api'

/**
 * Das eine Bildzeichen je Quellentyp (#1942): Kopf der Detailseite und Kachel des Assistenten
 * zeigen dasselbe Symbol, damit die Wahl im Assistenten und die angelegte Bibliothek sichtbar
 * dieselbe Sache sind. Immer schmückend — der Typ steht daneben als Text.
 */
export default function SourceTypeIcon({
  sourceType,
  fontSize = 24,
}: {
  sourceType: SourceTypeKey | undefined
  fontSize?: number
}) {
  const sx = { fontSize }
  switch (sourceType) {
    case 'CONFLUENCE':
      return <AccountTreeIcon sx={sx} />
    case 'FILESYSTEM':
      return <FolderIcon sx={sx} />
    case 'HTTP_DIRECTORY':
      return <LanguageIcon sx={sx} />
    case 'RSS_FEED':
      return <RssFeedIcon sx={sx} />
    case 'S3':
      return <StorageIcon sx={sx} />
    case 'UPLOAD':
    case undefined:
      return <UploadFileIcon sx={sx} />
    default:
      // a connector the frontend has no own symbol for (ADR-0038)
      return <ExtensionIcon sx={sx} />
  }
}
