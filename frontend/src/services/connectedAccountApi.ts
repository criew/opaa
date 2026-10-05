import { AxiosError } from 'axios'
import type {
  ConnectedAccount,
  ConnectedAccountConnectRequest,
  ConnectedAccountsOverview,
  ConnectionAuthorizationCompleteRequest,
  ConnectionAuthorizationCompleteResponse,
  ConnectionAuthorizationStartRequest,
  ConnectionAuthorizationStartResponse,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

const ME = '/v1/me/connected-accounts'
const AUTHORIZATIONS = '/v1/connections/authorizations'

/**
 * A refused connection attempt. Deliberately carries no `cause`: the original request - and with
 * it the secret - must not outlive the attempt inside an error object.
 */
export class ConnectAccountError extends Error {
  readonly status: number | null
  readonly code: string | null

  constructor(message: string, status: number | null, code: string | null) {
    super(message)
    this.name = 'ConnectAccountError'
    this.status = status
    this.code = code
  }
}

export async function listMyConnectedAccounts(): Promise<ConnectedAccountsOverview> {
  try {
    const { data } = await client.get<ConnectedAccountsOverview>(ME)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** The refusal as a {@link ConnectAccountError}, without the request that carried a secret. */
function refusal(err: unknown): ConnectAccountError {
  if (err instanceof AxiosError) {
    const body = err.response?.data as { error?: unknown; code?: unknown } | undefined
    return new ConnectAccountError(
      typeof body?.error === 'string' ? body.error : '',
      err.response?.status ?? null,
      typeof body?.code === 'string' ? body.code : null,
    )
  }
  return new ConnectAccountError('', null, null)
}

/** Connects or reconnects the caller's account; the secret is sent once and never returned. */
export async function connectMyAccount(
  profileId: string,
  request: ConnectedAccountConnectRequest,
): Promise<ConnectedAccount> {
  try {
    const { data } = await client.put<ConnectedAccount>(`${ME}/${profileId}`, request)
    return data
  } catch (err) {
    throw refusal(err)
  }
}

/** Starts the provider's consent for the caller's own account on an OAuth profile. */
export async function startAccountAuthorization(
  profileId: string,
): Promise<ConnectionAuthorizationStartResponse> {
  try {
    const { data } = await client.post<ConnectionAuthorizationStartResponse>(AUTHORIZATIONS, {
      profileId,
      purpose: 'ACCOUNT',
    })
    return data
  } catch (err) {
    throw refusal(err)
  }
}

/** Starts the provider's consent for a library's source ("Quelle verbinden"). */
export async function startSourceAuthorization(
  request: ConnectionAuthorizationStartRequest,
): Promise<ConnectionAuthorizationStartResponse> {
  try {
    const { data } = await client.post<ConnectionAuthorizationStartResponse>(
      AUTHORIZATIONS,
      request,
    )
    return data
  } catch (err) {
    throw refusal(err)
  }
}

/**
 * Completes a consent with what the provider sent back. The code and state are single-use; the
 * error carries neither.
 */
export async function completeConnectionAuthorization(
  request: ConnectionAuthorizationCompleteRequest,
): Promise<ConnectionAuthorizationCompleteResponse> {
  try {
    const { data } = await client.post<ConnectionAuthorizationCompleteResponse>(
      `${AUTHORIZATIONS}/complete`,
      request,
    )
    return data
  } catch (err) {
    throw refusal(err)
  }
}

export async function disconnectMyAccount(profileId: string): Promise<void> {
  try {
    await client.delete(`${ME}/${profileId}`)
  } catch (err) {
    normalizeError(err)
  }
}
