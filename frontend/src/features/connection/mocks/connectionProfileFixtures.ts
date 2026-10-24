import type { ConnectionProfileRef, ConnectionProfileResponse } from '@/shared/api/types'

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
      signInRejected: false,
      connectorSettings: { edition: 'INTERN' },
      connectionCount: 2,
      connectedAccountCount: { count: null, fewerThan: 5 },
      expiredConnectionCount: { count: null, fewerThan: 5 },
      expiredConnectionWarning: false,
      locked: false,
      createdAt: '2026-10-01T09:00:00Z',
      updatedAt: '2026-10-01T09:00:00Z',
    },
    // the browser's mock mode also offers the profiles SharePoint and Dropbox libraries need;
    // the tests keep the single profile their admin overview counts on
    ...(import.meta.env.MODE === 'test' ? [] : libraryOnlyProfiles()),
  ]
}

/** Mutable so the handlers reflect create, change and delete on the next list GET. */
export let mockConnectionProfiles: ConnectionProfileResponse[] = initialProfiles()

export function resetMockConnectionProfiles() {
  mockConnectionProfiles = initialProfiles()
}

/** The profile as a library names it to its managers - never a secret. */
export function mockProfileRef(profile: ConnectionProfileResponse): ConnectionProfileRef {
  return {
    id: profile.id,
    name: profile.name,
    serverUrl: profile.serverUrl,
    authMethod: profile.authMethod,
    connectorDefaults: profile.connectorSettings ?? null,
  }
}

/** Profiles for the types whose libraries cannot be created without one. */
function libraryOnlyProfiles(): ConnectionProfileResponse[] {
  return [
    {
      id: 'connection-profile-sharepoint',
      name: 'Microsoft 365 Rheinfurt',
      sourceType: 'SHAREPOINT',
      serverUrl: 'https://graph.microsoft.com',
      authMethod: 'CLIENT_CREDENTIALS',
      ownership: 'LIBRARY',
      clientSecretSet: true,
      clientSecretExpiresSoon: false,
      signInRejected: false,
      connectorSettings: null,
      connectionCount: 1,
      connectedAccountCount: { count: null, fewerThan: 5 },
      expiredConnectionCount: { count: null, fewerThan: 5 },
      expiredConnectionWarning: false,
      locked: false,
      createdAt: '2026-10-01T09:00:00Z',
      updatedAt: '2026-10-01T09:00:00Z',
    },
    {
      id: 'connection-profile-dropbox-library',
      name: 'Dropbox Rheinfurt',
      sourceType: 'DROPBOX',
      serverUrl: 'https://api.dropboxapi.com',
      authMethod: 'OAUTH',
      ownership: 'LIBRARY',
      clientSecretSet: true,
      clientSecretExpiresSoon: false,
      signInRejected: false,
      connectorSettings: null,
      connectionCount: 1,
      connectedAccountCount: { count: null, fewerThan: 5 },
      expiredConnectionCount: { count: null, fewerThan: 5 },
      expiredConnectionWarning: false,
      locked: false,
      createdAt: '2026-10-01T09:00:00Z',
      updatedAt: '2026-10-01T09:00:00Z',
    },
  ]
}
