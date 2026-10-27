import axios from 'axios'
import type { LlmModelDiscoveryResponse } from '@/shared/api/types'
import {
  STEP_REQUEST_KEY,
  type InstallationModelDiscoveryRequest,
  type InstallationStatusResponse,
  type InstallationStepCheckRequest,
  type InstallationStepId,
  type InstallationStepState,
  type ModelStepId,
  type StepInput,
} from './types'

const INSTALLATION_REQUEST_TIMEOUT_MS = 30_000

/**
 * A client of its own: the installation token is the only credential, so neither the session
 * header nor the renew interceptor of the shared client may touch these requests - a 401 here
 * means a wrong token, not an expired session. A check may wait on a model, hence the timeout.
 */
const installationClient = axios.create({
  baseURL: '/api/v1/installation',
  timeout: INSTALLATION_REQUEST_TIMEOUT_MS,
})

function bearer(token: string) {
  return { headers: { Authorization: `Bearer ${token}` } }
}

/** The state of every step; doubles as the check of the token. */
export async function getInstallation(token: string): Promise<InstallationStatusResponse> {
  const { data } = await installationClient.get<InstallationStatusResponse>('', bearer(token))
  return data
}

/** Stores the step's values; the backend validates every field and discards the step's check. */
export async function saveInstallationStep<S extends InstallationStepId>(
  token: string,
  step: S,
  values: StepInput[S],
): Promise<InstallationStepState> {
  const { data } = await installationClient.put<InstallationStepState>(
    `/steps/${step}`,
    { [STEP_REQUEST_KEY[step]]: values },
    bearer(token),
  )
  return data
}

/**
 * Runs the check of the stored values, e.g. the connection test of a model. A failed check is
 * no error of the call: the returned state says `checked: false` and why in `checkMessage`.
 * `options` carries the test recipient of the mail step.
 */
export async function checkInstallationStep(
  token: string,
  step: InstallationStepId,
  options?: InstallationStepCheckRequest,
): Promise<InstallationStepState> {
  const { data } = await installationClient.post<InstallationStepState>(
    `/steps/${step}/check`,
    options,
    bearer(token),
  )
  return data
}

/** Skips an optional step: its saved values are discarded, and the completion leaves it out. */
export async function skipInstallationStep(
  token: string,
  step: InstallationStepId,
): Promise<InstallationStepState> {
  const { data } = await installationClient.post<InstallationStepState>(
    `/steps/${step}/skip`,
    undefined,
    bearer(token),
  )
  return data
}

/**
 * Lists the models the endpoint of a model step offers; nothing is saved. An omitted key uses
 * the one stored with the step, only for its address; an empty key sends none.
 */
export async function discoverInstallationModels(
  token: string,
  step: ModelStepId,
  request: InstallationModelDiscoveryRequest,
): Promise<LlmModelDiscoveryResponse> {
  const { data } = await installationClient.post<LlmModelDiscoveryResponse>(
    `/steps/${step}/discover`,
    request,
    bearer(token),
  )
  return data
}

/** Completes the installation; the API answers 404 on every instance afterwards. */
export async function completeInstallation(token: string): Promise<void> {
  await installationClient.post('/complete', undefined, bearer(token))
}
