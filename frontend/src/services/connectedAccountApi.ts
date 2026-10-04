import { AxiosError } from 'axios'
import type {
  ConnectedAccount,
  ConnectedAccountConnectRequest,
  ConnectedAccountsOverview,
} from '../types/api'
import { apiClient as client, normalizeError } from './api'

const ME = '/v1/me/connected-accounts'

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

/** Connects or reconnects the caller's account; the secret is sent once and never returned. */
export async function connectMyAccount(
  profileId: string,
  request: ConnectedAccountConnectRequest,
): Promise<ConnectedAccount> {
  try {
    const { data } = await client.put<ConnectedAccount>(`${ME}/${profileId}`, request)
    return data
  } catch (err) {
    if (err instanceof AxiosError) {
      const body = err.response?.data as { error?: unknown; code?: unknown } | undefined
      throw new ConnectAccountError(
        typeof body?.error === 'string' ? body.error : '',
        err.response?.status ?? null,
        typeof body?.code === 'string' ? body.code : null,
      )
    }
    throw new ConnectAccountError('', null, null)
  }
}

export async function disconnectMyAccount(profileId: string): Promise<void> {
  try {
    await client.delete(`${ME}/${profileId}`)
  } catch (err) {
    normalizeError(err)
  }
}
