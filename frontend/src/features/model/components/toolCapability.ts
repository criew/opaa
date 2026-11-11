import type { LlmModelToolCapability, ToolCapability } from '@/shared/api/types'
import { formatDate } from '@/shared/utils/formatDate'

export const TOOL_CAPABILITY_LABELS: Record<ToolCapability, string> = {
  UNVERIFIED: 'Ungeprüft',
  CAPABLE: 'Fähig',
  NOT_CAPABLE: 'Nicht fähig',
}

/** What each value means for a turn that needs tools — shown under the choice in the dialog. */
export const TOOL_CAPABILITY_HINTS: Record<ToolCapability, string> = {
  UNVERIFIED: 'Noch niemand hat es geprüft. Anfragen mit Werkzeugen werden abgelehnt.',
  CAPABLE: 'Das Modell ruft Werkzeuge zuverlässig auf. Anfragen mit Werkzeugen laufen.',
  NOT_CAPABLE: 'Geprüft, kann es nicht. Anfragen mit Werkzeugen werden abgelehnt.',
}

function formatDay(value: string): string {
  return formatDate(value)
}

/**
 * The basis of a checked statement in one line — how it was checked and when, never who:
 * „manuell gesetzt · 09.11.2026“ or „gemessen · 09.11.2026 · <Messlauf>“. Null while unverified.
 */
export function toolCapabilityBasisLine(capability: LlmModelToolCapability): string | null {
  if (capability.value === 'UNVERIFIED' || !capability.basis) return null
  const parts = [capability.basis === 'MEASURED' ? 'gemessen' : 'manuell gesetzt']
  if (capability.assessedAt) parts.push(formatDay(capability.assessedAt))
  if (capability.basis === 'MEASURED' && capability.measurementRun) {
    parts.push(capability.measurementRun)
  }
  return parts.join(' · ')
}
