import axios from 'axios'
import type { AuthConfig, AuthUser, FieldError, LocalTokenResponse } from '../types/auth'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import { DEV_USER_HEADER, getDevUser } from './devAuth'
import { CSRF_TOKEN_MISSING, UNKNOWN_ISSUER } from './apiInterceptors'

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

/** Whether this browser holds the CSRF cookie a refresh or logout would have to double-submit. */
export function hasCsrfToken(): boolean {
  return readCookie(CSRF_COOKIE) !== null
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

let inFlightLocalRefresh: Promise<LocalTokenResponse | null> | null = null

/**
 * One shared refresh attempt per moment in time (single-flight): concurrent 401s must not each
 * present the same rotating refresh token, which the backend would read as a replay and answer by
 * revoking the whole family. Without the CSRF cookie there is no local session to restore, and no
 * request is made at all - a regular OIDC sign-in must not see a failed call it never asked for.
 */
export function performLocalRefresh(): Promise<LocalTokenResponse | null> {
  if (!hasCsrfToken()) return Promise.resolve(null)
  if (!inFlightLocalRefresh) {
    inFlightLocalRefresh = refreshLocal()
      .catch(() => null)
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
  const header = err.response.headers['retry-after']
  const seconds = typeof header === 'string' ? Number.parseInt(header, 10) : Number.NaN
  return {
    status: err.response.status,
    retryAfterSeconds: Number.isFinite(seconds) ? seconds : null,
  }
}
