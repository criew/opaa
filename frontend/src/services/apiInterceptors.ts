import type { AxiosInstance, InternalAxiosRequestConfig } from 'axios'
import type { PasswordChangeReason } from '../types/auth'
import { DEV_USER_HEADER, getDevUser } from './devAuth'

type TokenGetter = () => string | null | Promise<string | null>
// #737: a single silent-renew attempt (refresh-token based, see authStore's UserManager
// config); resolves whether the retry below should go ahead with a freshly renewed token.
type RenewFn = () => Promise<boolean>
// #737: the session is unrecoverable without a full sign-in - reset local state, but never
// signoutRedirect() from here (that would also tear down the IdP session for what might be a
// single expired access token). A deliberate logout click keeps using its own full logout().
// ADR-0025/ADR-0033: some 401s name a cause a renew can never fix - the provider of this session
// is no longer enabled, the account is locked, the session was revoked - so the cause is passed
// on for a matching explanation.
type SessionExpiredFn = (reason?: SessionExpiredReason) => void
/** The forced password change (ADR-0033, Entscheidung 8) with the reason the backend names. */
type PasswordChangeRequiredFn = (reason: PasswordChangeReason | null) => void

/** The backend's marker for "no enabled provider owns this token's issuer" (ADR-0025). */
export const UNKNOWN_ISSUER = 'unknown_issuer'

/**
 * Every `error_description` marker that ends a session for good (ADR-0025; ADR-0033,
 * Entscheidung 8). A 401 carrying one of these gets no renewal attempt - a renewed token would be
 * refused for the same reason.
 */
export const SESSION_ENDING_MARKERS = [
  UNKNOWN_ISSUER,
  'local_accounts_disabled',
  'account_locked',
  'account_expired',
  'session_revoked',
  'account_not_active',
  'unknown_account',
  'malformed_token',
] as const

export type SessionEndingMarker = (typeof SESSION_ENDING_MARKERS)[number]

/** A marker, optionally with its `:`-separated cause, e.g. `account_locked:failed_logins`. */
export type SessionExpiredReason = SessionEndingMarker | `${SessionEndingMarker}:${string}`

/** The backend's code for "this account must change its password first" (ADR-0033). */
export const PASSWORD_CHANGE_REQUIRED = 'PASSWORD_CHANGE_REQUIRED'

/** The backend's code for a refused CSRF double-submit on the two cookie-bearing endpoints. */
export const CSRF_TOKEN_MISSING = 'CSRF_TOKEN_MISSING'

interface ChallengeCarrier {
  response?: { headers?: Record<string, unknown>; data?: unknown }
}

function asChallengeCarrier(error: unknown): ChallengeCarrier {
  return typeof error === 'object' && error !== null ? (error as ChallengeCarrier) : {}
}

/**
 * The session-ending marker of a 401, with its cause when the backend named one; null when the
 * challenge names no such marker (an ordinary expired token, which a renewal can still fix).
 */
export function sessionEndingReason(error: unknown): SessionExpiredReason | null {
  const challenge = asChallengeCarrier(error).response?.headers?.['www-authenticate']
  if (typeof challenge !== 'string') return null
  const described = /error_description="([^"]*)"/.exec(challenge)
  const value = described ? described[1] : challenge
  const marker = value.split(':')[0] as SessionEndingMarker
  if (!SESSION_ENDING_MARKERS.includes(marker)) return null
  return value as SessionExpiredReason
}

/** The reason of a 403 that demands a password change; null when the 403 is an ordinary one. */
export function passwordChangeRequiredReason(
  error: unknown,
): PasswordChangeReason | null | undefined {
  const body = asChallengeCarrier(error).response?.data
  if (typeof body !== 'object' || body === null) return undefined
  const { code, reason } = body as { code?: unknown; reason?: unknown }
  if (code !== PASSWORD_CHANGE_REQUIRED) return undefined
  return isPasswordChangeReason(reason) ? reason : null
}

function isPasswordChangeReason(value: unknown): value is PasswordChangeReason {
  return value === 'INITIAL' || value === 'ADMIN_RESET' || value === 'SECURITY'
}

interface RetryableRequestConfig extends InternalAxiosRequestConfig {
  _retry?: boolean
}

export function setupAuthInterceptors(
  client: AxiosInstance,
  getToken: TokenGetter,
  renewToken: RenewFn,
  onSessionExpired: SessionExpiredFn,
  onPasswordChangeRequired?: PasswordChangeRequiredFn,
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
      const endingReason = error.response?.status === 401 ? sessionEndingReason(error) : null
      if (endingReason) {
        // a renewed token would be refused for exactly the same reason
        onSessionExpired(endingReason)
        return Promise.reject(error)
      }

      // ADR-0033, Entscheidung 8: the account has to set a new password before anything but
      // /api/v1/auth/local/* answers. The session is intact - only the destination changes.
      if (error.response?.status === 403) {
        const pcrReason = passwordChangeRequiredReason(error)
        if (pcrReason !== undefined) {
          onPasswordChangeRequired?.(pcrReason)
          return Promise.reject(error)
        }
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
