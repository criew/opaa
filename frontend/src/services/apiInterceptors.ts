import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import { DEV_USER_HEADER, getDevUser } from './devAuth'

type TokenGetter = () => string | null | Promise<string | null>
// #737: a single silent-renew attempt (refresh-token based, see authStore's UserManager
// config); resolves whether the retry below should go ahead with a freshly renewed token.
type RenewFn = () => Promise<boolean>
// #737: the session is unrecoverable without a full sign-in - reset local state, but never
// signoutRedirect() from here (that would also tear down the IdP session for what might be a
// single expired access token). A deliberate logout click keeps using its own full logout().
// ADR-0025: `unknown_issuer` names the one 401 a renew can never fix - the provider of this
// session is no longer enabled - so the reason is passed on for a matching explanation.
export type SessionExpiredReason = 'unknown_issuer'
type SessionExpiredFn = (reason?: SessionExpiredReason) => void

/** The backend's marker for "no enabled provider owns this token's issuer" (ADR-0025). */
export const UNKNOWN_ISSUER = 'unknown_issuer'

function isUnknownIssuer(error: { response?: { headers?: Record<string, unknown> } }): boolean {
  const challenge = error.response?.headers?.['www-authenticate']
  return typeof challenge === 'string' && challenge.includes(UNKNOWN_ISSUER)
}

interface RetryableRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

export function setupAuthInterceptors(
  client: AxiosInstance,
  getToken: TokenGetter,
  renewToken: RenewFn,
  onSessionExpired: SessionExpiredFn,
) {
  client.interceptors.request.use(async (config) => {
    const token = await getToken()
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    // Only ever set in dev mode; the backend ignores it under oidc.
    const devUser = getDevUser()
    if (devUser) {
      config.headers[DEV_USER_HEADER] = devUser
    }
    return config
  })

  client.interceptors.response.use(
    (response) => response,
    async (error) => {
      const original = error.config as RetryableRequestConfig | undefined

      // #737: a 401 used to end the whole session immediately - including on background polls
      // (indexingStore/documentStore) that fire without any user action, which is exactly what
      // made the resulting logout feel random. Now: one signinSilent() attempt, then retry the
      // original request once with the renewed token (_retry guards against retrying forever if
      // the retried request itself still comes back 401).
      if (error.response?.status === 401 && isUnknownIssuer(error)) {
        // a renewed token would carry the same issuer and be refused again
        onSessionExpired(UNKNOWN_ISSUER)
        return Promise.reject(error)
      }

      if (error.response?.status === 401 && original && !original._retry) {
        original._retry = true
        const renewed = await renewToken()
        if (renewed) {
          return client(original)
        }
        onSessionExpired()
        return Promise.reject(error)
      }

      if (error.response?.status === 401) {
        onSessionExpired()
      }
      return Promise.reject(error)
    },
  )
}
