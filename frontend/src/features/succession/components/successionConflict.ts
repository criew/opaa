import { apiErrorCode } from '@/shared/api/apiErrorDetails'

/** The error code with which the backend rejects the frozen reach (ADR-0036/6). */
export const SUCCESSION_OPEN = 'SUCCESSION_OPEN'

const TAKEOVER_HINT =
  'Der Ausweg ist eine Nachfolge: Eigentum und Verantwortung werden übertragen — unter ' +
  'Administration → Ohne Zuständigkeit oder aus der Gruppenverwaltung.'

/**
 * The rejection because of an open succession as an explained boundary instead of a raw error: the
 * backend's message already names the action and the responsible party; here the way out is added.
 * Every other error keeps its text.
 */
export function successionAwareMessage(err: unknown, fallback: string): string {
  const message = err instanceof Error && err.message ? err.message : fallback
  return apiErrorCode(err) === SUCCESSION_OPEN ? `${message} ${TAKEOVER_HINT}` : message
}
