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

const REALM: SourceTypeDescriptor = {
  type: 'NEXTCLOUD',
  displayName: 'Nextcloud',
  indexingRun: true,
  uploads: false,
  pushIntake: false,
  browsable: true,
  profileSupport: 'REQUIRED',
  profileRequired: true,
  signIns: [
    {
      method: 'OAUTH',
      ownerships: ['PERSON'],
      profileEndpoints: { authorization: true, token: true, revocation: false },
      defaultScopes: 'openid offline_access',
    },
  ],
  profileDefaults: [],
  serverAddress: { schemes: ['https'], fixed: null },
  creatable: true,
  creatableWithOwnAddress: false,
  locked: false,
}

/** OAuth in the administration (#2168): endpoints the connector leaves to the profile. */
describe('ConnectionProfileManagementPage, OAuth', () => {
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
    server.use(http.get('/api/v1/source-types', () => HttpResponse.json([REALM])))
  })

  afterEach(() => {
    server.events.removeAllListeners()
  })

  it('asks for exactly the endpoints the connector leaves to the profile and names the redirect URI', async () => {
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
    await user.click(within(dialog).getByRole('radio', { name: /Nextcloud/ }))
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Keycloak')
    await user.click(within(dialog).getByLabelText(/^Server-Adresse/))
    await user.paste('https://cloud.example.org')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'OAuth' }))
    await user.type(within(dialog).getByLabelText(/^Client-ID/), 'opaa')

    expect(within(dialog).queryByLabelText(/^Widerrufs-Endpunkt/)).not.toBeInTheDocument()
    expect(within(dialog).getByText(/Vorgabe der Quellart: „openid offline_access“/)).toBeVisible()
    expect(within(dialog).getByTestId('connection-profile-redirect-uri')).toHaveTextContent(
      `${window.location.origin}/connections/callback`,
    )
    const create = within(dialog).getByRole('button', { name: 'Anlegen' })
    expect(create).toBeDisabled()

    await user.click(within(dialog).getByLabelText(/^Autorisierungs-Endpunkt/))
    await user.paste('https://idp.example.org/realms/r/protocol/openid-connect/auth')
    expect(create).toBeDisabled()
    await user.click(within(dialog).getByLabelText(/^Token-Endpunkt/))
    await user.paste('https://idp.example.org/realms/r/protocol/openid-connect/token')
    await user.click(create)

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0]).toMatchObject({
      authMethod: 'OAUTH',
      ownership: 'PERSON',
      clientId: 'opaa',
      authorizationEndpoint: 'https://idp.example.org/realms/r/protocol/openid-connect/auth',
      tokenEndpoint: 'https://idp.example.org/realms/r/protocol/openid-connect/token',
      revocationEndpoint: null,
    })
  })

  it('warns at a profile whose expired connections reach the threshold', async () => {
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      expiredConnectionCount: { count: 12, fewerThan: null },
      expiredConnectionWarning: true,
    }
    renderWithProviders(<ConnectionProfileManagementPage />)

    const row = (await screen.findByText('Zugang Nextcloud intern')).closest('tr')!
    expect(within(row).getByText('12')).toBeInTheDocument()
    expect(within(row).getByText('Viele abgelaufen')).toBeInTheDocument()
  })
})
