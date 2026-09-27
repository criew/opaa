import ExtensionIcon from '@mui/icons-material/Extension'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type { SourceTypeKey } from '../../types/api'
import { sourceRegistration } from './sources/registry'

/**
 * Das eine Bildzeichen je Quellentyp (#1942): Kopf der Detailseite und Kachel des Assistenten
 * zeigen dasselbe Symbol, damit die Wahl im Assistenten und die angelegte Bibliothek sichtbar
 * dieselbe Sache sind. Immer schmückend — der Typ steht daneben als Text. Ein Typ ohne eigene
 * Registrierung trägt ein neutrales Zeichen.
 */
export default function SourceTypeIcon({
  sourceType,
  fontSize = 24,
}: {
  sourceType: SourceTypeKey | undefined
  fontSize?: number
}) {
  const sx = { fontSize }
  if (sourceType === undefined) return <UploadFileIcon sx={sx} />
  const Icon = sourceRegistration(sourceType)?.Icon ?? ExtensionIcon
  return <Icon sx={sx} />
}
