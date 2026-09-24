import AccountTreeIcon from '@mui/icons-material/AccountTree'
import FolderIcon from '@mui/icons-material/Folder'
import LanguageIcon from '@mui/icons-material/Language'
import RssFeedIcon from '@mui/icons-material/RssFeed'
import StorageIcon from '@mui/icons-material/Storage'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type { DocumentSourceType } from '../../types/api'

/**
 * Das eine Bildzeichen je Quellentyp (#1942): Kopf der Detailseite und Kachel des Assistenten
 * zeigen dasselbe Symbol, damit die Wahl im Assistenten und die angelegte Bibliothek sichtbar
 * dieselbe Sache sind. Immer schmückend — der Typ steht daneben als Text.
 */
export default function SourceTypeIcon({
  sourceType,
  fontSize = 24,
}: {
  sourceType: DocumentSourceType | undefined
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
    default:
      return <UploadFileIcon sx={sx} />
  }
}
