import axios from 'axios'
import type { LocalHandoverPreviewResponse } from '../types/api'
import { authClient, retryAfterSecondsOf } from './authApi'
import { LinkInvalidError, RateLimitedError } from './selfServiceApi'

/**
 * The two public endpoints of a handover (ADR-0033, Entscheidung 12). They run on {@link
 * authClient}, which carries no interceptor and therefore **no** `Authorization` header: a provider
 * token in the header would be provisioned into a new account by the backend's filter chain before
 * the endpoint runs - the one thing the redemption has to find absent. The provider's token travels
 * in the body of {@link redeemHandover} and nowhere else.
 */
const HANDOVER_BASE = '/v1/auth/local/handover'

/** An account already exists under the provider identity; ADR-0025 merges nothing. */
export class ProviderAccountExistsError extends Error {
  constructor() {
    super('PROVIDER_ACCOUNT_EXISTS')
    this.name = 'ProviderAccountExistsError'
  }
}

/** The sign-in happened at another provider than the handover names. */
export class ProviderMismatchError extends Error {
  constructor() {
    super('PROVIDER_MISMATCH')
    this.name = 'ProviderMismatchError'
  }
}

/** The handover is refused for this account - emergency anchor, or last administrator. */
export class HandoverRefusedError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'HandoverRefusedError'
  }
}

/** The provider token was not accepted; the person signs in again and reopens the link. */
export class ProviderSignInInvalidError extends Error {
  constructor() {
    super('PROVIDER_TOKEN_INVALID')
    this.name = 'ProviderSignInInvalidError'
  }
}

function rethrow(err: unknown): never {
  if (axios.isAxiosError(err) && err.response) {
    const status = err.response.status
    const body = err.response.data as { code?: string; error?: string } | undefined
    if (status === 429) throw new RateLimitedError(retryAfterSecondsOf(err))
    if (status === 401) throw new ProviderSignInInvalidError()
    if (status === 400 && body?.code === 'TOKEN_INVALID') throw new LinkInvalidError()
    if (status === 409) {
      if (body?.code === 'PROVIDER_ACCOUNT_EXISTS') throw new ProviderAccountExistsError()
      if (body?.code === 'PROVIDER_MISMATCH') throw new ProviderMismatchError()
      throw new HandoverRefusedError(body?.error ?? 'Die Übergabe wurde abgelehnt.')
    }
  }
  throw err
}

/** What the handover would move. Consumes nothing - the code stays redeemable. */
export async function previewHandover(token: string): Promise<LocalHandoverPreviewResponse> {
  try {
    const { data } = await authClient.post<LocalHandoverPreviewResponse>(
      `${HANDOVER_BASE}/preview`,
      { token },
    )
    return data
  } catch (err) {
    rethrow(err)
  }
}

/** Completes the handover. There is no way back from here (ADR-0033, Grenzen). */
export async function redeemHandover(token: string, providerToken: string): Promise<void> {
  try {
    await authClient.post(`${HANDOVER_BASE}/redeem`, { token, providerToken })
  } catch (err) {
    rethrow(err)
  }
}
