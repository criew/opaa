import type { MailSendResultResponse, MailSettingsResponse } from '../../../types/api'

export type MailStatusKind = 'UNCONFIGURED' | 'UNTESTED' | 'SUCCESS' | 'FAILURE'

export interface MailStatus {
  kind: MailStatusKind
  headline: string
  detail: string | null
}

/** German date and time of a backend timestamp, or `null` for one that was never set. */
export function formatMailTimestamp(value: string | null | undefined): string | null {
  if (!value) return null
  return new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

/**
 * The one reading of `lastSuccessAt`/`lastFailureAt`/`lastFailureReason` the status tile shows
 * (ADR-0033, Entscheidung 10). Which of the two timestamps is the newer one decides the verdict:
 * a success after a failure means the problem is over, and the tile must not keep claiming
 * otherwise.
 */
export function mailStatusOf(settings: MailSettingsResponse): MailStatus {
  if (!settings.enabled || !settings.host) {
    return {
      kind: 'UNCONFIGURED',
      headline: 'Nicht konfiguriert',
      detail: 'Ohne SMTP-Server versendet OPAA keine Einladungen und keine Rücksetzlinks.',
    }
  }

  const success = settings.lastSuccessAt ? Date.parse(settings.lastSuccessAt) : null
  const failure = settings.lastFailureAt ? Date.parse(settings.lastFailureAt) : null

  if (success === null && failure === null) {
    return {
      kind: 'UNTESTED',
      headline: 'Konfiguriert, Test ausstehend',
      detail: 'Es wurde noch keine Nachricht über diese Verbindung versendet.',
    }
  }
  if (failure !== null && (success === null || failure > success)) {
    return {
      kind: 'FAILURE',
      headline: `Letzter Versand fehlgeschlagen am ${formatMailTimestamp(settings.lastFailureAt)}`,
      detail: settings.lastFailureReason ?? 'Das Backend hat keinen Grund gemeldet.',
    }
  }
  return {
    kind: 'SUCCESS',
    headline: `Letzter Versand erfolgreich am ${formatMailTimestamp(settings.lastSuccessAt)}`,
    detail: null,
  }
}

/** The German sentence for a send attempt - the outcome plus the backend's own reason. */
export function mailSendResultMessage(result: MailSendResultResponse): string {
  switch (result.outcome) {
    case 'SENT':
      return result.recipient
        ? `Die Testnachricht wurde an ${result.recipient} versendet.`
        : 'Die Testnachricht wurde versendet.'
    case 'SKIPPED':
      return `Es wurde nichts versendet: ${result.reason ?? 'SMTP ist nicht eingerichtet.'}`
    case 'FAILED':
      return `Der Versand ist fehlgeschlagen: ${result.reason ?? 'Das Backend hat keinen Grund gemeldet.'}`
  }
}

/** MUI severity for a send outcome - skipped is a hint, not a failure. */
export function mailSendResultSeverity(
  result: MailSendResultResponse,
): 'success' | 'warning' | 'error' {
  switch (result.outcome) {
    case 'SENT':
      return 'success'
    case 'SKIPPED':
      return 'warning'
    case 'FAILED':
      return 'error'
  }
}
