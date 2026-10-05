import type {
  ConnectionEndCause,
  SourceBlockReason,
  SourceConnectionResponsible,
} from '../../types/api'
import { endCauseLabel } from '../audit/connectionLogLabels'

/** Who answers for a library's source connection; a name the server withholds stays unnamed. */
export function responsibleLabel(responsible: SourceConnectionResponsible): string {
  if (responsible.type === 'GROUP') {
    return responsible.name ? `Gruppe „${responsible.name}“` : 'eine Gruppe'
  }
  return responsible.name ?? 'eine Person'
}

/** Why a library's own source connection ended. */
export function sourceConnectionEndLabel(cause: ConnectionEndCause): string {
  return cause === 'SELF' ? 'Von den Verwaltenden getrennt' : endCauseLabel(cause)
}

const REASON_LABELS: Record<SourceBlockReason, string> = {
  TYPE_LOCKED: 'Quellart gesperrt',
  PROFILE_LOCKED: 'Zugang gesperrt',
  PROFILE_REQUIRED: 'Zugang erforderlich',
  NOT_CONNECTED: 'Nicht verbunden',
  ACCESS_REMOVED: 'Zugang entfernt',
  OWNER_DEACTIVATED: 'Konto deaktiviert',
  DORMANT: 'Ruhend',
  TARGET_OUTSIDE_PROFILE: 'Ziel weicht ab',
  EXPIRED: 'Abgelaufen',
}

export function sourceBlockReasonLabel(reason: SourceBlockReason): string {
  return REASON_LABELS[reason] ?? reason
}

export function formatConsentTime(value: string): string {
  return new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}
