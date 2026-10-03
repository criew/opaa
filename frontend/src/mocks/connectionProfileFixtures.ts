import type { ConnectionProfileResponse } from '../types/api'

function initialProfiles(): ConnectionProfileResponse[] {
  return [
    {
      id: 'connection-profile-wiki',
      name: 'Zugang Wiki intern',
      sourceType: 'CONFLUENCE',
      serverUrl: 'https://wiki.rheinfurt.example',
      authMethod: 'PERSONAL_SECRET',
      ownership: 'LIBRARY',
      clientSecretSet: false,
      clientSecretExpiresSoon: false,
      connectionCount: 2,
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
