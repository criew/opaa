import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockConnectionProfiles } from '../mocks/connectionProfileFixtures'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import type { SourceTypeDescriptor } from '../types/api'
import ConnectionProfileManagementPage from './ConnectionProfileManagementPage'

const DRIVE: SourceTypeDescriptor = {
  type: 'GOOGLE_DRIVE',
  displayName: 'Google Drive',
  indexingRun: true,
  uploads: false,
  pushIntake: false,
  browsable: true,
  profileSupport: 'OPTIONAL',
  profileRequired: false,
  signIns: [{ method: 'SERVICE_ACCOUNT_KEY', ownerships: ['LIBRARY'] }],
  profileDefaults: [
    { key: 'subject', label: 'Imitiertes Konto', kind: 'TEXT', choices: [], profileOnly: true },
  ],
  serverAddress: { schemes: [], fixed: 'https://www.googleapis.com' },
  creatable: true,
  creatableWithOwnAddress: true,
  locked: false,
}

const KEY_FILE = JSON.stringify({
  type: 'service_account',
  client_email: 'leser@projekt.iam.gserviceaccount.com',
  private_key_id: 'abc',
  private_key: '-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n',
})

/** A profile's own sign-in in the administration (#2220): key upload, rejection and its test. */
describe('ConnectionProfileManagementPage, sign-in of the profile', () => {
  beforeEach(() => {
    useAuthStore.setState({
      mode: 'dev',
      isAuthenticated: true,
      isLoading: false,
      user: {
        id: 'user-1',
        email: 'admin@opaa.local',
        displayName: 'Admin',
        systemRole: 'SYSTEM_ADMIN',
      },
      token: null,
      error: null,
      userManager: null,
    })
    server.use(http.get('/api/v1/source-types', () => HttpResponse.json([DRIVE])))
  })

  afterEach(() => {
    server.events.removeAllListeners()
  })

  it('takes a service account key as a file and asks for no client id', async () => {
    const user = userEvent.setup()
    const sent: Array<Record<string, unknown>> = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith('/admin/connection-profiles')) {
        sent.push((await request.clone().json()) as Record<string, unknown>)
      }
    })
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await user.click(within(dialog).getByRole('radio', { name: /Google Drive/ }))
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Drive')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'Dienstkonto-Schlüssel' }))
    expect(within(dialog).queryByLabelText(/^Client-ID/)).not.toBeInTheDocument()
    await user.upload(
      within(dialog).getByLabelText('Schlüsseldatei des Dienstkontos'),
      new File([KEY_FILE], 'schluessel.json', { type: 'application/json' }),
    )
    expect(await within(dialog).findByTestId('profile-key-status')).toHaveTextContent(
      'leser@projekt.iam.gserviceaccount.com',
    )
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await screen.findByRole('row', { name: /Zugang Drive/ })
    expect(sent[0]).toMatchObject({
      authMethod: 'SERVICE_ACCOUNT_KEY',
      clientId: null,
      clientSecret: KEY_FILE,
      serverUrl: 'https://www.googleapis.com',
    })
  }, 20000)

  it('shows a rejected sign-in and lifts it with a sign-in test', async () => {
    const user = userEvent.setup()
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      sourceType: 'GOOGLE_DRIVE',
      authMethod: 'SERVICE_ACCOUNT_KEY',
      clientSecretSet: true,
      signInRejected: true,
    }
    renderWithProviders(<ConnectionProfileManagementPage />)

    const row = await screen.findByRole('row', { name: /Zugang Nextcloud intern/ })
    expect(within(row).getByText('Anmeldung abgelehnt')).toBeVisible()
    await user.click(
      within(row).getByRole('button', { name: 'Anmeldung von Zugang Nextcloud intern testen' }),
    )

    expect(await screen.findByText('Anmeldung erfolgreich.')).toBeVisible()
    await waitFor(() =>
      expect(
        within(screen.getByRole('row', { name: /Zugang Nextcloud intern/ })).queryByText(
          'Anmeldung abgelehnt',
        ),
      ).not.toBeInTheDocument(),
    )
  })
})
