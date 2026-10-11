import { expect } from '@playwright/test'
import { apiAs } from './externalAccess'

/**
 * Creates a Dropbox access (sign-in OAuth, ownership library) as the system administration. The
 * address is the connector's fixed one, which follows the configured API base (dropbox-stub) and
 * is what the backend reaches.
 */
export async function createDropboxProfile(name: string): Promise<string> {
  const api = await apiAs('dev-admin')
  try {
    const created = await api.post('/api/v1/admin/connection-profiles', {
      data: {
        name,
        sourceType: 'DROPBOX',
        serverUrl: 'http://dropbox-stub:8090',
        authMethod: 'OAUTH',
        ownership: 'LIBRARY',
        clientId: 'e2e-app-key',
        clientSecret: 'e2e-app-secret',
      },
    })
    expect(created.status(), await created.text()).toBe(201)
    return ((await created.json()) as { id: string }).id
  } finally {
    await api.dispose()
  }
}
