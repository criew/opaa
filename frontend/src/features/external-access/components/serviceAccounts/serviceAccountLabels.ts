import type { ServiceAccountResponse } from '@/shared/api/types'
import { daysUntil, formatDate } from '../tokenLabels'
import { formatNumber } from '@/shared/utils/formatDate'

/** From when a coming end is named in words - like the first reminder email. */
export const SOON_DAYS = 14

/** The one-word state beside „Verantwortlich“ when the person was locked or deleted. */
export const MISSING_PERSON = 'fehlt'

export const DEACTIVATE_CONSEQUENCE =
  'Alle Tokens dieses Dienstkontos werden sofort widerrufen. Ein deaktiviertes Dienstkonto lässt ' +
  'sich nicht wieder aktivieren.'

export const REVOKE_TOKEN_CONSEQUENCE =
  'Aufrufe mit diesem Token werden ab sofort abgewiesen. Vorher ein neues Token ausstellen und ' +
  'im Verfahren umstellen, sonst fällt es aus.'

export const REMOVE_CONSENT_CONSEQUENCE =
  'Das Dienstkonto findet ab dem nächsten Aufruf nichts mehr in dieser Bibliothek. Eine ' +
  'erneute Aufnahme ist jederzeit möglich.'

export const VALUE_ONCE_HINT =
  'Der Wert wird nur jetzt angezeigt. Geben Sie ihn auf sicherem Weg an den Betrieb des ' +
  'Verfahrens weiter.'

/** „in 3 Tagen“, „morgen“, „heute“ - only for an end within {@link SOON_DAYS}. */
export function soonLabel(value: string | null | undefined): string | null {
  if (!value) return null
  const days = daysUntil(value)
  if (days > SOON_DAYS) return null
  if (days <= 0) return 'heute'
  if (days === 1) return 'morgen'
  return `in ${days} Tagen`
}

export function tokenCountLabel(count: number): string {
  return count === 1 ? '1 Token' : `${count} Tokens`
}

export function rateLimitLabel(account: ServiceAccountResponse): string {
  const value = `${formatNumber(account.effectiveRateLimitPerHour)} je Stunde`
  return account.rateLimitPerHour == null ? `${value} (Vorgabe)` : value
}

export { formatDate }
