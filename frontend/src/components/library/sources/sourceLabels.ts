import type { SourceTypeKey } from '../../../types/api'
import { sourceRegistration } from './registry'

/** The names a source type is shown under - its registration's, else the key itself. */
export function documentSourceTypeLabel(sourceType: SourceTypeKey | undefined): string {
  if (!sourceType) return ''
  return sourceRegistration(sourceType)?.label ?? sourceType
}

/** The origin as a card badge shows it; tables and forms keep {@link documentSourceTypeLabel}. */
export function documentSourceTypeShortLabel(sourceType: SourceTypeKey | undefined): string {
  if (!sourceType) return ''
  return sourceRegistration(sourceType)?.shortLabel ?? sourceType
}

export function documentSourceTypeDescription(sourceType: SourceTypeKey | undefined): string {
  if (!sourceType) return ''
  return sourceRegistration(sourceType)?.description ?? 'Weiterer Quellentyp.'
}
