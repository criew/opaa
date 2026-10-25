import axios from 'axios'
import { apiErrorStatus } from '@/shared/api/apiErrorDetails'
import type { InstallationState, InstallationStep, StepInput, SupportedStepId } from './types'

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
export async function getInstallation(token: string): Promise<InstallationState> {
  const { data } = await installationClient.get<InstallationState>('', bearer(token))
  return data
}

/** Stores the step's values; the backend validates every field and resets the step's check. */
export async function saveInstallationStep<S extends SupportedStepId>(
  token: string,
  step: S,
  values: StepInput[S],
): Promise<InstallationStep> {
  const { data } = await installationClient.put<InstallationStep>(
    `/steps/${step}`,
    values,
    bearer(token),
  )
  return data
}

/** Runs the check of the stored values, e.g. the connection test of a model. */
export async function checkInstallationStep(
  token: string,
  step: SupportedStepId,
): Promise<InstallationStep> {
  const { data } = await installationClient.post<InstallationStep>(
    `/steps/${step}/check`,
    undefined,
    bearer(token),
  )
  return data
}

/** Completes the installation; the API answers 404 on every instance afterwards. */
export async function completeInstallation(token: string): Promise<void> {
  await installationClient.post('/complete', undefined, bearer(token))
}

/**
 * Whether this installation still waits for its set-up, asked without a token: an installed
 * instance answers 404, one in installation mode refuses the missing token. Anything else -
 * network failure, a proxy error - counts as installed, so the regular sign-in stays reachable.
 */
export async function probeInstallationPending(): Promise<boolean> {
  try {
    await installationClient.get('')
    return true
  } catch (err) {
    const status = apiErrorStatus(err)
    return status === 401 || status === 403
  }
}
