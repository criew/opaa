import type { DirectorySyncOutcome } from '../../../types/api'
import type { StatusTone } from '../../StatusLine'

const outcomeLabels: Record<DirectorySyncOutcome, string> = {
  APPLIED: 'Angewendet',
  DRY_RUN: 'Trockenlauf',
  PENDING_CONFIRMATION: 'Wartet auf Bestätigung',
  ABORTED_THRESHOLD: 'Über der Schwelle abgebrochen',
  ABORTED_EMPTY_RESULT: 'Abgebrochen: leeres Ergebnis',
  UNREACHABLE: 'Verzeichnis nicht erreichbar',
}

const outcomeTones: Record<DirectorySyncOutcome, StatusTone> = {
  APPLIED: 'success',
  DRY_RUN: 'neutral',
  PENDING_CONFIRMATION: 'warning',
  ABORTED_THRESHOLD: 'warning',
  ABORTED_EMPTY_RESULT: 'error',
  UNREACHABLE: 'error',
}

export function outcomeLabel(outcome: DirectorySyncOutcome | null | undefined): string {
  return outcome ? outcomeLabels[outcome] : 'Noch kein Lauf'
}

export function outcomeTone(outcome: DirectorySyncOutcome | null | undefined): StatusTone {
  return outcome ? outcomeTones[outcome] : 'neutral'
}

/**
 * Der Mechanismuskonflikt als Satz statt als roher `409` (ADR-0036, Entscheidung 2): Je Anbieter
 * gibt es genau einen Gruppenmechanismus.
 */
export const DIRECTORY_SYNC_CONFLICT_MESSAGES: Readonly<Record<string, string>> = {
  DIRECTORY_SYNC_MECHANISM_CONFLICT:
    'Dieser Anbieter liefert seine Gruppen bereits über den Token-Claim. Je Anbieter gibt es genau einen Gruppenmechanismus — leeren Sie zuerst das Feld „groups claim" in der Anbieterverwaltung, sonst entstünde dieselbe Verzeichnisgruppe zweimal.',
  DIRECTORY_SYNC_NOT_ENABLED:
    'Der Verzeichnisabgleich ist für diesen Anbieter ausgeschaltet. Schalten Sie ihn zuerst ein.',
  DIRECTORY_SYNC_PROVIDER_DISABLED:
    'Der Anbieter ist deaktiviert; sein Abgleich pausiert, solange das so bleibt.',
  DIRECTORY_SYNC_ALREADY_RUNNING:
    'Für diesen Anbieter läuft bereits ein Abgleich. Bitte warten Sie ihn ab.',
  DIRECTORY_SYNC_PLAN_CHANGED:
    'Das Verzeichnis hat sich seit der Vorlage geändert. Es wurde nichts angewendet; der neue Plan liegt Ihnen vor und will erneut entschieden werden.',
}

export function formatFraction(value: number | null | undefined): string {
  if (value === null || value === undefined) return '—'
  return `${Math.round(value * 100)} %`
}

export function formatDateTime(value: string | null | undefined): string {
  if (!value) return '—'
  const date = new Date(value)
  return `${date.toLocaleDateString('de-DE', { dateStyle: 'medium' })}, ${date.toLocaleTimeString(
    'de-DE',
    { hour: '2-digit', minute: '2-digit' },
  )}`
}
