import { AxiosError } from 'axios'
import { apiErrorStatus, apiFieldErrors } from '@/shared/api/apiErrorDetails'
import { isErrorResponse } from '@/shared/api/types'
import type { FieldError } from '@/shared/session/types'

/** How a failed request of the installation API is to be handled by the assistant. */
export type InstallationFailureKind =
  /** The token is wrong or was replaced by the operator: ask for it again. */
  | 'token'
  /** The installation is complete (another instance, the answer file): the API is gone. */
  | 'installed'
  /** Anything else: show the message, keep the entries. */
  | 'other'

export interface InstallationFailure {
  kind: InstallationFailureKind
  message: string
  fieldErrors: FieldError[]
}

const TOKEN_REJECTED =
  'Das Installations-Token stimmt nicht. Prüfen Sie die Eingabe; das Token steht im Log des ersten Starts oder in OPAA_INSTALLATION_TOKEN.'
const NETWORK_REFUSED =
  'Die Einrichtung ist von diesem Netz aus nicht freigegeben. Der Betrieb legt die zugelassenen Netzbereiche in OPAA_INSTALLATION_ALLOWED_CIDRS fest.'
const TOO_MANY_ATTEMPTS =
  'Zu viele Anfragen oder Fehlversuche von dieser Adresse. Warten Sie einige Minuten und versuchen Sie es dann erneut.'
const INSTALLED = 'Die Installation ist bereits abgeschlossen.'
const UNREACHABLE =
  'Der Server ist nicht erreichbar. Prüfen Sie die Verbindung und versuchen Sie es erneut.'
const TIMED_OUT =
  'Der Server hat nicht rechtzeitig geantwortet. Eine Prüfung kann auf ein langsames Modell warten; versuchen Sie es erneut.'

/** The backend's own German message, where the response carries one. */
function backendMessage(err: unknown): string | null {
  const data = err instanceof AxiosError ? err.response?.data : null
  return isErrorResponse(data) ? data.error : null
}

/**
 * Translates a failed request into what the assistant does next and the German sentence it shows.
 * Field errors come through as the backend sent them; every field shows its own (guidelines 5.2).
 */
export function installationFailure(err: unknown, fallback: string): InstallationFailure {
  const status = apiErrorStatus(err)
  const fieldErrors = apiFieldErrors(err)
  if (status === 401) return { kind: 'token', message: TOKEN_REJECTED, fieldErrors: [] }
  if (status === 404) return { kind: 'installed', message: INSTALLED, fieldErrors: [] }
  if (status === 403) return { kind: 'other', message: NETWORK_REFUSED, fieldErrors: [] }
  if (status === 429) {
    return { kind: 'other', message: mailLimitMessage(err) ?? TOO_MANY_ATTEMPTS, fieldErrors: [] }
  }
  if (err instanceof AxiosError && status === null) {
    const message = err.code === AxiosError.ECONNABORTED ? TIMED_OUT : UNREACHABLE
    return { kind: 'other', message, fieldErrors: [] }
  }
  return { kind: 'other', message: backendMessage(err) ?? fallback, fieldErrors }
}

const SECONDS_PER_MINUTE = 60

/** The code of a 429 from the budget of model discoveries, not the API's per-address budget. */
const DISCOVERY_LIMIT_CODE = 'MODEL_DISCOVERY_LIMIT'

/** The codes of a 429 from the budgets of the mail step, apart from the API's own budget. */
const MAIL_TEST_LIMIT_CODE = 'MAIL_TEST_LIMIT'
const MAIL_CHECK_LIMIT_CODE = 'MAIL_CHECK_LIMIT'

/** The code of a refusal by a budget of its own, or `null`. */
function limitCode(err: unknown): string | null {
  const data = err instanceof AxiosError ? err.response?.data : null
  return apiErrorStatus(err) === 429 && isErrorResponse(data) ? (data.code ?? null) : null
}

/** How long to wait, from the server's `Retry-After`, in words. */
function waitOf(err: unknown): string {
  const header = err instanceof AxiosError ? err.response?.headers?.['retry-after'] : undefined
  const seconds = Number(header)
  const minutes =
    Number.isFinite(seconds) && seconds > 0 ? Math.ceil(seconds / SECONDS_PER_MINUTE) : null
  if (minutes === null) return 'in einigen Minuten'
  return minutes === 1 ? 'in etwa 1 Minute' : `in etwa ${minutes} Minuten`
}

/**
 * The sentence for a check of the mail step past one of its budgets: the test messages, after which
 * the check without one still works, or the checks themselves. `null` for any other failure.
 */
function mailLimitMessage(err: unknown): string | null {
  const code = limitCode(err)
  if (code === MAIL_TEST_LIMIT_CODE) {
    return `Zu viele Testnachrichten. Bitte ${waitOf(err)} erneut versuchen; ohne Testnachricht lässt sich der Mailserver weiter prüfen.`
  }
  if (code === MAIL_CHECK_LIMIT_CODE) {
    return `Zu viele Prüfungen des Mailservers. Bitte ${waitOf(err)} erneut versuchen.`
  }
  return null
}

/**
 * The sentence for a model discovery past its own budget, with the wait the server's
 * `Retry-After` names; the identifier can always be typed by hand. `null` for any other failure,
 * the per-address budget of the installation API included.
 */
export function discoveryLimitMessage(err: unknown): string | null {
  if (limitCode(err) !== DISCOVERY_LIMIT_CODE) return null
  return `Zu viele Modellabrufe. Bitte ${waitOf(err)} erneut versuchen; die Kennung lässt sich von Hand eintragen.`
}
