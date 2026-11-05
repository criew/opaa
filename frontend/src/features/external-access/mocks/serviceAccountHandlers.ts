import { http, HttpResponse } from 'msw'
import type {
  LibraryServiceAccountConsentResponse,
  ServiceAccountConsentedLibraryResponse,
  ServiceAccountRequest,
  ServiceAccountResponse,
  ServiceAccountTokenResponse,
} from '@/shared/api/types'
import {
  currentMockScenario,
  generate,
  scenarioErrorBody,
  scenarioLatency,
  scenarioName,
  type MockScenario,
} from '@/shared/mocks/mockScenario'
import { mockUsers } from '../../principal/mocks/userFixtures'
import { mockLibraryDetails } from '../../library/mocks/libraryFixtures'

/**
 * Service accounts (epic #3587) as MSW handlers with one fixture set per mock
 * scenario. The state is per scenario and lives until the page reloads; the derived figures
 * (active tokens, consented libraries, next expiry) follow every write like in the backend.
 */

const DAY = 86_400_000
const MAX_CONSENT_DAYS = 365
const CHANNEL_RATE_LIMIT = 60

interface StoredAccount {
  id: string
  name: string
  description: string | null
  active: boolean
  deactivatedAt: string | null
  responsibleUserId: string | null
  responsibleDisplayName: string | null
  responsiblePersonMissing: boolean
  rateLimitPerHour: number | null
  createdAt: string
  tokens: ServiceAccountTokenResponse[]
  /** libraryId → consent */
  consents: Map<string, { libraryName: string; grantedAt: string; expiresAt: string }>
}

interface ScenarioState {
  accounts: StoredAccount[]
}

const states = new Map<MockScenario, ScenarioState>()
let sequence = 0

/** Back to the fixtures of every scenario; the tests call it after each case. */
export function resetServiceAccountMockState() {
  states.clear()
}

function inDays(days: number): string {
  return new Date(Date.now() + days * DAY).toISOString()
}

function libraryNames(): Array<{ id: string; name: string }> {
  return Object.values(mockLibraryDetails).map((library) => ({
    id: library.id,
    name: library.name,
  }))
}

function token(name: string, createdDays: number, expiresDays: number, status?: string) {
  sequence += 1
  return {
    id: `sa-token-${sequence}`,
    name,
    prefix: `opaa_pat_${String(sequence).padStart(4, '0')}`,
    createdAt: inDays(createdDays),
    expiresAt: inDays(expiresDays),
    status: (status ??
      (expiresDays < 0 ? 'EXPIRED' : 'ACTIVE')) as ServiceAccountTokenResponse['status'],
  }
}

function account(
  partial: Partial<StoredAccount> & { id: string; name: string },
  consents: Array<[number | string, number]> = [],
): StoredAccount {
  const libraries = libraryNames()
  const map = new Map<string, { libraryName: string; grantedAt: string; expiresAt: string }>()
  consents.forEach(([ref, expiresDays]) => {
    const library =
      typeof ref === 'string'
        ? (libraries.find((candidate) => candidate.id === ref) ?? { id: ref, name: ref })
        : libraries[ref % libraries.length]
    map.set(library.id, {
      libraryName: library.name,
      grantedAt: inDays(-30),
      expiresAt: inDays(expiresDays),
    })
  })
  const responsible = mockUsers[1]
  return {
    description: null,
    active: true,
    deactivatedAt: null,
    responsibleUserId: responsible.id,
    responsibleDisplayName: responsible.displayName ?? null,
    responsiblePersonMissing: false,
    rateLimitPerHour: null,
    createdAt: inDays(-120),
    tokens: [],
    consents: map,
    ...partial,
  }
}

function person(index: number) {
  const user = mockUsers[index % mockUsers.length]
  return { responsibleUserId: user.id, responsibleDisplayName: user.displayName ?? null }
}

const MISSING = {
  responsibleUserId: null,
  responsibleDisplayName: null,
  responsiblePersonMissing: true,
}

function defaultAccounts(): StoredAccount[] {
  return [
    account(
      {
        id: 'sa-bauantrag',
        name: 'Bauantrag-Portal',
        description: 'Suche in Bauordnung und Satzungen für das Antragsportal der Bauaufsicht.',
        ...person(1),
        rateLimitPerHour: 600,
        tokens: [token('Produktion', -80, 10), token('Test', -20, 70)],
      },
      [
        ['library-filesystem-satzungen', 200],
        ['library-nextcloud-projekte', 12],
      ],
    ),
    account(
      {
        id: 'sa-wohngeld',
        name: 'Wohngeld-Online',
        description: 'Auskunft zu Dienstanweisungen im Wohngeldverfahren.',
        ...MISSING,
        tokens: [token('Produktion', -40, 50)],
      },
      [
        ['library-dienstanweisungen', 90],
        ['library-nextcloud-projekte', 140],
      ],
    ),
    account(
      {
        id: 'sa-buergerservice',
        name: 'Bürgerservice-Assistent',
        description: null,
        ...person(3),
        tokens: [],
      },
      [],
    ),
    account(
      {
        id: 'sa-gewerbe',
        name: 'Gewerbeanmeldung (Altverfahren)',
        description: 'Abgelöst durch das Landesportal.',
        ...person(2),
        active: false,
        deactivatedAt: inDays(-15),
        tokens: [token('Produktion', -200, -15, 'REVOKED')],
      },
      [],
    ),
  ]
}

function smallAccounts(): StoredAccount[] {
  return [
    account(
      {
        id: 'sa-bauantrag',
        name: 'Bauantrag-Portal',
        description: 'Suche in Bauordnung und Satzungen für das Antragsportal der Bauaufsicht.',
        ...person(1),
        tokens: [token('Produktion', -10, 80)],
      },
      [['library-nextcloud-projekte', 180]],
    ),
  ]
}

function scaleAccounts(): StoredAccount[] {
  return generate(60, (i) =>
    account(
      {
        id: `sa-scale-${i}`,
        name: scenarioName('Fachverfahren', i),
        description:
          i % 4 === 0
            ? 'Anbindung des Landesportals für Leistungen nach dem Onlinezugangsgesetz mit eigener Suche.'
            : null,
        ...(i % 7 === 0 ? MISSING : person(i)),
        active: i % 11 !== 0,
        deactivatedAt: i % 11 === 0 ? inDays(-i) : null,
        rateLimitPerHour: i % 5 === 0 ? 1200 : null,
        tokens:
          i === 1
            ? generate(14, (t) =>
                token(
                  `Instanz ${t} ${t % 3 === 0 ? 'Rechenzentrum Süd' : ''}`.trim(),
                  -t * 5,
                  t * 6 - 10,
                ),
              )
            : generate(i % 4, (t) => token(`Token ${t}`, -t * 9, ((i * 7 + t * 13) % 88) + 2)),
      },
      i === 1
        ? generate(40, (l) => [l, ((l * 17) % 300) - 5] as [number, number])
        : generate(i % 3, (l) => [i + l, (((i + l) * 23) % 300) + 3] as [number, number]),
    ),
  )
}

function partialAccounts(): StoredAccount[] {
  return [
    account(
      {
        id: 'sa-bauantrag',
        name: 'Bauantrag-Portal',
        ...MISSING,
        tokens: [token('Produktion', -85, 3), token('Ersatz', -1, 89)],
      },
      [
        ['library-nextcloud-projekte', 2],
        ['library-filesystem-satzungen', -4],
      ],
    ),
    account(
      {
        id: 'sa-wohngeld',
        name: 'Wohngeld-Online',
        ...MISSING,
        tokens: [token('Produktion', -90, -1, 'EXPIRED')],
      },
      [['library-nextcloud-projekte', -10]],
    ),
    account(
      {
        id: 'sa-kita',
        name: 'Kita-Platzvergabe',
        ...person(2),
        tokens: [token('Produktion', -5, 60)],
      },
      [],
    ),
  ]
}

function initial(scenario: MockScenario): ScenarioState {
  switch (scenario) {
    case 'small':
      return { accounts: smallAccounts() }
    case 'scale':
      return { accounts: scaleAccounts() }
    case 'empty':
      return { accounts: [] }
    case 'partial':
      return { accounts: partialAccounts() }
    default:
      return { accounts: defaultAccounts() }
  }
}

function state(): ScenarioState {
  const scenario = currentMockScenario()
  let current = states.get(scenario)
  if (!current) {
    current = initial(scenario)
    states.set(scenario, current)
  }
  return current
}

function consentStatus(expiresAt: string): 'ACTIVE' | 'EXPIRED' {
  return new Date(expiresAt).getTime() > Date.now() ? 'ACTIVE' : 'EXPIRED'
}

function summary(stored: StoredAccount): ServiceAccountResponse {
  const activeTokens = stored.active ? stored.tokens.filter((t) => t.status === 'ACTIVE') : []
  const activeConsents = [...stored.consents.values()].filter(
    (c) => consentStatus(c.expiresAt) === 'ACTIVE',
  )
  const ends = [
    ...activeTokens.map((t) => t.expiresAt),
    ...(stored.active ? activeConsents.map((c) => c.expiresAt) : []),
  ].sort()
  return {
    id: stored.id,
    name: stored.name,
    description: stored.description,
    active: stored.active,
    deactivatedAt: stored.deactivatedAt,
    responsibleUserId: stored.responsibleUserId,
    responsibleDisplayName: stored.responsibleDisplayName,
    responsiblePersonMissing: stored.responsiblePersonMissing,
    rateLimitPerHour: stored.rateLimitPerHour,
    effectiveRateLimitPerHour: stored.rateLimitPerHour ?? CHANNEL_RATE_LIMIT,
    activeTokenCount: activeTokens.length,
    consentedLibraryCount: activeConsents.length,
    nextExpiryAt: ends[0] ?? null,
    createdAt: stored.createdAt,
  }
}

function libraries(stored: StoredAccount): ServiceAccountConsentedLibraryResponse[] {
  return [...stored.consents.entries()]
    .map(([libraryId, consent]) => ({
      libraryId,
      libraryName: consent.libraryName,
      grantedAt: consent.grantedAt,
      expiresAt: consent.expiresAt,
      status: consentStatus(consent.expiresAt),
    }))
    .sort((a, b) => a.libraryName.localeCompare(b.libraryName, 'de'))
}

function find(id: string): StoredAccount | undefined {
  return state().accounts.find((candidate) => candidate.id === id)
}

function notFound() {
  return HttpResponse.json(scenarioErrorBody(404, 'Dienstkonto nicht gefunden'), { status: 404 })
}

function validate(body: ServiceAccountRequest) {
  const fieldErrors: Record<string, string> = {}
  if (!body.name?.trim()) fieldErrors.name = 'Bitte einen Namen angeben.'
  if (!body.responsibleUserId) fieldErrors.responsibleUserId = 'Bitte eine Person auswählen.'
  if (
    body.rateLimitPerHour != null &&
    (body.rateLimitPerHour < 1 || body.rateLimitPerHour > 10000)
  ) {
    fieldErrors.rateLimitPerHour = 'Zwischen 1 und 10.000 Anfragen je Stunde.'
  }
  if (Object.keys(fieldErrors).length === 0) return null
  return HttpResponse.json(
    {
      ...scenarioErrorBody(400, 'Die Angaben sind unvollständig.'),
      fieldErrors: Object.entries(fieldErrors).map(([field, message]) => ({ field, message })),
    },
    { status: 400 },
  )
}

function responsibleOf(userId: string) {
  const user = mockUsers.find((candidate) => candidate.id === userId)
  return {
    responsibleUserId: userId,
    responsibleDisplayName: user?.displayName ?? user?.email ?? userId,
    responsiblePersonMissing: false,
  }
}

function consentOf(stored: StoredAccount, libraryId: string): LibraryServiceAccountConsentResponse {
  const consent = stored.consents.get(libraryId)!
  return {
    serviceAccountId: stored.id,
    serviceAccountName: stored.name,
    serviceAccountDescription: stored.description,
    serviceAccountActive: stored.active,
    grantedAt: consent.grantedAt,
    expiresAt: consent.expiresAt,
    status: consentStatus(consent.expiresAt),
  }
}

/** The library-side consents of the `scale` scenario: 25 accounts per library. */
function ensureScaleConsents(libraryId: string, name: string) {
  if (currentMockScenario() !== 'scale') return
  const accounts = state().accounts
  if (accounts.some((stored) => stored.consents.has(libraryId))) {
    const count = accounts.filter((stored) => stored.consents.has(libraryId)).length
    if (count >= 25) return
  }
  accounts
    .filter((stored) => stored.active)
    .slice(0, 25)
    .forEach((stored, index) => {
      if (!stored.consents.has(libraryId)) {
        stored.consents.set(libraryId, {
          libraryName: name,
          grantedAt: inDays(-index),
          expiresAt: inDays(((index * 37) % 360) - 8),
        })
      }
    })
}

export const serviceAccountHandlers = [
  http.get('/api/v1/admin/service-accounts', async () => {
    await scenarioLatency()
    if (currentMockScenario() === 'failed') {
      return HttpResponse.json(
        scenarioErrorBody(502, 'Die Dienstkonten konnten nicht geladen werden.'),
        { status: 502 },
      )
    }
    return HttpResponse.json({
      serviceAccounts: state()
        .accounts.map(summary)
        .sort((a, b) => Number(b.active) - Number(a.active) || a.name.localeCompare(b.name, 'de')),
    })
  }),

  http.post('/api/v1/admin/service-accounts', async ({ request }) => {
    const body = (await request.json()) as ServiceAccountRequest
    const invalid = validate(body)
    if (invalid) return invalid
    sequence += 1
    const stored = account({
      id: `sa-new-${sequence}`,
      name: body.name.trim(),
      description: body.description?.trim() || null,
      rateLimitPerHour: body.rateLimitPerHour ?? null,
      createdAt: new Date().toISOString(),
      ...responsibleOf(body.responsibleUserId),
    })
    state().accounts.push(stored)
    return HttpResponse.json(summary(stored), { status: 201 })
  }),

  http.get('/api/v1/admin/service-accounts/:id', async ({ params }) => {
    await scenarioLatency()
    const stored = find(String(params.id))
    if (!stored) return notFound()
    return HttpResponse.json({
      serviceAccount: summary(stored),
      tokens: [...stored.tokens].sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
      libraries: libraries(stored),
    })
  }),

  http.put('/api/v1/admin/service-accounts/:id', async ({ params, request }) => {
    const stored = find(String(params.id))
    if (!stored) return notFound()
    const body = (await request.json()) as ServiceAccountRequest
    const invalid = validate(body)
    if (invalid) return invalid
    Object.assign(stored, {
      name: body.name.trim(),
      description: body.description?.trim() || null,
      rateLimitPerHour: body.rateLimitPerHour ?? null,
      ...responsibleOf(body.responsibleUserId),
    })
    return HttpResponse.json(summary(stored))
  }),

  http.post('/api/v1/admin/service-accounts/:id/deactivate', ({ params }) => {
    const stored = find(String(params.id))
    if (!stored) return notFound()
    stored.active = false
    stored.deactivatedAt = new Date().toISOString()
    stored.tokens.forEach((t) => {
      if (t.status === 'ACTIVE') t.status = 'REVOKED'
    })
    return HttpResponse.json(summary(stored))
  }),

  http.get('/api/v1/admin/service-accounts/:id/tokens', ({ params }) => {
    const stored = find(String(params.id))
    if (!stored) return notFound()
    return HttpResponse.json({ tokens: stored.tokens })
  }),

  http.post('/api/v1/admin/service-accounts/:id/tokens', async ({ params, request }) => {
    const stored = find(String(params.id))
    if (!stored) return notFound()
    if (!stored.active) {
      return HttpResponse.json(
        scenarioErrorBody(409, 'Ein deaktiviertes Dienstkonto bekommt keine neuen Tokens.'),
        { status: 409 },
      )
    }
    const body = (await request.json()) as { name: string; expiresAt: string }
    const fieldErrors: Record<string, string> = {}
    if (!body.name?.trim()) fieldErrors.name = 'Bitte einen Namen angeben.'
    if (!body.expiresAt) fieldErrors.expiresAt = 'Bitte ein Ablaufdatum angeben.'
    if (Object.keys(fieldErrors).length > 0) {
      return HttpResponse.json(
        {
          ...scenarioErrorBody(400, 'Die Angaben sind unvollständig.'),
          fieldErrors: Object.entries(fieldErrors).map(([field, message]) => ({ field, message })),
        },
        { status: 400 },
      )
    }
    sequence += 1
    const created = {
      id: `sa-token-${sequence}`,
      name: body.name.trim(),
      prefix: `opaa_pat_${String(sequence).padStart(4, '0')}`,
      createdAt: new Date().toISOString(),
      expiresAt: body.expiresAt,
      status: 'ACTIVE' as const,
    }
    stored.tokens.unshift(created)
    return HttpResponse.json(
      { ...created, token: `${created.prefix}_Q7d2mX9vR4kL8pT1wZ6nB3cY5hJ0sF` },
      { status: 201 },
    )
  }),

  http.delete('/api/v1/admin/service-accounts/:id/tokens/:tokenId', ({ params }) => {
    const stored = find(String(params.id))
    const found = stored?.tokens.find((t) => t.id === params.tokenId)
    if (!found) return notFound()
    found.status = 'REVOKED'
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/libraries/:libraryId/service-accounts', async ({ params }) => {
    await scenarioLatency()
    const libraryId = String(params.libraryId)
    if (currentMockScenario() === 'failed') {
      return HttpResponse.json(
        scenarioErrorBody(502, 'Die Fachverfahren konnten nicht geladen werden.'),
        { status: 502 },
      )
    }
    ensureScaleConsents(libraryId, mockLibraryDetails[libraryId]?.name ?? libraryId)
    const consents = state()
      .accounts.filter((stored) => stored.consents.has(libraryId))
      .map((stored) => consentOf(stored, libraryId))
      .sort((a, b) => a.serviceAccountName.localeCompare(b.serviceAccountName, 'de'))
    return HttpResponse.json({ consents, maxConsentDays: MAX_CONSENT_DAYS })
  }),

  http.put(
    '/api/v1/libraries/:libraryId/service-accounts/:serviceAccountId',
    async ({ params, request }) => {
      const libraryId = String(params.libraryId)
      const stored = find(String(params.serviceAccountId))
      if (!stored) return notFound()
      if (!stored.active) {
        return HttpResponse.json(scenarioErrorBody(409, 'Das Dienstkonto ist deaktiviert.'), {
          status: 409,
        })
      }
      const body = (await request.json()) as { expiresAt?: string }
      if (!body.expiresAt) {
        return HttpResponse.json(
          {
            ...scenarioErrorBody(400, 'Bitte ein Ablaufdatum angeben.'),
            fieldErrors: [{ field: 'expiresAt', message: 'Bitte ein Ablaufdatum angeben.' }],
          },
          { status: 400 },
        )
      }
      const days = (new Date(body.expiresAt).getTime() - Date.now()) / DAY
      if (days > MAX_CONSENT_DAYS) {
        return HttpResponse.json(
          {
            ...scenarioErrorBody(400, `Höchstens ${MAX_CONSENT_DAYS} Tage.`),
            fieldErrors: [{ field: 'expiresAt', message: `Höchstens ${MAX_CONSENT_DAYS} Tage.` }],
          },
          { status: 400 },
        )
      }
      stored.consents.set(libraryId, {
        libraryName: mockLibraryDetails[libraryId]?.name ?? libraryId,
        grantedAt: new Date().toISOString(),
        expiresAt: body.expiresAt,
      })
      return HttpResponse.json(consentOf(stored, libraryId))
    },
  ),

  http.delete('/api/v1/libraries/:libraryId/service-accounts/:serviceAccountId', ({ params }) => {
    const stored = find(String(params.serviceAccountId))
    if (!stored) return notFound()
    stored.consents.delete(String(params.libraryId))
    return new HttpResponse(null, { status: 204 })
  }),

  http.get('/api/v1/libraries/:libraryId/service-account-choices', async ({ params }) => {
    await scenarioLatency()
    const libraryId = String(params.libraryId)
    return HttpResponse.json({
      serviceAccounts: state()
        .accounts.filter((stored) => stored.active && !stored.consents.has(libraryId))
        .map((stored) => ({ id: stored.id, name: stored.name, description: stored.description }))
        .sort((a, b) => a.name.localeCompare(b.name, 'de')),
    })
  }),
]
