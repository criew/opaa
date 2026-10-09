import { http, HttpResponse } from 'msw'
import type {
  ConnectionProfileCreateRequest,
  ConnectionProfileOption,
  ConnectionProfileRequestCreateRequest,
  ConnectionProfileRequestResolveRequest,
  ConnectionProfileRequestResponse,
  ConnectionProfileResponse,
  ConnectionProfileSupport,
  ConnectionProfileUpdateRequest,
  ConnectorLockRequest,
  ConnectorProfileRequirementRequest,
  ConnectorProfileRequirementResponse,
  ConnectorTypeStateResponse,
  OwnAddressStock,
} from '../types/api'
import {
  mockConnectionProfileRequests,
  mockConnectionProfiles,
  resetMockConnectionProfileRequests,
  resetMockConnectionProfiles,
} from './connectionProfileFixtures'
import { mockOwnAccountProfileIds } from './connectedAccountHandlers'

let lockedTypes = new Set<string>()
// The types switched to "Nur über Zugänge", with the choice for their own-address libraries.
let requiredTypes = new Map<string, OwnAddressStock>()

export function resetConnectionProfileMockState() {
  resetMockConnectionProfiles()
  resetMockConnectionProfileRequests()
  lockedTypes = new Set<string>()
  requiredTypes = new Map<string, OwnAddressStock>()
}

interface MockConnectorType {
  sourceType: string
  displayName: string
  profileSupport: ConnectionProfileSupport
}

// As the delivered connectors declare it: every remote one admits profiles.
const CONNECTOR_TYPES: MockConnectorType[] = [
  { sourceType: 'CONFLUENCE', displayName: 'Confluence', profileSupport: 'OPTIONAL' },
  { sourceType: 'FILESYSTEM', displayName: 'Dateisystem', profileSupport: 'FORBIDDEN' },
  { sourceType: 'GOOGLE_DRIVE', displayName: 'Google Drive', profileSupport: 'OPTIONAL' },
  { sourceType: 'HTTP_DIRECTORY', displayName: 'Webverzeichnis', profileSupport: 'OPTIONAL' },
  { sourceType: 'NEXTCLOUD', displayName: 'Nextcloud', profileSupport: 'OPTIONAL' },
  { sourceType: 'RSS_FEED', displayName: 'RSS-Feed', profileSupport: 'OPTIONAL' },
  { sourceType: 'S3', displayName: 'S3-Objektspeicher', profileSupport: 'OPTIONAL' },
  { sourceType: 'SMB', displayName: 'Windows-Dateifreigabe (SMB)', profileSupport: 'OPTIONAL' },
]

function typeState(type: MockConnectorType): ConnectorTypeStateResponse {
  const locked = lockedTypes.has(type.sourceType)
  const stock = requiredTypes.get(type.sourceType) ?? null
  return {
    sourceType: type.sourceType,
    displayName: type.displayName,
    locked,
    lockedAt: locked ? new Date().toISOString() : null,
    profileSupport: type.profileSupport,
    profileRequired: type.profileSupport === 'REQUIRED' || stock !== null,
    profileRequiredAt: stock !== null ? '2026-10-04T09:00:00Z' : null,
    ownAddressStock: stock,
  }
}

/** Whether a profile of the type may carry new libraries: unlocked and admitting libraries. */
function usableProfileExists(sourceType: string) {
  return mockConnectionProfiles.some(
    (p) => p.sourceType === sourceType && !p.locked && p.ownership !== 'PERSON',
  )
}

function requirementOf(type: MockConnectorType): ConnectorProfileRequirementResponse {
  const state = typeState(type)
  const notSwitchableReason =
    type.profileSupport !== 'OPTIONAL'
      ? `Die Quellart „${type.displayName}“ legt selbst fest, ob sie Zugänge verlangt.`
      : !state.profileRequired && !usableProfileExists(type.sourceType)
        ? `Für die Quellart „${type.displayName}“ gibt es keinen nicht gesperrten Zugang, der Bibliotheken zulässt. Legen Sie zuerst einen an.`
        : null
  return {
    state,
    switchable: notSwitchableReason === null,
    notSwitchableReason,
    ownAddressLibraries:
      type.sourceType === 'NEXTCLOUD'
        ? [
            {
              id: 'library-nextcloud-eigen',
              name: 'Projektablage',
              ownerType: 'GROUP',
              ownerName: 'Referat 50',
            },
          ]
        : [],
    coverageNotice:
      type.sourceType === 'RSS_FEED'
        ? 'Die Pflicht legt nur die Feed-Adresse fest. Die Detailseiten eines Feeds stammen aus seinen Einträgen und können auf fremden Servern liegen; sie werden weiter abgerufen, aber ohne Zugangsdaten.'
        : null,
  }
}

const ADMIN = '/api/v1/admin/connection-profiles'

function notFound() {
  return HttpResponse.json({ error: 'Zugang nicht gefunden', status: 404 }, { status: 404 })
}

/** The libraries on a profile, as the impact counts them. */
function librariesOn(profile: ConnectionProfileResponse) {
  return Math.ceil(profile.connectionCount / 2)
}

/**
 * The connector's refusals of a proposed change: in the mocks, a proxy on an `.invalid` host is
 * out of reach for every library on the profile.
 */
function refusalsOf(profile: ConnectionProfileResponse, body: ConnectionProfileUpdateRequest) {
  if (!body.sourceProxy?.split(':')[0].endsWith('.invalid')) return []
  return Array.from({ length: librariesOn(profile) }, (_, index) => ({
    libraryId: `library-on-${profile.id}-${index + 1}`,
    category: 'CONNECTION' as const,
    message: `Der Proxy ${body.sourceProxy} ist nicht erreichbar.`,
  }))
}

function count(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`
}

/**
 * What saving `body` discards, as the backend plans it: in the mocks a new address or registration
 * discards every secret of the profile - one stored secret per library - and ends the accounts of
 * persons, as does an ownership without persons; address, proxy, TLS switch and defaults change
 * every library's configuration. The confirmation is the 409's text.
 */
function discardsOf(profile: ConnectionProfileResponse, body: ConnectionProfileUpdateRequest) {
  const all =
    profile.serverUrl !== body.serverUrl.replace(/\/+$/, '') ||
    profile.authMethod !== body.authMethod ||
    (profile.clientId ?? null) !== (body.clientId ?? null) ||
    (profile.authorizationEndpoint ?? null) !== (body.authorizationEndpoint ?? null) ||
    (profile.tokenEndpoint ?? null) !== (body.tokenEndpoint ?? null) ||
    (profile.revocationEndpoint ?? null) !== (body.revocationEndpoint ?? null)
  const reconfigured =
    profile.serverUrl !== body.serverUrl.replace(/\/+$/, '') ||
    (profile.sourceProxy ?? null) !== (body.sourceProxy ?? null) ||
    profile.sourceInsecureSsl !== Boolean(body.sourceInsecureSsl) ||
    JSON.stringify(profile.connectorSettings ?? null) !==
      JSON.stringify(body.connectorSettings ?? null)
  const forPersons = profile.ownership !== 'LIBRARY'
  const persons = forPersons && (all || body.ownership === 'LIBRARY')
  const libraries = librariesOn(profile)
  const connectionsDiscarded = all ? profile.connectionCount : 0
  const secretsDiscarded = all ? libraries : 0
  let confirmation: string | null = null
  if (connectionsDiscarded > 0) {
    confirmation =
      `Die Änderung verwirft alle Zugangsdaten und Token dieses Zugangs; ${count(connectionsDiscarded, 'Verbindung muss', 'Verbindungen müssen')} neu angemeldet werden` +
      (secretsDiscarded > 0
        ? `, die gespeicherten Zugangsdaten von ${count(secretsDiscarded, 'Bibliothek', 'Bibliotheken')} sind neu einzutragen`
        : '') +
      (persons ? ', etwaige verbundene Konten von Personen enden. ' : '. ') +
      'Bitte bestätigen.'
  } else if (persons) {
    confirmation =
      'Die Änderung verwirft die Zugangsdaten etwaiger verbundener Konten von Personen dieses Zugangs. Bitte bestätigen.'
  }
  return {
    connectionsDiscarded,
    secretsDiscarded,
    configurationsChanged: reconfigured ? libraries : 0,
    connectedAccountsEnded: persons ? profile.connectedAccountCount : null,
    confirmation,
  }
}

// Connection profiles (#2160). Like the backend, an answer never carries the client secret, and a
// change that discards anything needs confirmDiscard.
export const connectionProfileHandlers = [
  http.get(ADMIN, () => HttpResponse.json(mockConnectionProfiles)),

  // No connector of this installation lets a library consent at the provider itself yet.
  http.get('/api/v1/admin/source-connections/dormant', () => HttpResponse.json([])),

  http.get(`${ADMIN}/oauth-redirect`, () =>
    HttpResponse.json({ redirectUri: `${window.location.origin}/connections/callback` }),
  ),

  http.post(ADMIN, async ({ request }) => {
    const body = (await request.json()) as ConnectionProfileCreateRequest
    const now = new Date().toISOString()
    const created: ConnectionProfileResponse = {
      id: `connection-profile-${crypto.randomUUID().slice(0, 8)}`,
      name: body.name,
      sourceType: body.sourceType,
      serverUrl: body.serverUrl.replace(/\/+$/, ''),
      authMethod: body.authMethod,
      ownership: body.ownership,
      clientId: body.clientId ?? null,
      clientSecretSet: Boolean(body.clientSecret),
      clientSecretExpiresOn: body.clientSecretExpiresOn ?? null,
      clientSecretExpiresSoon: false,
      signInRejected: false,
      tenant: body.tenant ?? null,
      scopes: body.scopes ?? null,
      authorizationEndpoint: body.authorizationEndpoint ?? null,
      tokenEndpoint: body.tokenEndpoint ?? null,
      revocationEndpoint: body.revocationEndpoint ?? null,
      connectorSettings: body.connectorSettings ?? null,
      sourceProxy: body.sourceProxy ?? null,
      sourceInsecureSsl: body.sourceInsecureSsl ?? false,
      connectionCount: 0,
      connectedAccountCount: { count: null, fewerThan: 5 },
      expiredConnectionCount: { count: null, fewerThan: 5 },
      expiredConnectionWarning: false,
      locked: false,
      createdAt: now,
      updatedAt: now,
    }
    if (body.fulfillsRequestId) {
      const wish = mockConnectionProfileRequests.find((r) => r.id === body.fulfillsRequestId)
      if (!wish) return requestNotFound()
      if (wish.state !== 'OPEN') return requestNotOpen()
      Object.assign(wish, {
        state: 'DONE',
        resolvedAt: now,
        profile: { id: created.id, name: created.name },
      })
    }
    mockConnectionProfiles.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.put(`${ADMIN}/:profileId`, async ({ params, request }) => {
    const index = mockConnectionProfiles.findIndex((p) => p.id === params.profileId)
    if (index < 0) return notFound()
    const current = mockConnectionProfiles[index]
    const body = (await request.json()) as ConnectionProfileUpdateRequest
    const movedSecretEndpoint = (['tokenEndpoint', 'revocationEndpoint'] as const).some(
      (key) => body[key] != null && body[key] !== (current[key] ?? null),
    )
    if (current.clientSecretSet && body.clientSecret == null && movedSecretEndpoint) {
      return HttpResponse.json(
        {
          error:
            'Der Token-Endpunkt ändert sich. Das hinterlegte Client-Secret wird nicht an einen geänderten Endpunkt gesendet: Bitte geben Sie das Client-Secret für den neuen Endpunkt an, oder ein leeres für einen öffentlichen Client.',
          status: 400,
          code: 'CONNECTION_PROFILE_CLIENT_SECRET_REQUIRED',
        },
        { status: 400 },
      )
    }
    const { confirmation } = discardsOf(current, body)
    const refusals = refusalsOf(current, body)
    if (refusals.length > 0) {
      return HttpResponse.json(
        {
          error: `Der Konnektor lehnt die Änderung für ${refusals.length} Bibliotheken ab (Verbindung).`,
          status: 400,
          code: 'CONNECTION_PROFILE_CHANGE_REJECTED',
        },
        { status: 400 },
      )
    }
    if (confirmation && !body.confirmDiscard) {
      return HttpResponse.json(
        {
          error: confirmation,
          status: 409,
          code: 'CONNECTION_PROFILE_CONFIRMATION_REQUIRED',
        },
        { status: 409 },
      )
    }
    const updated: ConnectionProfileResponse = {
      ...current,
      name: body.name,
      serverUrl: body.serverUrl.replace(/\/+$/, ''),
      authMethod: body.authMethod,
      ownership: body.ownership,
      clientId: body.clientId ?? null,
      clientSecretSet:
        body.clientSecret == null ? current.clientSecretSet : body.clientSecret.trim() !== '',
      clientSecretExpiresOn: body.clientSecretExpiresOn ?? null,
      tenant: body.tenant ?? null,
      scopes: body.scopes ?? null,
      authorizationEndpoint: body.authorizationEndpoint ?? null,
      tokenEndpoint: body.tokenEndpoint ?? null,
      revocationEndpoint: body.revocationEndpoint ?? null,
      connectorSettings: body.connectorSettings ?? null,
      sourceProxy: body.sourceProxy ?? null,
      sourceInsecureSsl: body.sourceInsecureSsl ?? false,
      updatedAt: new Date().toISOString(),
    }
    mockConnectionProfiles[index] = updated
    return HttpResponse.json(updated)
  }),

  http.delete(`${ADMIN}/:profileId`, ({ params }) => {
    const index = mockConnectionProfiles.findIndex((p) => p.id === params.profileId)
    if (index < 0) return notFound()
    mockConnectionProfiles.splice(index, 1)
    return new HttpResponse(null, { status: 204 })
  }),

  http.get(`${ADMIN}/:profileId/impact`, ({ params }) => {
    const profile = mockConnectionProfiles.find((p) => p.id === params.profileId)
    if (!profile) return notFound()
    return HttpResponse.json({
      connections: profile.connectionCount,
      libraries: librariesOn(profile),
      connectedAccounts: profile.connectedAccountCount,
      rejectedLibraries: 0,
      rejections: [],
      lastForProfileRequirement: false,
    })
  }),

  // The preview of an update: the counts plus every library whose connector refuses it.
  http.post(`${ADMIN}/:profileId/impact`, async ({ params, request }) => {
    const profile = mockConnectionProfiles.find((p) => p.id === params.profileId)
    if (!profile) return notFound()
    const body = (await request.json()) as ConnectionProfileUpdateRequest
    const refusals = refusalsOf(profile, body)
    return HttpResponse.json({
      connections: profile.connectionCount,
      libraries: librariesOn(profile),
      connectedAccounts: profile.connectedAccountCount,
      ...discardsOf(profile, body),
      rejectedLibraries: refusals.length,
      rejections: refusals,
      // private libraries only as the masked count of their owners, on a profile admitting persons
      ...(profile.ownership === 'LIBRARY'
        ? {}
        : { rejectedPrivateLibraries: { count: null, fewerThan: 5 } }),
      lastForProfileRequirement: false,
    })
  }),

  http.post(`${ADMIN}/:profileId/disconnect-all`, ({ params }) => {
    const profile = mockConnectionProfiles.find((p) => p.id === params.profileId)
    if (!profile) return notFound()
    return HttpResponse.json({
      connections: profile.connectionCount,
      libraries: profile.connectionCount,
      connectedAccounts: profile.connectedAccountCount,
      rejectedLibraries: 0,
      rejections: [],
      lastForProfileRequirement: false,
    })
  }),

  http.post(`${ADMIN}/:profileId/test-sign-in`, ({ params }) => {
    const profile = mockConnectionProfiles.find((p) => p.id === params.profileId)
    if (!profile) return notFound()
    if (!profile.clientSecretSet) {
      return HttpResponse.json({
        success: false,
        message: 'Für den Zugang ist kein Client-Secret bzw. Dienstkonto-Schlüssel hinterlegt.',
      })
    }
    profile.signInRejected = false
    return HttpResponse.json({ success: true, message: 'Anmeldung erfolgreich.' })
  }),

  http.put(`${ADMIN}/:profileId/lock`, async ({ params, request }) => {
    const index = mockConnectionProfiles.findIndex((p) => p.id === params.profileId)
    if (index < 0) return notFound()
    const { locked } = (await request.json()) as ConnectorLockRequest
    const updated: ConnectionProfileResponse = {
      ...mockConnectionProfiles[index],
      locked,
      lockedAt: locked ? new Date().toISOString() : null,
    }
    mockConnectionProfiles[index] = updated
    return HttpResponse.json(updated)
  }),

  http.get('/api/v1/admin/connector-types', () =>
    HttpResponse.json(CONNECTOR_TYPES.map(typeState)),
  ),

  http.put('/api/v1/admin/connector-types/:sourceType/lock', async ({ params, request }) => {
    const type = CONNECTOR_TYPES.find((t) => t.sourceType === params.sourceType)
    if (!type) {
      return HttpResponse.json({ error: 'Quellart unbekannt', status: 400 }, { status: 400 })
    }
    const { locked } = (await request.json()) as ConnectorLockRequest
    if (locked) lockedTypes.add(type.sourceType)
    else lockedTypes.delete(type.sourceType)
    return HttpResponse.json(typeState(type))
  }),

  http.get('/api/v1/admin/connector-types/:sourceType/profile-requirement', ({ params }) => {
    const type = CONNECTOR_TYPES.find((t) => t.sourceType === params.sourceType)
    if (!type) {
      return HttpResponse.json({ error: 'Quellart unbekannt', status: 400 }, { status: 400 })
    }
    return HttpResponse.json(requirementOf(type))
  }),

  // Like the backend: only an "optional" type switches, and only on while a usable profile exists.
  http.put(
    '/api/v1/admin/connector-types/:sourceType/profile-requirement',
    async ({ params, request }) => {
      const type = CONNECTOR_TYPES.find((t) => t.sourceType === params.sourceType)
      if (!type || type.profileSupport !== 'OPTIONAL') {
        return HttpResponse.json(
          { error: 'Diese Quellart lässt sich nicht umschalten', status: 400 },
          { status: 400 },
        )
      }
      const body = (await request.json()) as ConnectorProfileRequirementRequest
      if (!body.required) {
        requiredTypes.delete(type.sourceType)
        return HttpResponse.json(typeState(type))
      }
      if (!requiredTypes.has(type.sourceType) && !usableProfileExists(type.sourceType)) {
        return HttpResponse.json(
          {
            error: `Für die Quellart „${type.displayName}“ gibt es keinen passenden Zugang.`,
            status: 409,
            code: 'PROFILE_REQUIREMENT_NEEDS_PROFILE',
          },
          { status: 409 },
        )
      }
      requiredTypes.set(type.sourceType, body.ownAddressStock ?? 'RUNS')
      return HttpResponse.json(typeState(type))
    },
  ),

  // The profiles a library may be connected through - every one admitting libraries, the ones the
  // caller may not use with the notice naming who releases them, and one for persons only where
  // the caller has an account on it. With libraryId the managers of that library ask; the mocks
  // answer them alike.
  http.get('/api/v1/connection-profiles', ({ request }) => {
    const sourceType = new URL(request.url).searchParams.get('sourceType')
    const ownAccounts = mockOwnAccountProfileIds()
    const options: ConnectionProfileOption[] = mockConnectionProfiles
      .filter(
        (p) =>
          p.sourceType === sourceType && (p.ownership !== 'PERSON' || ownAccounts.includes(p.id)),
      )
      .map((p) => {
        const creatable = !p.locked && !lockedTypes.has(p.sourceType)
        return {
          id: p.id,
          name: p.name,
          sourceType: p.sourceType,
          serverUrl: p.serverUrl,
          authMethod: p.authMethod,
          ownership: p.ownership,
          ownAccount: ownAccounts.includes(p.id),
          sourceProxy: p.sourceProxy ?? null,
          sourceInsecureSsl: p.sourceInsecureSsl,
          creatable,
          creationNotice: creatable
            ? null
            : `Der Zugang „${p.name}“ ist gesperrt. Zuständig ist die Systemverwaltung.`,
          connectorDefaults: p.connectorSettings ?? null,
        }
      })
    return HttpResponse.json(options)
  }),

  // Connection profile requests ("Zugangswunsch"): the same open request answers 200, a new one 201.
  http.post('/api/v1/connection-profile-requests', async ({ request }) => {
    const body = (await request.json()) as ConnectionProfileRequestCreateRequest
    const serverUrl = body.serverUrl.trim().replace(/\/+$/, '').toLowerCase()
    if (!/^https?:\/\/[^/]+/.test(serverUrl)) {
      return HttpResponse.json(
        {
          error:
            'serverUrl muss eine absolute Adresse mit Host sein, beginnend mit https://, http://',
          status: 400,
        },
        { status: 400 },
      )
    }
    const open = mockConnectionProfileRequests.find(
      (r) => r.state === 'OPEN' && r.sourceType === body.sourceType && r.serverUrl === serverUrl,
    )
    if (open) return HttpResponse.json(open)
    const created: ConnectionProfileRequestResponse = {
      id: `connection-profile-request-${crypto.randomUUID().slice(0, 8)}`,
      sourceType: body.sourceType,
      serverUrl,
      reason: body.reason?.trim() || null,
      state: 'OPEN',
      requestedByName: 'Dev User',
      createdAt: new Date().toISOString(),
      resolvedAt: null,
      profile: null,
      answer: null,
    }
    mockConnectionProfileRequests.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.get('/api/v1/me/connection-profile-requests', () =>
    HttpResponse.json([...mockConnectionProfileRequests].reverse()),
  ),

  http.get('/api/v1/admin/connection-profile-requests', ({ request }) => {
    const params = new URL(request.url).searchParams
    const state = params.get('state')
    const page = Number(params.get('page') ?? 0)
    const size = Number(params.get('size') ?? 25)
    const matching = mockConnectionProfileRequests.filter((r) => !state || r.state === state)
    return HttpResponse.json({
      items: matching.slice(page * size, (page + 1) * size),
      total: matching.length,
      page,
      size,
    })
  }),

  http.put('/api/v1/admin/connection-profile-requests/:requestId', async ({ params, request }) => {
    const wish = mockConnectionProfileRequests.find((r) => r.id === params.requestId)
    if (!wish) return requestNotFound()
    if (wish.state !== 'OPEN') return requestNotOpen()
    const body = (await request.json()) as ConnectionProfileRequestResolveRequest
    const profile = mockConnectionProfiles.find((p) => p.id === body.profileId)
    Object.assign(wish, {
      state: body.state,
      resolvedAt: new Date().toISOString(),
      answer: body.answer?.trim() || null,
      profile: profile ? { id: profile.id, name: profile.name } : null,
    })
    return HttpResponse.json(wish)
  }),
]

function requestNotFound() {
  return HttpResponse.json({ error: 'Zugangswunsch nicht gefunden', status: 404 }, { status: 404 })
}

function requestNotOpen() {
  return HttpResponse.json(
    {
      error: 'Der Zugangswunsch ist bereits erledigt',
      status: 409,
      code: 'CONNECTION_PROFILE_REQUEST_NOT_OPEN',
    },
    { status: 409 },
  )
}
