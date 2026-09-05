import axios from 'axios'
import type { AuthConfig, AuthUser } from '../types/auth'
import { DEV_USER_HEADER, getDevUser } from './devAuth'
import { UNKNOWN_ISSUER } from './apiInterceptors'

/**
 * Thrown by {@link getMe} when the backend refuses the token with {@code unknown_issuer}
 * (ADR-0025): the provider of this session is no longer enabled - a renewed token would carry
 * the same issuer, so the session is over, not merely expired.
 */
export class UnknownIssuerError extends Error {
  constructor() {
    super(UNKNOWN_ISSUER)
    this.name = 'UnknownIssuerError'
  }
}

// A client of its own, without the renew interceptor of api.ts (which depends on the auth
// store this client serves) - the one 401 it must understand is unknown_issuer.
const authClient = axios.create({ baseURL: '/api' })

export async function getAuthConfig(): Promise<AuthConfig> {
  const { data } = await authClient.get<AuthConfig>('/v1/auth/config')
  return data
}

/**
 * Fetches the current user. `token` is null in dev mode, where the backend derives the identity
 * from the selected dev user rather than from a bearer token.
 */
export async function getMe(token: string | null): Promise<AuthUser> {
  const headers: Record<string, string> = {}
  if (token) {
    headers.Authorization = `Bearer ${token}`
  }
  const devUser = getDevUser()
  if (devUser) {
    headers[DEV_USER_HEADER] = devUser
  }

  try {
    const { data } = await authClient.get<AuthUser>('/v1/auth/me', { headers })
    return data
  } catch (err) {
    if (axios.isAxiosError(err) && err.response?.status === 401) {
      const challenge = err.response.headers['www-authenticate']
      if (typeof challenge === 'string' && challenge.includes(UNKNOWN_ISSUER)) {
        throw new UnknownIssuerError()
      }
    }
    throw err
  }
}
