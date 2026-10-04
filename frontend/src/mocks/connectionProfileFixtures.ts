import type { ConnectionProfileRequestResponse, ConnectionProfileResponse } from '../types/api'

function initialProfiles(): ConnectionProfileResponse[] {
  return [
    {
      id: 'connection-profile-nextcloud',
      name: 'Zugang Nextcloud intern',
      sourceType: 'NEXTCLOUD',
      serverUrl: 'https://cloud.rheinfurt.example',
      authMethod: 'PERSONAL_SECRET',
      ownership: 'LIBRARY',
      clientSecretSet: false,
      clientSecretExpiresSoon: false,
      connectorSettings: { edition: 'INTERN' },
      sourceProxy: null,
      sourceInsecureSsl: false,
      connectionCount: 2,
      locked: false,
      createdAt: '2026-10-01T09:00:00Z',
      updatedAt: '2026-10-01T09:00:00Z',
    },
  ]
}

/** Mutable so the handlers reflect create, change and delete on the next list GET. */
export let mockConnectionProfiles: ConnectionProfileResponse[] = initialProfiles()

export function resetMockConnectionProfiles() {
  mockConnectionProfiles = initialProfiles()
}

function initialRequests(): ConnectionProfileRequestResponse[] {
  return [
    {
      id: 'connection-profile-request-partner',
      sourceType: 'NEXTCLOUD',
      serverUrl: 'https://cloud.partner.example',
      reason: 'Gemeinsame Ablage mit dem Partnerlandkreis',
      state: 'OPEN',
      requestedByName: 'Dev User',
      createdAt: '2026-10-03T08:30:00Z',
      resolvedAt: null,
      profile: null,
      answer: null,
    },
  ]
}

/** The connection profile requests ("Zugangswünsche"); mutable like the profiles. */
export let mockConnectionProfileRequests: ConnectionProfileRequestResponse[] = initialRequests()

export function resetMockConnectionProfileRequests() {
  mockConnectionProfileRequests = initialRequests()
}
