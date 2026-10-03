import type { ConnectionProfileResponse } from '../types/api'

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
