import ExtensionIcon from '@mui/icons-material/Extension'
import UploadFileIcon from '@mui/icons-material/UploadFile'
import type { SourceTypeKey } from '@/shared/api/types'
import { sourceTypeEntry } from './sourceTypeDirectory'

/**
 * The one icon per source type: the detail page's header and the wizard's tile
 * show the same symbol, so that the choice in the wizard and the created library are visibly
 * the same thing. Always decorative — the type stands next to it as text. A type without its own
 * registration carries a neutral icon.
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
  const Icon = sourceTypeEntry(sourceType)?.Icon ?? ExtensionIcon
  return <Icon sx={sx} />
}
