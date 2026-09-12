import { http, HttpResponse } from 'msw'
import { mockAuthConfig, mockLocalAccount } from './fixtures'
import {
  consumeMockToken,
  MOCK_RATE_LIMITED_EMAIL,
  MOCK_RATE_LIMITED_TOKEN,
  MOCK_RETRY_AFTER_SECONDS,
  MOCK_SET_PASSWORD_TOKEN,
  MOCK_VERIFY_EMAIL_TOKEN,
} from './localAuthFixtures'

/**
 * The four public self-service endpoints (#1538) as MSW handlers - the invariants the pages depend
 * on, the same ones the backend enforces:
 *
 * - a link is redeemable exactly once, and every unusable link is the same 400 `TOKEN_INVALID` -
 *   unknown, used up and of the wrong purpose are indistinguishable,
 * - "Passwort vergessen" and the registration answer the same for an address with an account and
 *   one without (204 / 202), so no page can turn into an account oracle,
 * - a switched-off flow answers 404 like an unknown route, read from the same
 *   `localAccounts` block the SPA gets from `/auth/config`,
 * - the password policy answers with `fieldErrors` on the field that carries the password.
 */

/** Mirrors the backend's minimum; the maximum and the block list are the policy's own. */
function passwordMinLength(): number {
  return mockAuthConfig.localAccounts?.passwordMinLength ?? 12
}

const COMMON_PASSWORDS = ['passwort', 'password', 'geheim', '123456789012']

function errorBody(message: string, extra: Record<string, unknown> = {}): Record<string, unknown> {
  return { error: message, status: 400, timestamp: new Date().toISOString(), ...extra }
}

function tokenInvalid() {
  return HttpResponse.json(
    errorBody('Dieser Link ist nicht mehr gültig.', { code: 'TOKEN_INVALID' }),
    { status: 400 },
  )
}

function tooManyRequests() {
  return HttpResponse.json(
    {
      error: 'Zu viele Anfragen.',
      status: 429,
      timestamp: new Date().toISOString(),
    },
    { status: 429, headers: { 'Retry-After': String(MOCK_RETRY_AFTER_SECONDS) } },
  )
}

function notFound() {
  return HttpResponse.json(
    { error: 'Not Found', status: 404, timestamp: new Date().toISOString() },
    { status: 404 },
  )
}

/** Every violated rule at once, as the backend's policy reports them (ADR-0033, Entscheidung 9). */
function passwordViolations(field: string, password: string, email: string | null) {
  const violations: { field: string; code: string; message: string }[] = []
  const min = passwordMinLength()
  if (password.length < min) {
    violations.push({
      field,
      code: 'TOO_SHORT',
      message: `Das Passwort muss mindestens ${min} Zeichen lang sein.`,
    })
  }
  if (password.length > 64) {
    violations.push({ field, code: 'TOO_LONG', message: 'Das Passwort ist zu lang.' })
  }
  if (email && password.toLowerCase() === email.trim().toLowerCase()) {
    violations.push({
      field,
      code: 'EQUALS_EMAIL',
      message: 'Das Passwort darf nicht die E-Mail-Adresse sein.',
    })
  }
  if (COMMON_PASSWORDS.includes(password.toLowerCase())) {
    violations.push({ field, code: 'TOO_COMMON', message: 'Dieses Passwort ist zu häufig.' })
  }
  return violations
}

/** The backend's plausibility rule for an address, not a full RFC check. */
function isPlausibleAddress(email: string): boolean {
  const at = email.indexOf('@')
  return (
    at > 0 &&
    at < email.length - 1 &&
    email.indexOf('@', at + 1) < 0 &&
    email.length <= 320 &&
    !/\s/.test(email)
  )
}

export const localAuthHandlers = [
  http.post('/api/v1/auth/local/set-password', async ({ request }) => {
    const body = (await request.json()) as { token?: string; newPassword?: string }
    const password = body.newPassword ?? ''
    // The policy is checked before the link is consumed, as in the backend: a refused password
    // leaves the link open, or a typo would cost the person their invitation.
    const violations = passwordViolations('newPassword', password, mockLocalAccount.email)
    if (violations.length > 0) {
      return HttpResponse.json(
        errorBody('Das neue Passwort entspricht nicht der Passwortrichtlinie.', {
          fieldErrors: violations,
        }),
        { status: 400 },
      )
    }
    if (body.token !== MOCK_SET_PASSWORD_TOKEN || !consumeMockToken(`set:${body.token}`)) {
      return tokenInvalid()
    }
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/auth/local/forgot-password', async ({ request }) => {
    if (!mockAuthConfig.localAccounts?.passwordResetEnabled) return notFound()
    const body = (await request.json()) as { email?: string }
    const email = (body.email ?? '').trim()
    if (!isPlausibleAddress(email)) {
      return HttpResponse.json(
        errorBody('Bitte geben Sie eine gültige E-Mail-Adresse an.', {
          fieldErrors: [
            {
              field: 'email',
              code: 'INVALID_ADDRESS',
              message: 'Bitte geben Sie eine gültige E-Mail-Adresse an.',
            },
          ],
        }),
        { status: 400 },
      )
    }
    if (email.toLowerCase() === MOCK_RATE_LIMITED_EMAIL) return tooManyRequests()
    // The same answer for an address with an account and one without - that is the whole contract.
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/auth/local/register', async ({ request }) => {
    if (!mockAuthConfig.localAccounts?.selfRegistrationEnabled) return notFound()
    const body = (await request.json()) as {
      email?: string
      displayName?: string
      password?: string
    }
    const email = (body.email ?? '').trim()
    const displayName = (body.displayName ?? '').trim()
    const fieldErrors: { field: string; code: string; message: string }[] = []
    if (!isPlausibleAddress(email)) {
      fieldErrors.push({
        field: 'email',
        code: 'INVALID_ADDRESS',
        message: 'Bitte geben Sie eine gültige E-Mail-Adresse an.',
      })
    }
    if (displayName === '') {
      fieldErrors.push({
        field: 'displayName',
        code: 'REQUIRED',
        message: 'Das Feld darf nicht leer sein.',
      })
    } else if (displayName.length > 255) {
      fieldErrors.push({
        field: 'displayName',
        code: 'TOO_LONG',
        message: 'Höchstens 255 Zeichen sind erlaubt.',
      })
    }
    fieldErrors.push(...passwordViolations('password', body.password ?? '', email))
    if (fieldErrors.length > 0) {
      return HttpResponse.json(
        errorBody('Die Eingaben wurden nicht angenommen.', { fieldErrors }),
        {
          status: 400,
        },
      )
    }
    if (email.toLowerCase() === MOCK_RATE_LIMITED_EMAIL) return tooManyRequests()
    // A taken address, a domain outside the list and a free address all answer alike.
    return new HttpResponse(null, { status: 202 })
  }),

  http.post('/api/v1/auth/local/verify-email', async ({ request }) => {
    const body = (await request.json()) as { token?: string }
    if (body.token === MOCK_RATE_LIMITED_TOKEN) return tooManyRequests()
    if (body.token !== MOCK_VERIFY_EMAIL_TOKEN || !consumeMockToken(`verify:${body.token}`)) {
      return tokenInvalid()
    }
    return new HttpResponse(null, { status: 204 })
  }),
]
