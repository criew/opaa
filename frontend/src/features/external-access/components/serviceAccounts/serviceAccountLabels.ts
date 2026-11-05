import type { ServiceAccountResponse } from '@/shared/api/types'
import { daysUntil, formatDate } from '../tokenLabels'

/** From when a coming end is named in words - like the first reminder email. */
export const SOON_DAYS = 14

export const MISSING_PERSON = 'Verantwortliche Person fehlt'

export const DEACTIVATE_CONSEQUENCE =
  'Alle Tokens dieses Dienstkontos werden sofort widerrufen; das Fachverfahren findet ab dem ' +
  'nächsten Aufruf nichts mehr. Ein deaktiviertes Dienstkonto lässt sich nicht wieder aktivieren.'

export const REVOKE_TOKEN_CONSEQUENCE =
  'Das Fachverfahren wird ab dem nächsten Aufruf mit diesem Token abgewiesen. Vorher ein neues ' +
  'Token ausstellen und im Verfahren umstellen, sonst fällt es aus.'

export const REMOVE_CONSENT_CONSEQUENCE =
  'Das Fachverfahren findet ab dem nächsten Aufruf nichts mehr in dieser Bibliothek. Eine ' +
  'erneute Aufnahme ist jederzeit möglich.'

export const VALUE_ONCE_HINT =
  'Der Wert wird nur jetzt angezeigt. Geben Sie ihn auf sicherem Weg an den Betrieb des ' +
  'Fachverfahrens weiter.'

/** „in 3 Tagen“, „morgen“, „heute“ - only for an end within {@link SOON_DAYS}. */
export function soonLabel(value: string | null | undefined): string | null {
  if (!value) return null
  const days = daysUntil(value)
  if (days > SOON_DAYS) return null
  if (days <= 0) return 'heute'
  if (days === 1) return 'morgen'
  return `in ${days} Tagen`
}

/** The next end of a token or a consent, as a date - or a dash when nothing runs. */
export function nextExpiryText(account: ServiceAccountResponse): string {
  if (!account.active) return '—'
  return formatDate(account.nextExpiryAt)
}

export function tokenCountLabel(count: number): string {
  return count === 1 ? '1 Token' : `${count} Tokens`
}

export function libraryCountLabel(count: number): string {
  return count === 1 ? '1 Bibliothek' : `${count} Bibliotheken`
}

export function rateLimitLabel(account: ServiceAccountResponse): string {
  const value = `${account.effectiveRateLimitPerHour.toLocaleString('de-DE')} je Stunde`
  return account.rateLimitPerHour == null ? `${value} (Vorgabe)` : value
}

export { formatDate }
