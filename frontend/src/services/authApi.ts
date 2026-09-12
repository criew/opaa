import axios from 'axios'
import type { AuthConfig, AuthUser, FieldError, LocalTokenResponse } from '../types/auth'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import { DEV_USER_HEADER, getDevUser } from './devAuth'
import { CSRF_TOKEN_MISSING, sessionEndingReason, UNKNOWN_ISSUER } from './apiInterceptors'

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
// store this client serves) - the one 401 it must understand is unknown_issuer. The timeout is
// what bounds the cross-tab refresh lock (see performLocalRefresh): a request that never settles
// would otherwise keep every other tab of this browser waiting for its own renewal.
const AUTH_REQUEST_TIMEOUT_MS = 15_000
/**
 * Exported for the self-service calls in {@link ./selfServiceApi}: same base URL, same timeout and
 * the same deliberate absence of the renew interceptor - a second client would be a second set of
 * those decisions to keep in step.
 */
export const authClient = axios.create({ baseURL: '/api', timeout: AUTH_REQUEST_TIMEOUT_MS })

export async function getAuthConfig(): Promise<AuthConfig> {
  const { data } = await authClient.get<AuthConfig>('/v1/auth/config')
  // A backend without local accounts omits the block entirely; "absent" means "switched off",
  // never "unknown", so every caller can read the flags without a null check.
  return { ...data, localAccounts: data.localAccounts ?? LOCAL_ACCOUNTS_DISABLED }
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

/** Non-HttpOnly half of the CSRF double-submit pair the backend sets (ADR-0033, Entscheidung 7). */
const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const LOCAL_AUTH_BASE = '/v1/auth/local'

function readCookie(name: string): string | null {
  const match = new RegExp(`(?:^|; )${name}=([^;]*)`).exec(document.cookie)
  if (!match) return null
  try {
    return decodeURIComponent(match[1])
  } catch {
    return match[1]
  }
}

/**
 * The double-submit header for the two cookie-bearing endpoints. Empty when no token is present -
 * the caller decides whether to attempt the request at all (the store does not: no cookie means
 * no local session, and a request would only produce a 4xx the sign-in page must not show).
 */
export function csrfHeaders(): Record<string, string> {
  const token = readCookie(CSRF_COOKIE)
  return token ? { [CSRF_HEADER]: token } : {}
}

/** A 400 whose body names the offending request fields (ADR-0033, Entscheidung 9). */
export class FieldValidationError extends Error {
  readonly fieldErrors: FieldError[]

  constructor(fieldErrors: FieldError[], message: string) {
    super(message)
    this.name = 'FieldValidationError'
    this.fieldErrors = fieldErrors
  }
}

export async function loginLocal(email: string, password: string): Promise<LocalTokenResponse> {
  const { data } = await authClient.post<LocalTokenResponse>(
    `${LOCAL_AUTH_BASE}/login`,
    { email, password },
    { withCredentials: true },
  )
  return data
}

/**
 * Re-primes the CSRF cookie from a public endpoint. The backend emits it on any response, so one
 * plain GET is enough to replace a token it no longer accepts.
 */
async function primeCsrfToken(): Promise<void> {
  try {
    await authClient.get('/v1/auth/config', { withCredentials: true })
  } catch {
    // best effort - the repeated call below simply fails again if there is no connectivity
  }
}

function isCsrfTokenMissing(err: unknown): boolean {
  if (!axios.isAxiosError(err) || err.response?.status !== 403) return false
  const body = err.response.data as { code?: unknown } | undefined
  return body?.code === CSRF_TOKEN_MISSING
}

/** One repeat with a freshly primed CSRF cookie; every other failure is passed on unchanged. */
async function withCsrfRetry<T>(call: () => Promise<T>): Promise<T> {
  try {
    return await call()
  } catch (err) {
    if (!isCsrfTokenMissing(err)) throw err
    await primeCsrfToken()
    return call()
  }
}

export async function refreshLocal(): Promise<LocalTokenResponse> {
  const { data } = await withCsrfRetry(() =>
    authClient.post<LocalTokenResponse>(`${LOCAL_AUTH_BASE}/refresh`, null, {
      withCredentials: true,
      headers: csrfHeaders(),
    }),
  )
  return data
}

/**
 * Ends the local session at the backend. `token` is sent only while it is still valid - an
 * expired bearer makes the resource server refuse the call before `permitAll` is reached, and the
 * session would then outlive the click.
 */
export async function logoutLocal(token: string | null): Promise<void> {
  await withCsrfRetry(() => {
    const headers: Record<string, string> = { ...csrfHeaders() }
    if (token) headers.Authorization = `Bearer ${token}`
    return authClient.post(`${LOCAL_AUTH_BASE}/logout`, null, { withCredentials: true, headers })
  })
}

/**
 * Changes the password of the signed-in local account and returns the session it mints in the
 * same answer - a token without the `pcr` claim. Policy violations arrive as a
 * {@link FieldValidationError}.
 */
export async function changePassword(
  token: string | null,
  currentPassword: string,
  newPassword: string,
): Promise<LocalTokenResponse> {
  const headers: Record<string, string> = {}
  if (token) headers.Authorization = `Bearer ${token}`
  try {
    const { data } = await authClient.post<LocalTokenResponse>(
      `${LOCAL_AUTH_BASE}/change-password`,
      { currentPassword, newPassword },
      { withCredentials: true, headers },
    )
    return data
  } catch (err) {
    if (axios.isAxiosError(err) && err.response?.status === 400) {
      // fieldErrors may be absent or an empty array - a 400 without them is still a rejected
      // input, and the message then carries the whole reason.
      const body = err.response.data as { fieldErrors?: FieldError[]; error?: string } | undefined
      throw new FieldValidationError(
        body?.fieldErrors ?? [],
        body?.error ?? 'Das Passwort konnte nicht geändert werden.',
      )
    }
    throw err
  }
}

/**
 * The non-secret note that this browser last held a local session (ADR-0033, Entscheidung 7). It
 * gates the one refresh attempt at start-up. The CSRF cookie cannot do that job: `CsrfCookieFilter`
 * sets it on *every* response, so it is already there after the first `GET /auth/config` of a
 * signed-out visitor, and the gate would let a pointless `POST /refresh` through on every page
 * load. Deliberately in localStorage and deliberately not a secret: it says nothing but "there was
 * a local session here", survives a tab restart, and gives the refresh token - which stays in the
 * HttpOnly cookie - away to nobody.
 */
export const LAST_SESSION_KIND_STORAGE_KEY = 'opaa.auth.lastSessionKind'
const LOCAL_SESSION_KIND = 'local'

/** The cross-tab lock name; see {@link performLocalRefresh}. */
const REFRESH_LOCK_NAME = 'opaa.local.refresh'

export function rememberLocalSession(): void {
  try {
    localStorage.setItem(LAST_SESSION_KIND_STORAGE_KEY, LOCAL_SESSION_KIND)
  } catch {
    // storage may be unavailable (private mode); the next reload then simply starts signed out
  }
}

export function forgetLocalSession(): void {
  try {
    localStorage.removeItem(LAST_SESSION_KIND_STORAGE_KEY)
  } catch {
    // see rememberLocalSession
  }
}

/**
 * Whether a failure means the session is over rather than momentarily out of reach: a named
 * session-ending marker, or a plain `401`/`403`. A `5xx` or a network error says nothing about the
 * session - dropping the note there would keep every later reload from even trying to restore it.
 */
export function endsSession(err: unknown): boolean {
  if (sessionEndingReason(err) !== null) return true
  if (!axios.isAxiosError(err)) return false
  const status = err.response?.status
  return status === 401 || status === 403
}

export function hadLocalSession(): boolean {
  try {
    return localStorage.getItem(LAST_SESSION_KIND_STORAGE_KEY) === LOCAL_SESSION_KIND
  } catch {
    return false
  }
}

let inFlightLocalRefresh: Promise<LocalTokenResponse | null> | null = null

/**
 * Serialises the refresh across every tab of this browser. The refresh token rotates: two tabs
 * presenting the same one makes the loser a replay in the backend's eyes, which revokes every
 * session of the account. The Web Lock makes the second tab wait and then present the rotated
 * token instead. Where `navigator.locks` does not exist (jsdom, older browsers) the call simply
 * runs - the tab-local single-flight below still holds.
 */
async function withRefreshLock<T>(call: () => Promise<T>): Promise<T> {
  const locks: LockManager | undefined =
    typeof navigator === 'undefined' ? undefined : navigator.locks
  if (!locks) return call()
  return locks.request(REFRESH_LOCK_NAME, call) as Promise<T>
}

/**
 * One shared refresh attempt per moment in time: tab-local single-flight around a cross-tab Web
 * Lock. Without the note of a previous local session no request is made at all - a regular OIDC
 * sign-in must not see a failed call it never asked for. A `401` is the end of the session: the
 * note is dropped, so the next page load starts signed out instead of asking again.
 */
export function performLocalRefresh(): Promise<LocalTokenResponse | null> {
  if (!hadLocalSession()) return Promise.resolve(null)
  if (!inFlightLocalRefresh) {
    inFlightLocalRefresh = withRefreshLock(() => refreshLocal())
      .catch((err: unknown) => {
        if (axios.isAxiosError(err) && err.response?.status === 401) forgetLocalSession()
        return null
      })
      .finally(() => {
        inFlightLocalRefresh = null
      })
  }
  return inFlightLocalRefresh
}

/**
 * What a refused local sign-in was: the HTTP status (null when the request never reached the
 * backend) and the `Retry-After` seconds of a rate-limited answer. Extracting it here keeps the
 * axios shape out of the store, which only picks the sentence to show.
 */
export function describeLocalSignInFailure(err: unknown): {
  status: number | null
  retryAfterSeconds: number | null
} {
  if (!axios.isAxiosError(err) || !err.response) {
    return { status: null, retryAfterSeconds: null }
  }
  return {
    status: err.response.status,
    retryAfterSeconds: retryAfterSecondsOf(err),
  }
}

/** The `Retry-After` seconds of a refused request; null when the header is absent or unusable. */
export function retryAfterSecondsOf(err: unknown): number | null {
  if (!axios.isAxiosError(err) || !err.response) return null
  const header = err.response.headers['retry-after']
  const seconds = typeof header === 'string' ? Number.parseInt(header, 10) : Number.NaN
  return Number.isFinite(seconds) ? seconds : null
}
