import { apiErrorCode } from '../../services/apiErrorDetails'

/** Der Fehlercode, mit dem das Backend die eingefrorene Reichweite abweist (ADR-0036/6). */
export const SUCCESSION_OPEN = 'SUCCESSION_OPEN'

const TAKEOVER_HINT =
  'Der Ausgang ist die Übernahme: Eigentum und Verantwortung werden übertragen — aus der ' +
  'Betriebsliste (Administration → Lebenszyklus) oder aus der Gruppenverwaltung.'

/**
 * Die Ablehnung wegen offener Nachfolge als erklärte Grenze statt als roher Fehler: Die Meldung des
 * Backends nennt bereits die Handlung und die zuständige Stelle; hier kommt der Weg hinaus dazu.
 * Jeder andere Fehler behält seinen Text.
 */
export function successionAwareMessage(err: unknown, fallback: string): string {
  const message = err instanceof Error && err.message ? err.message : fallback
  return apiErrorCode(err) === SUCCESSION_OPEN ? `${message} ${TAKEOVER_HINT}` : message
}
