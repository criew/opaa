import type { SourceConnectionTestResponse } from '@/shared/api/types'

/** The stages a check reports, in the order the source passes them. */
export type CheckStage = 'address' | 'signIn' | 'access'

export const STAGE_LABELS: Record<CheckStage, string> = {
  address: 'Adresse',
  signIn: 'Anmeldung',
  access: 'Rechte',
}

export type StageState = 'ok' | 'failed' | 'skipped'

export interface CheckResult {
  passed: boolean
  stages: Partial<Record<CheckStage, StageState>>
  message: string
}

const SIGN_IN_WORDS =
  /anmeld|abgelehnt|401|zugangsdaten|passwort|kennwort|token|schlüssel|zustimmung|signature|logon/i
const ADDRESS_WORDS =
  /nicht erreichbar|antwortet nicht|zeitüberschreitung|nicht gefunden|dns|port|adresse/i

/** Maps a test response (or a refused request) onto the stages the source has. */
export function checkResultOf(
  stages: CheckStage[],
  outcome: { result?: SourceConnectionTestResponse; error?: string },
): CheckResult {
  const states: Partial<Record<CheckStage, StageState>> = {}
  const set = (failed: CheckStage | null) => {
    let passedFailure = false
    for (const stage of stages) {
      if (passedFailure) states[stage] = 'skipped'
      else if (stage === failed) {
        states[stage] = 'failed'
        passedFailure = true
      } else states[stage] = 'ok'
    }
  }
  const { result, error } = outcome
  if (error !== undefined || !result) {
    set(stages.includes('signIn') && SIGN_IN_WORDS.test(error ?? '') ? 'signIn' : stages[0])
    return { passed: false, stages: states, message: error ?? 'Verbindung ließ sich nicht prüfen.' }
  }
  if (result.reachable) {
    set(null)
    return { passed: true, stages: states, message: result.message }
  }
  let failed: CheckStage
  if (result.credentialsVerified === true) failed = 'access'
  else if (
    stages.includes('signIn') &&
    (result.credentialsVerified === false || SIGN_IN_WORDS.test(result.message))
  )
    failed =
      ADDRESS_WORDS.test(result.message) && !SIGN_IN_WORDS.test(result.message)
        ? 'address'
        : 'signIn'
  else if (ADDRESS_WORDS.test(result.message) || !stages.includes('access')) failed = stages[0]
  else failed = 'access'
  if (!stages.includes(failed)) failed = stages[stages.length - 1]
  set(failed)
  return { passed: false, stages: states, message: result.message }
}
