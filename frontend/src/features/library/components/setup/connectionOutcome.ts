import type { SourceConnectionTestResponse } from '@/shared/api/types'

export type ConnectionStageKey = 'address' | 'auth' | 'rights'

export const STAGE_LABELS: Record<ConnectionStageKey, string> = {
  address: 'Adresse',
  auth: 'Anmeldung',
  rights: 'Rechte',
}

/** One connection test as every source form shows it: passed, or the stage that failed. */
export interface ConnectionOutcome {
  ok: boolean
  failedStage?: ConnectionStageKey
  message: string
  /** The connector's own findings, e.g. per selected scope. */
  details?: Record<string, unknown> | null
}

const AUTH_WORDS = /Anmeld|abgelehnt|401|Zugangsdaten|Passwort|Token|Schlüssel|invalid_grant|LOGON|Zustimmung/i
const RIGHTS_WORDS = /Zugriff|verweigert|Berechtig|AccessDenied|403|nicht lesbar|nicht erreichbar;|Bereich/i

/**
 * The stage a test response failed at. A refused sign-in is „Anmeldung"; a source that accepted
 * the sign-in but cannot read everything is „Rechte"; anything else did not get past the address.
 */
export function outcomeOf(
  response: SourceConnectionTestResponse,
  stages: ConnectionStageKey[],
): ConnectionOutcome {
  const details = (response.details ?? null) as Record<string, unknown> | null
  if (response.reachable) return { ok: true, message: response.message, details }
  const has = (stage: ConnectionStageKey) => stages.includes(stage)
  let failedStage: ConnectionStageKey = stages[0]
  if (response.credentialsVerified === true && has('rights')) failedStage = 'rights'
  else if (has('auth') && AUTH_WORDS.test(response.message)) failedStage = 'auth'
  else if (has('rights') && RIGHTS_WORDS.test(response.message)) failedStage = 'rights'
  return { ok: false, failedStage, message: response.message, details }
}

/** A failed request: the source was not reached, or the server refused the call itself. */
export function outcomeOfError(err: unknown, stages: ConnectionStageKey[]): ConnectionOutcome {
  const message =
    err instanceof Error && err.message ? err.message : 'Die Verbindung ließ sich nicht testen.'
  return {
    ok: false,
    failedStage: RIGHTS_WORDS.test(message) && stages.includes('rights') ? 'rights' : stages[0],
    message,
  }
}
