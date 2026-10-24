import { useState } from 'react'
import type { SourceConnectionTestResponse } from '@/shared/api/types'
import { apiErrorStatus } from '@/shared/api/apiErrorDetails'
import { browseSource, testLibrarySource } from '../../api/libraryApi'
import type { SourceConfiguration } from '../sources/types'
import type { CheckOutcome, CheckStage, StageState, StepContext, StepwiseSource } from './types'

const FALLBACK = 'Die Quelle hat nicht geantwortet. Bitte später erneut prüfen.'

function stages(address: StageState, signIn: StageState, rights: StageState) {
  return { address, signIn, rights } satisfies Record<CheckStage, StageState>
}

/**
 * A connection test read in the three stages Adresse · Anmeldung · Rechte. `rightsLater` while the
 * page „Umfang“ still follows: the rights of the selection are checked there.
 */
export function outcomeOfTest(
  response: SourceConnectionTestResponse,
  { signsIn, rightsLater }: { signsIn: boolean; rightsLater: boolean },
): CheckOutcome {
  const signIn = signsIn ? 'ok' : 'none'
  if (response.reachable) {
    return {
      stages: stages('ok', signIn, rightsLater ? 'open' : 'ok'),
      message: response.message,
      ok: true,
    }
  }
  if (signsIn && response.credentialsVerified === false) {
    return { stages: stages('ok', 'failed', 'open'), message: response.message, ok: false }
  }
  return { stages: stages('ok', signIn, 'failed'), message: response.message, ok: false }
}

/** A request that failed before the source answered: the stage follows the HTTP status. */
export function outcomeOfError(err: unknown, signsIn: boolean): CheckOutcome {
  const message = err instanceof Error && err.message ? err.message : FALLBACK
  const status = apiErrorStatus(err)
  if (signsIn && status === 401) {
    return { stages: stages('ok', 'failed', 'open'), message, ok: false }
  }
  if (status === 403) {
    return {
      stages: stages('ok', signsIn ? 'ok' : 'none', 'limited'),
      message,
      ok: true,
    }
  }
  return { stages: stages('failed', signsIn ? 'open' : 'none', 'open'), message, ok: false }
}

/**
 * The check of the page „Verbindung“ for a source whose test needs a selection (S3, Google Drive,
 * SharePoint): the root listing proves address and sign-in; a refused listing leaves entry by hand.
 */
export async function checkByListing(
  run: () => ReturnType<typeof browseSource>,
  noun: string,
): Promise<CheckOutcome> {
  try {
    const result = await run()
    if (!result.complete) {
      return {
        stages: stages('ok', 'ok', 'limited'),
        message:
          result.message ??
          `Die Liste der ${noun} ist unvollständig. Fehlende Einträge geben Sie im nächsten Schritt von Hand an.`,
        ok: true,
      }
    }
    return {
      stages: stages('ok', 'ok', 'open'),
      message:
        result.entries.length === 1
          ? `1 Eintrag sichtbar.`
          : `${result.entries.length} Einträge sichtbar.`,
      ok: true,
    }
  } catch (err) {
    return outcomeOfError(err, true)
  }
}

/** The check behind „Weiter“ on the page „Verbindung“ for any source. */
export async function checkConnection<V>(
  configuration: SourceConfiguration<V>,
  stepwise: StepwiseSource<V>,
  values: V,
  ctx: StepContext,
): Promise<CheckOutcome> {
  if (stepwise.check) return stepwise.check(values, ctx)
  const signsIn = stepwise.signsIn ?? true
  try {
    const payload = (stepwise.checkPayload ?? configuration.toPayload)(values)
    const response = await testLibrarySource(
      ctx.fields.probeRequest({ sourceType: ctx.sourceType, ...payload }),
    )
    return outcomeOfTest(response, { signsIn, rightsLater: Boolean(stepwise.scope) })
  } catch (err) {
    return outcomeOfError(err, signsIn)
  }
}

/** The check behind „Weiter“ on the page „Umfang“: the full test of the selection. */
export async function checkRights<V>(
  configuration: SourceConfiguration<V>,
  stepwise: StepwiseSource<V>,
  values: V,
  ctx: StepContext,
): Promise<CheckOutcome> {
  const signsIn = stepwise.signsIn ?? true
  try {
    const response = await testLibrarySource(
      ctx.fields.probeRequest({ sourceType: ctx.sourceType, ...configuration.toPayload(values) }),
    )
    const failedEntries = stepwise.scope?.failedEntries?.(response, values) ?? {}
    const outcome = outcomeOfTest(response, { signsIn, rightsLater: false })
    const anyFailed = Object.keys(failedEntries).length > 0
    return {
      ...outcome,
      stages:
        anyFailed && outcome.stages.rights === 'ok'
          ? stages('ok', outcome.stages.signIn, 'limited')
          : outcome.stages,
      ok: outcome.ok && !anyFailed,
      failedEntries,
    }
  } catch (err) {
    return outcomeOfError(err, signsIn)
  }
}

/**
 * A check result tied to what it was measured with: once `token` changes (a connection field or
 * the selection), the result is no longer shown - a stale „erreichbar“ never survives an edit.
 */
export function useCheck(token: string) {
  const [state, setState] = useState<{ token: string; outcome: CheckOutcome } | null>(null)
  const [running, setRunning] = useState(false)
  const outcome = state?.token === token ? state.outcome : null
  async function run(check: () => Promise<CheckOutcome>): Promise<CheckOutcome> {
    setRunning(true)
    try {
      const result = await check()
      setState({ token, outcome: result })
      return result
    } finally {
      setRunning(false)
    }
  }
  return { outcome, running, run, reset: () => setState(null) }
}
