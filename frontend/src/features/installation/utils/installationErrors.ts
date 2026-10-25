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
  'Zu viele Fehlversuche von dieser Adresse. Warten Sie einige Minuten und versuchen Sie es dann erneut.'
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
  if (status === 429) return { kind: 'other', message: TOO_MANY_ATTEMPTS, fieldErrors: [] }
  if (err instanceof AxiosError && status === null) {
    const message = err.code === AxiosError.ECONNABORTED ? TIMED_OUT : UNREACHABLE
    return { kind: 'other', message, fieldErrors: [] }
  }
  return { kind: 'other', message: backendMessage(err) ?? fallback, fieldErrors }
}
