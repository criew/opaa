import axios from 'axios'
import { apiErrorStatus } from '@/shared/api/apiErrorDetails'
import {
  STEP_REQUEST_KEY,
  type InstallationStatusResponse,
  type InstallationStepId,
  type InstallationStepState,
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
 */
export async function checkInstallationStep(
  token: string,
  step: InstallationStepId,
): Promise<InstallationStepState> {
  const { data } = await installationClient.post<InstallationStepState>(
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
 * instance answers 404, one in installation mode refuses the missing token (401) or the client's
 * network (403). Anything else - network failure, a proxy error - counts as installed, so the
 * regular sign-in stays reachable.
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
