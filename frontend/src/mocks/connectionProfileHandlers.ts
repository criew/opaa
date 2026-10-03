import { http, HttpResponse } from 'msw'
import type {
  ConnectionProfileCreateRequest,
  ConnectionProfileResponse,
  ConnectionProfileUpdateRequest,
} from '../types/api'
import { mockConnectionProfiles, resetMockConnectionProfiles } from './connectionProfileFixtures'

export function resetConnectionProfileMockState() {
  resetMockConnectionProfiles()
}

const ADMIN = '/api/v1/admin/connection-profiles'

function notFound() {
  return HttpResponse.json({ error: 'Zugang nicht gefunden', status: 404 }, { status: 404 })
}

// Connection profiles (#2160). Like the backend, an answer never carries the client secret, and a
// change of address or registration on a profile with connections needs confirmDiscard.
export const connectionProfileHandlers = [
  http.get(ADMIN, () => HttpResponse.json(mockConnectionProfiles)),

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
      tenant: body.tenant ?? null,
      scopes: body.scopes ?? null,
      connectorSettings: body.connectorSettings ?? null,
      connectionCount: 0,
      locked: false,
      createdAt: now,
      updatedAt: now,
    }
    mockConnectionProfiles.push(created)
    return HttpResponse.json(created, { status: 201 })
  }),

  http.put(`${ADMIN}/:profileId`, async ({ params, request }) => {
    const index = mockConnectionProfiles.findIndex((p) => p.id === params.profileId)
    if (index < 0) return notFound()
    const current = mockConnectionProfiles[index]
    const body = (await request.json()) as ConnectionProfileUpdateRequest
    const discards =
      current.serverUrl !== body.serverUrl.replace(/\/+$/, '') ||
      current.authMethod !== body.authMethod ||
      (current.clientId ?? null) !== (body.clientId ?? null)
    if (discards && current.connectionCount > 0 && !body.confirmDiscard) {
      return HttpResponse.json(
        {
          error:
            'Die Änderung verwirft die Zugangsdaten bestehender Verbindungen. Bitte bestätigen.',
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
      clientSecretSet: body.clientSecret ? true : current.clientSecretSet,
      clientSecretExpiresOn: body.clientSecretExpiresOn ?? null,
      tenant: body.tenant ?? null,
      scopes: body.scopes ?? null,
      connectorSettings: body.connectorSettings ?? null,
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
      libraries: Math.ceil(profile.connectionCount / 2),
    })
  }),

  http.post(`${ADMIN}/:profileId/disconnect-all`, ({ params }) => {
    const profile = mockConnectionProfiles.find((p) => p.id === params.profileId)
    if (!profile) return notFound()
    return HttpResponse.json({
      connections: profile.connectionCount,
      libraries: profile.connectionCount,
    })
  }),
]
