import { http, HttpResponse } from 'msw'
import type {
  LocalAuthSettingsUpdateRequest,
  LocalUserCreateRequest,
  LocalUserResponse,
  LocalUserUpdateRequest,
} from '../types/api'
import {
  LAST_ADMIN_USER_ID,
  MAIL_FAILING_ADDRESS_SUFFIX,
  MOCK_SELF_USER_ID,
  OWNS_CONTENT_USER_ID,
  mockLocalAuthSettings,
  mockLocalUsers,
  setMockLocalAuthSettings,
  setMockLocalUsers,
} from './localUserFixtures'

/**
 * Die Admin-API der lokalen Konten (#1537) als MSW-Handler – mit genau den Invarianten, auf die
 * sich die Oberfläche verlässt:
 *
 * - die Liste führt nur lokale Konten, hat keine Sortierung nach Aktivität und lehnt eine
 *   Seitengröße über 50 mit 400 ab,
 * - Einladung und Zurücksetzen liefern den Link **genau einmal** und nur, wenn die Mail nicht
 *   hinausgegangen ist (Adressen auf `@kaputt.example`),
 * - die Konfliktcodes fallen dort an, wo sie auch im Backend anfallen (eigenes Konto,
 *   Notanker-Konto, letzter anmeldefähiger Systemverwalter, besitzende Konten),
 * - das Einschalten von „Passwort vergessen"/Selbstregistrierung ohne öffentliche Basis-URL ist
 *   409, mit leerer Domänenliste 400.
 */

const SORT_KEYS = ['displayName', 'email', 'expiresAt', 'createdAt'] as const
type SortKey = (typeof SORT_KEYS)[number]

function conflict(code: string, error: string) {
  return HttpResponse.json(
    { error, status: 409, code, timestamp: new Date().toISOString() },
    { status: 409 },
  )
}

function badRequest(
  error: string,
  fieldErrors?: Array<{ field: string; code: string; message: string }>,
) {
  return HttpResponse.json(
    { error, status: 400, timestamp: new Date().toISOString(), fieldErrors },
    { status: 400 },
  )
}

function notFound() {
  return HttpResponse.json(
    { error: 'Konto nicht gefunden', status: 404, timestamp: new Date().toISOString() },
    { status: 404 },
  )
}

function find(id: string): LocalUserResponse | undefined {
  return mockLocalUsers.find((user) => user.id === id)
}

function replace(updated: LocalUserResponse) {
  setMockLocalUsers(mockLocalUsers.map((user) => (user.id === updated.id ? updated : user)))
}

function compare(a: LocalUserResponse, b: LocalUserResponse, key: SortKey): number {
  if (key === 'expiresAt' || key === 'createdAt') {
    // An account without an expiry date sorts last, the way a NULLS LAST order does.
    const left = a[key] ?? ''
    const right = b[key] ?? ''
    if (left === right) return 0
    if (left === '') return 1
    if (right === '') return -1
    return left < right ? -1 : 1
  }
  return a[key].localeCompare(b[key], 'de-DE')
}

/** Whether the mail of an invitation or reset goes out in the mock. */
function mailGoesOut(email: string): boolean {
  return (
    mockLocalAuthSettings.publicBaseUrlConfigured &&
    !email.toLowerCase().endsWith(MAIL_FAILING_ADDRESS_SUFFIX)
  )
}

function linkFor(email: string): string {
  const token = `mock-token-${encodeURIComponent(email)}`
  return mockLocalAuthSettings.publicBaseUrlConfigured
    ? `https://opaa.stadt.example/konto/passwort?token=${token}`
    : `/konto/passwort?token=${token}`
}

function expiryFrom(request: {
  expiresAt?: string | null
  noExpiry?: boolean
}): string | undefined {
  if (request.noExpiry) return undefined
  if (request.expiresAt) return request.expiresAt
  const date = new Date()
  date.setDate(date.getDate() + mockLocalAuthSettings.defaultExpiryDays)
  return date.toISOString()
}

export const localUserHandlers = [
  http.get('/api/v1/admin/local-users', ({ request }) => {
    const url = new URL(request.url)
    const size = Number(url.searchParams.get('size') ?? '25')
    if (size > 50) {
      return badRequest('Die Seitengröße darf höchstens 50 betragen')
    }
    const page = Number(url.searchParams.get('page') ?? '0')
    const query = (url.searchParams.get('query') ?? '').toLowerCase()
    const status = url.searchParams.get('status')
    const role = url.searchParams.get('role')
    const withoutExpiry = url.searchParams.get('withoutExpiry') === 'true'
    const inactive = url.searchParams.get('inactive') === 'true'
    const sortParam = url.searchParams.get('sort') ?? 'displayName'
    if (!SORT_KEYS.includes(sortParam as SortKey)) {
      return badRequest(`Nach „${sortParam}" kann nicht sortiert werden`)
    }
    const sort = sortParam as SortKey
    const direction = url.searchParams.get('direction') === 'desc' ? -1 : 1

    const matching = mockLocalUsers
      .filter(
        (user) =>
          (query === '' ||
            user.email.toLowerCase().includes(query) ||
            user.displayName.toLowerCase().includes(query)) &&
          (!status || user.status === status) &&
          (!role || user.systemRole === role) &&
          (!withoutExpiry || !user.expiresAt) &&
          (!inactive || user.activity === 'NEVER' || user.activity === 'INACTIVE_90_DAYS'),
      )
      .sort((a, b) => compare(a, b, sort) * direction)

    return HttpResponse.json({
      items: matching.slice(page * size, page * size + size),
      total: matching.length,
      page,
      size,
    })
  }),

  http.get('/api/v1/admin/local-users/summary', () => {
    return HttpResponse.json({
      total: mockLocalUsers.length,
      withoutExpiry: mockLocalUsers.filter((user) => !user.expiresAt).length,
      locked: mockLocalUsers.filter((user) => user.status === 'LOCKED').length,
      invitedPending: mockLocalUsers.filter((user) => user.status === 'INVITED').length,
      lastReviewHint: 'Die nächste Wiedervorlage geht am 01.10.2026 an die Systemverwaltung.',
    })
  }),

  http.post('/api/v1/admin/local-users', async ({ request }) => {
    const body = (await request.json()) as LocalUserCreateRequest
    const email = body.email.trim()
    if (!body.createdReason?.trim()) {
      return badRequest('Der Anlagegrund ist erforderlich', [
        { field: 'createdReason', code: 'REQUIRED', message: 'Der Anlagegrund ist erforderlich' },
      ])
    }
    if (!email.includes('@')) {
      return badRequest('Die E-Mail-Adresse ist nicht gültig', [
        { field: 'email', code: 'INVALID_ADDRESS', message: 'Die E-Mail-Adresse ist nicht gültig' },
      ])
    }
    if (mockLocalUsers.some((user) => user.email.toLowerCase() === email.toLowerCase())) {
      return conflict('EMAIL_TAKEN', 'Unter dieser Adresse existiert bereits ein lokales Konto')
    }

    const created: LocalUserResponse = {
      id: `local-user-${mockLocalUsers.length + 1}`,
      email,
      displayName: body.displayName.trim(),
      systemRole: body.systemRole ?? 'USER',
      status: body.mode === 'INVITE' ? 'INVITED' : 'ACTIVE',
      passwordChangeRequired: body.mode === 'INITIAL_PASSWORD',
      ...(body.mode === 'INITIAL_PASSWORD' ? { passwordChangeReason: 'INITIAL' as const } : {}),
      ...(expiryFrom(body) ? { expiresAt: expiryFrom(body) } : {}),
      createdReason: body.createdReason.trim(),
      createdAt: new Date().toISOString(),
      activity: 'NEVER',
      bootstrap: false,
    }
    setMockLocalUsers([...mockLocalUsers, created])

    if (body.mode === 'INITIAL_PASSWORD') {
      return HttpResponse.json(
        {
          user: created,
          mode: 'INITIAL_PASSWORD',
          emailSent: false,
          initialPassword: 'Mock-Anfangs-Passwort-7Q2',
        },
        { status: 201 },
      )
    }
    const sent = mailGoesOut(email)
    return HttpResponse.json(
      {
        user: created,
        mode: 'INVITE',
        emailSent: sent,
        deliveryPath: sent
          ? 'MAIL_SENT'
          : mockLocalAuthSettings.publicBaseUrlConfigured
            ? 'MAIL_FAILED'
            : 'LINK_DISPLAYED',
        ...(sent ? {} : { setupUrl: linkFor(email) }),
      },
      { status: 201 },
    )
  }),

  http.get('/api/v1/admin/local-users/:id', ({ params }) => {
    const user = find(String(params.id))
    return user ? HttpResponse.json(user) : notFound()
  }),

  http.patch('/api/v1/admin/local-users/:id', async ({ params, request }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    const body = (await request.json()) as LocalUserUpdateRequest
    if (
      body.email &&
      mockLocalUsers.some(
        (other) => other.id !== user.id && other.email.toLowerCase() === body.email!.toLowerCase(),
      )
    ) {
      return conflict('EMAIL_TAKEN', 'Unter dieser Adresse existiert bereits ein lokales Konto')
    }
    if (user.id === LAST_ADMIN_USER_ID && body.systemRole && body.systemRole !== user.systemRole) {
      return conflict(
        'LAST_LOGIN_CAPABLE_ADMIN',
        'Es bliebe kein anmeldefähiger Systemverwalter übrig',
      )
    }
    const updated: LocalUserResponse = {
      ...user,
      email: body.email?.trim() ?? user.email,
      displayName: body.displayName?.trim() ?? user.displayName,
      systemRole: body.systemRole ?? user.systemRole,
      createdReason: body.createdReason?.trim() ?? user.createdReason,
    }
    // noExpiry is the only way to remove a date; an absent expiresAt means „unchanged".
    if (body.noExpiry) delete updated.expiresAt
    else if (body.expiresAt) updated.expiresAt = body.expiresAt
    replace(updated)
    return HttpResponse.json(updated)
  }),

  http.delete('/api/v1/admin/local-users/:id', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    if (user.id === MOCK_SELF_USER_ID) {
      return conflict('SELF_DELETE', 'Das eigene Konto kann nicht gelöscht werden')
    }
    if (user.bootstrap) {
      return conflict('BOOTSTRAP_ACCOUNT', 'Das Notanker-Konto kann nicht gelöscht werden')
    }
    if (user.id === OWNS_CONTENT_USER_ID) {
      return conflict(
        'ACCOUNT_OWNS_CONTENT',
        'Das Konto wird noch in Nachweis- oder Rechtebeständen referenziert; sperren Sie es stattdessen',
      )
    }
    setMockLocalUsers(mockLocalUsers.filter((other) => other.id !== user.id))
    return new HttpResponse(null, { status: 204 })
  }),

  http.post('/api/v1/admin/local-users/:id/lock', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    if (user.id === MOCK_SELF_USER_ID) {
      return conflict('SELF_LOCKOUT', 'Das eigene Konto kann nicht gesperrt werden')
    }
    if (user.status === 'LOCKED') {
      return conflict('ALREADY_LOCKED', 'Das Konto ist bereits gesperrt')
    }
    if (user.id === LAST_ADMIN_USER_ID) {
      return conflict(
        'LAST_LOGIN_CAPABLE_ADMIN',
        'Es bliebe kein anmeldefähiger Systemverwalter übrig',
      )
    }
    const updated: LocalUserResponse = { ...user, status: 'LOCKED', lockedReason: 'ADMIN' }
    replace(updated)
    return HttpResponse.json(updated)
  }),

  http.post('/api/v1/admin/local-users/:id/unlock', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    if (user.status !== 'LOCKED') {
      return conflict('NOT_LOCKED', 'Das Konto ist nicht gesperrt')
    }
    const updated: LocalUserResponse = { ...user, status: 'ACTIVE' }
    delete updated.lockedReason
    replace(updated)
    return HttpResponse.json(updated)
  }),

  http.post('/api/v1/admin/local-users/:id/password-reset', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    const sent = mailGoesOut(user.email)
    return HttpResponse.json({
      emailSent: sent,
      deliveryPath: sent
        ? 'MAIL_SENT'
        : mockLocalAuthSettings.publicBaseUrlConfigured
          ? 'MAIL_FAILED'
          : 'LINK_DISPLAYED',
      ...(sent ? {} : { setupUrl: linkFor(user.email) }),
    })
  }),

  http.post('/api/v1/admin/local-users/:id/password', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    replace({ ...user, passwordChangeRequired: true, passwordChangeReason: 'ADMIN_RESET' })
    return HttpResponse.json({ password: 'Mock-Ersatz-Passwort-4T8' })
  }),

  http.get('/api/v1/admin/local-auth-settings', () => {
    return HttpResponse.json(mockLocalAuthSettings)
  }),

  http.put('/api/v1/admin/local-auth-settings', async ({ request }) => {
    const body = (await request.json()) as LocalAuthSettingsUpdateRequest
    const previous = mockLocalAuthSettings
    const turningOnALinkFlow =
      (body.passwordResetEnabled && !previous.passwordResetEnabled) ||
      (body.selfRegistrationEnabled && !previous.selfRegistrationEnabled)
    if (turningOnALinkFlow && !previous.publicBaseUrlConfigured) {
      return conflict(
        'PUBLIC_BASE_URL_REQUIRED',
        'Ohne OPAA_PUBLIC_BASE_URL gibt es keinen Link, den jemand sehen könnte',
      )
    }
    if (body.selfRegistrationEnabled && body.selfRegistrationAllowedDomains.length === 0) {
      return badRequest('Die Selbstregistrierung braucht mindestens eine Adress-Domäne', [
        {
          field: 'selfRegistrationAllowedDomains',
          code: 'REQUIRED',
          message: 'Die Selbstregistrierung braucht mindestens eine Adress-Domäne',
        },
      ])
    }
    if (body.passwordMinLength < 8 || body.passwordMinLength > 64) {
      return badRequest('Die Mindestlänge liegt zwischen 8 und 64 Zeichen', [
        {
          field: 'passwordMinLength',
          code: 'OUT_OF_RANGE',
          message: 'Die Mindestlänge liegt zwischen 8 und 64 Zeichen',
        },
      ])
    }

    const switchedOff = previous.enabled && !body.enabled
    setMockLocalAuthSettings({
      ...body,
      publicBaseUrlConfigured: previous.publicBaseUrlConfigured,
      ...(switchedOff
        ? {
            revokedSessions: mockLocalUsers.filter(
              (user) => user.systemRole !== 'SYSTEM_ADMIN' && user.status === 'ACTIVE',
            ).length,
          }
        : {}),
    })
    return HttpResponse.json(mockLocalAuthSettings)
  }),
]
