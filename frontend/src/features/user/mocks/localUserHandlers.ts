import { http, HttpResponse } from 'msw'
import type {
  LocalAuthSettingsUpdateRequest,
  LocalUserChatCleanupHoldRequest,
  LocalUserChatCleanupResponse,
  LocalUserCreateRequest,
  LocalUserResponse,
  LocalUserUpdateRequest,
} from '@/shared/api/types'
import {
  countsAsInactive,
  countsAsWithoutExpiry,
  LAST_ADMIN_USER_ID,
  MAIL_FAILING_ADDRESS_SUFFIX,
  MOCK_CHAT_DELETION_AT,
  MOCK_SELF_USER_ID,
  OWNS_CONTENT_USER_ID,
  MOCK_CHAT_CLEANUP_HOLD_MAX_DAYS,
  mockChatCleanupHolds,
  mockChatCleanupRequests,
  mockLocalAuthSettings,
  mockLocalUsers,
  setMockLocalAuthSettings,
  settingsAfterUpdate,
  setMockLocalUsers,
} from './localUserFixtures'

/**
 * The admin API of the local accounts as MSW handlers – with exactly the invariants the
 * interface relies on:
 *
 * - the list holds only local accounts, has no sorting by activity and rejects a
 *   page size above 50 with 400,
 * - invitation and reset return the link **exactly once** and only when the mail did not
 *   go out (addresses on `@kaputt.example`),
 * - the conflict codes occur where they occur in the backend as well (own account,
 *   emergency account, last login-capable system administrator, owning accounts),
 * - switching on „Passwort vergessen"/self-registration without a public base URL is
 *   409, with an empty domain list 400.
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

function handoverLinkFor(email: string): string {
  const token = `mock-handover-${encodeURIComponent(email)}`
  return mockLocalAuthSettings.publicBaseUrlConfigured
    ? `https://opaa.stadt.example/handover?token=${token}`
    : `/handover?token=${token}`
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

/**
 * The five numeric bounds from ADR-0033 in the order in which the backend checks them. `NaN` from
 * an empty input field fails every comparison and is thus a 400 as well - exactly the
 * behaviour a switch click must not trigger.
 */
function firstOutOfRange(
  body: LocalAuthSettingsUpdateRequest,
): { field: string; message: string } | null {
  const bounds: Array<[keyof LocalAuthSettingsUpdateRequest, number, number, string]> = [
    ['passwordMinLength', 8, 64, 'Die Mindestlänge liegt zwischen 8 und 64 Zeichen'],
    ['invitationTokenTtlHours', 1, 720, 'Der Einladungslink gilt 1 bis 720 Stunden'],
    ['resetTokenTtlMinutes', 1, 1440, 'Der Rücksetzlink gilt 1 bis 1440 Minuten'],
    [
      'defaultExpiryDays',
      1,
      Number.MAX_SAFE_INTEGER,
      'Das vorbelegte Ablaufdatum braucht mindestens 1 Tag',
    ],
    [
      'inactiveDays',
      30,
      Number.MAX_SAFE_INTEGER,
      'Die Inaktivitätsfrist beträgt mindestens 30 Tage',
    ],
  ]
  for (const [field, min, max, message] of bounds) {
    const value = body[field]
    if (typeof value !== 'number' || !Number.isFinite(value) || value < min || value > max) {
      return { field, message }
    }
  }
  return lockoutOutOfRange(body)
}

/** A changed lockout value lies within the limits of the operation; an unchanged one may not. */
function lockoutOutOfRange(
  body: LocalAuthSettingsUpdateRequest,
): { field: string; message: string } | null {
  const previous = mockLocalAuthSettings
  const attempts = body.lockoutMaxAttempts ?? null
  const limit = previous.lockoutMaxAttemptsLimit
  if (
    attempts !== null &&
    attempts !== (previous.storedLockoutMaxAttempts ?? null) &&
    !(Number.isInteger(attempts) && attempts >= 1 && attempts <= limit)
  ) {
    return { field: 'lockoutMaxAttempts', message: `Der Wert muss zwischen 1 und ${limit} liegen.` }
  }
  const minutes = body.lockoutDurationMinutes ?? null
  const min = previous.minLockoutDurationMinutes
  const max = previous.maxLockoutDurationMinutes
  if (
    minutes !== null &&
    minutes !== (previous.storedLockoutDurationMinutes ?? null) &&
    !(Number.isInteger(minutes) && minutes >= min && minutes <= max)
  ) {
    return {
      field: 'lockoutDurationMinutes',
      message: `Der Wert muss zwischen ${min} und ${max} liegen.`,
    }
  }
  return null
}

/** Only a lock by the administration schedules the deletion; chats play no part in the date. */
function chatCleanupOf(user: LocalUserResponse): LocalUserChatCleanupResponse {
  const hold = mockChatCleanupHolds.get(user.id) ?? null
  const request = mockChatCleanupRequests.get(user.id)
  const held = hold !== null
  const base = {
    held,
    deleteAfterDays: 365,
    holdMaxDays: MOCK_CHAT_CLEANUP_HOLD_MAX_DAYS,
    hold,
    pendingRequest: request
      ? {
          purpose: request.purpose,
          reason: request.reason,
          requestedAt: request.requestedAt,
          requestedByMe: request.byMe,
          extension: held,
        }
      : null,
  }
  if (held) return { ...base, status: 'HELD' }
  if (user.status === 'LOCKED' && user.lockedReason === 'ADMIN') {
    return { ...base, status: 'SCHEDULED', earliestDeletionAt: MOCK_CHAT_DELETION_AT }
  }
  return { ...base, status: 'NOT_LOCKED' }
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
          (!withoutExpiry || countsAsWithoutExpiry(user)) &&
          (!inactive || countsAsInactive(user)),
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
      withoutExpiry: mockLocalUsers.filter(countsAsWithoutExpiry).length,
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

    const expiresAt = expiryFrom(body)
    const created: LocalUserResponse = {
      id: `local-user-${mockLocalUsers.length + 1}`,
      email,
      displayName: body.displayName.trim(),
      systemRole: body.systemRole ?? 'USER',
      status: body.mode === 'INVITE' ? 'INVITED' : 'ACTIVE',
      passwordChangeRequired: body.mode === 'INITIAL_PASSWORD',
      ...(body.mode === 'INITIAL_PASSWORD' ? { passwordChangeReason: 'INITIAL' as const } : {}),
      ...(expiresAt ? { expiresAt } : {}),
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
    if (user.bootstrap && !body.noExpiry && body.expiresAt) {
      return conflict(
        'BOOTSTRAP_ACCOUNT',
        'Das Notanker-Konto der Systemverwaltung kann nicht befristet werden.',
      )
    }
    if (
      user.bootstrap &&
      body.systemRole &&
      body.systemRole !== 'SYSTEM_ADMIN' &&
      body.systemRole !== user.systemRole
    ) {
      return conflict(
        'BOOTSTRAP_ACCOUNT',
        'Die Rolle des Notanker-Kontos der Systemverwaltung kann nicht geändert werden.',
      )
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
    if (user.bootstrap) {
      return conflict(
        'BOOTSTRAP_ACCOUNT',
        'Das Notanker-Konto der Systemverwaltung kann nicht gesperrt werden.',
      )
    }
    if (user.status === 'LOCKED' && user.lockedReason === 'ADMIN') {
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

  http.get('/api/v1/admin/local-users/:id/chat-cleanup', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    return HttpResponse.json(chatCleanupOf(user))
  }),

  http.post(
    '/api/v1/admin/local-users/:id/chat-cleanup/hold-request',
    async ({ params, request }) => {
      const user = find(String(params.id))
      if (!user) return notFound()
      if (mockChatCleanupRequests.has(user.id)) {
        return HttpResponse.json(
          { message: 'Für dieses Konto liegt bereits ein Antrag auf Anhaltung der Löschung vor' },
          { status: 409 },
        )
      }
      const body = (await request.json()) as LocalUserChatCleanupHoldRequest
      mockChatCleanupRequests.set(user.id, {
        purpose: body.purpose,
        reason: body.reason,
        requestedAt: new Date().toISOString(),
        byMe: true,
      })
      return HttpResponse.json(chatCleanupOf(user))
    },
  ),

  http.post('/api/v1/admin/local-users/:id/chat-cleanup/hold-request/approve', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    const pending = mockChatCleanupRequests.get(user.id)
    if (!pending) return notFound()
    if (pending.byMe) {
      return HttpResponse.json({ message: 'Selbstfreigabe ist nicht zulässig' }, { status: 400 })
    }
    mockChatCleanupRequests.delete(user.id)
    const now = new Date()
    const until = new Date(now.getTime() + MOCK_CHAT_CLEANUP_HOLD_MAX_DAYS * 86_400_000)
    mockChatCleanupHolds.set(user.id, {
      purpose: pending.purpose,
      reason: pending.reason,
      heldSince: mockChatCleanupHolds.get(user.id)?.heldSince ?? now.toISOString(),
      heldUntil: until.toISOString(),
    })
    return HttpResponse.json(chatCleanupOf(user))
  }),

  http.delete('/api/v1/admin/local-users/:id/chat-cleanup/hold-request', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    if (!mockChatCleanupRequests.delete(user.id)) return notFound()
    return HttpResponse.json(chatCleanupOf(user))
  }),

  http.delete('/api/v1/admin/local-users/:id/chat-cleanup/hold', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    mockChatCleanupHolds.delete(user.id)
    return HttpResponse.json(chatCleanupOf(user))
  }),

  http.post('/api/v1/admin/local-users/:id/password-reset', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    if (user.bootstrap) {
      return conflict(
        'BOOTSTRAP_ACCOUNT',
        'Für das Notanker-Konto der Systemverwaltung kann kein Link zum Zurücksetzen verschickt werden.',
      )
    }
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

  // The initiation names provider and reason and never an identity; the emergency account is
  // excluded, and the link appears - as with the invitation - only when no mail went out.
  http.post('/api/v1/admin/local-users/:id/handover', async ({ params, request }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    const body = (await request.json()) as { providerId?: string; reason?: string }
    if (user.bootstrap) {
      return conflict(
        'BOOTSTRAP_ACCOUNT',
        'Das Notanker-Konto der Systemverwaltung kann nicht übergeben werden.',
      )
    }
    if (user.id === LAST_ADMIN_USER_ID) {
      return conflict(
        'LAST_LOGIN_CAPABLE_ADMIN',
        'Der letzte anmeldefähige Systemverwalter kann nicht entfernt werden.',
      )
    }
    if (!body.providerId) {
      return badRequest('Wählen Sie einen aktivierten Identitätsanbieter.', [
        {
          field: 'providerId',
          code: 'NOT_ELIGIBLE',
          message: 'Wählen Sie einen aktivierten Identitätsanbieter.',
        },
      ])
    }
    if (!body.reason || body.reason.trim().length === 0) {
      return badRequest('Bitte füllen Sie das Feld aus.', [
        { field: 'reason', code: 'REQUIRED', message: 'Das Feld darf nicht leer sein.' },
      ])
    }
    const sent = mailGoesOut(user.email)
    return HttpResponse.json({
      emailSent: sent,
      deliveryPath: sent
        ? 'MAIL_SENT'
        : mockLocalAuthSettings.publicBaseUrlConfigured
          ? 'MAIL_FAILED'
          : 'LINK_DISPLAYED',
      ...(sent ? {} : { handoverUrl: handoverLinkFor(user.email) }),
    })
  }),

  http.post('/api/v1/admin/local-users/:id/password', ({ params }) => {
    const user = find(String(params.id))
    if (!user) return notFound()
    if (user.bootstrap) {
      return conflict(
        'BOOTSTRAP_ACCOUNT',
        'Für das Notanker-Konto der Systemverwaltung kann kein neues Passwort erzeugt werden.',
      )
    }
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
    const outOfRange = firstOutOfRange(body)
    if (outOfRange) {
      return badRequest(outOfRange.message, [
        { field: outOfRange.field, code: 'OUT_OF_RANGE', message: outOfRange.message },
      ])
    }

    const switchedOff = previous.enabled && !body.enabled
    // `revokedSessions` belongs only in the response of the switching-off PUT, not permanently in
    // the settings: a GET afterwards would otherwise carry a number the backend never returns.
    setMockLocalAuthSettings(settingsAfterUpdate(previous, body))
    return HttpResponse.json(
      switchedOff
        ? {
            ...mockLocalAuthSettings,
            revokedSessions: mockLocalUsers.filter(
              (user) => user.systemRole !== 'SYSTEM_ADMIN' && user.status === 'ACTIVE',
            ).length,
          }
        : mockLocalAuthSettings,
    )
  }),
]
