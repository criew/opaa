import axios from 'axios'
import type { FieldError } from '../types/auth'
import { authClient, FieldValidationError, retryAfterSecondsOf } from './authApi'

/**
 * The four public self-service endpoints of local accounts (ADR-0033, Entscheidung 11). None of
 * them needs a session, all of them are rate-limited, and none may say more than the backend does:
 * "Passwort vergessen" and the registration answer the same whether or not an account exists, and
 * every unusable link is the same `TOKEN_INVALID`.
 */
const LOCAL_AUTH_BASE = '/v1/auth/local'

/** The stable code a 400 carries when the link itself is the problem, not the password. */
const TOKEN_INVALID = 'TOKEN_INVALID'

/**
 * The link cannot be redeemed - and deliberately without a reason: unknown, expired, consumed, of
 * another purpose and "the account is meanwhile locked" all arrive as the same answer.
 */
export class LinkInvalidError extends Error {
  constructor() {
    super(TOKEN_INVALID)
    this.name = 'LinkInvalidError'
  }
}

/** A 429; `retryAfterSeconds` is the `Retry-After` header, null when it is absent or unusable. */
export class RateLimitedError extends Error {
  readonly retryAfterSeconds: number | null

  constructor(retryAfterSeconds: number | null) {
    super('TOO_MANY_REQUESTS')
    this.name = 'RateLimitedError'
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/**
 * The flow does not exist in this installation. The backend answers exactly like an unknown route
 * (404) so its existence cannot be probed; the pages normally know the state from `/auth/config`
 * and never get here, except when a switch is turned off between page load and submission.
 */
export class FlowUnavailableError extends Error {
  constructor() {
    super('NOT_FOUND')
    this.name = 'FlowUnavailableError'
  }
}

/**
 * Translates a refused self-service call into the one error class its page distinguishes. Anything
 * else - a 5xx, a network failure, a timeout - is passed on unchanged: a page must be able to say
 * "not submitted" rather than blame the entry.
 */
function rethrowAsSelfServiceFailure(err: unknown, fallbackMessage: string): never {
  if (axios.isAxiosError(err) && err.response) {
    const status = err.response.status
    if (status === 429) throw new RateLimitedError(retryAfterSecondsOf(err))
    if (status === 404) throw new FlowUnavailableError()
    if (status === 400) {
      const body = err.response.data as
        { code?: string; error?: string; fieldErrors?: FieldError[] } | undefined
      if (body?.code === TOKEN_INVALID) throw new LinkInvalidError()
      throw new FieldValidationError(body?.fieldErrors ?? [], body?.error ?? fallbackMessage)
    }
  }
  throw err
}

/**
 * Redeems an invitation or a password-reset link - one call for both purposes, because the page
 * behind the link must not have to know which kind it is.
 */
export async function setPassword(token: string, newPassword: string): Promise<void> {
  try {
    await authClient.post(`${LOCAL_AUTH_BASE}/set-password`, { token, newPassword })
  } catch (err) {
    rethrowAsSelfServiceFailure(err, 'Das Passwort wurde nicht angenommen.')
  }
}

/** Asks for a password-reset link. Answers 204 whether or not an account exists. */
export async function forgotPassword(email: string): Promise<void> {
  try {
    await authClient.post(`${LOCAL_AUTH_BASE}/forgot-password`, { email })
  } catch (err) {
    rethrowAsSelfServiceFailure(err, 'Die Anfrage wurde nicht angenommen.')
  }
}

/** Registers an account. Answers 202 whether or not one was created. */
export async function register(
  email: string,
  displayName: string,
  password: string,
): Promise<void> {
  try {
    await authClient.post(`${LOCAL_AUTH_BASE}/register`, { email, displayName, password })
  } catch (err) {
    rethrowAsSelfServiceFailure(err, 'Die Registrierung wurde nicht angenommen.')
  }
}

/** Confirms the address of a self-registered account; triggered by the page, never by the link. */
export async function verifyEmail(token: string): Promise<void> {
  try {
    await authClient.post(`${LOCAL_AUTH_BASE}/verify-email`, { token })
  } catch (err) {
    rethrowAsSelfServiceFailure(err, 'Die Bestätigung war nicht möglich.')
  }
}
