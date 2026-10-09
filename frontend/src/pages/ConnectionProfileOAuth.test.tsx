import { screen, waitFor, within } from '@testing-library/react'
import userEvent, { PointerEventsCheckLevel } from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockConnectionProfiles } from '../mocks/connectionProfileFixtures'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import type { SourceTypeDescriptor } from '../types/api'
import ConnectionProfileManagementPage from './ConnectionProfileManagementPage'

/**
 * Input without delays and without the pointer-events check on every step: the long form of a new
 * OAuth profile otherwise runs close to the default test timeout.
 */
const FAST_INPUT = {
  delay: null,
  skipHover: true,
  pointerEventsCheck: PointerEventsCheckLevel.Never,
} as const

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

  /** Opens „Zugang anlegen“ for the OAuth realm with name, address and client id entered. */
  async function newOAuthProfile(user: ReturnType<typeof userEvent.setup>) {
    renderWithProviders(<ConnectionProfileManagementPage />)
    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await user.click(within(dialog).getByRole('radio', { name: /Nextcloud/ }))
    await user.click(within(dialog).getByLabelText(/^Name/))
    await user.paste('Zugang Keycloak')
    await user.click(within(dialog).getByLabelText(/^Server-Adresse/))
    await user.paste('https://cloud.example.org')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'OAuth' }))
    await user.click(within(dialog).getByLabelText(/^Client-ID/))
    await user.paste('opaa')
    return dialog
  }

  it('asks for exactly the endpoints the connector leaves to the profile and names the redirect URI', async () => {
    const user = userEvent.setup(FAST_INPUT)
    const sent: Array<Record<string, unknown>> = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith('/admin/connection-profiles')) {
        sent.push((await request.clone().json()) as Record<string, unknown>)
      }
    })
    const dialog = await newOAuthProfile(user)

    expect(within(dialog).queryByLabelText(/^Widerrufs-Endpunkt/)).not.toBeInTheDocument()
    expect(within(dialog).getByText(/Vorgabe der Quellart: „openid offline_access“/)).toBeVisible()
    expect(await within(dialog).findByTestId('connection-profile-redirect-uri')).toHaveTextContent(
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
    // the first test of the file also pays the cold render of the page and its dialog; measured
    // 3.5 s alone and over 5 s on a loaded machine, hence a timeout of its own
  }, 15_000)

  it('takes every endpoint only over https', async () => {
    const user = userEvent.setup(FAST_INPUT)
    const dialog = await newOAuthProfile(user)
    const authorization = within(dialog).getByLabelText(/^Autorisierungs-Endpunkt/)
    await user.click(authorization)
    await user.paste('http://idp.intern/auth')
    const token = within(dialog).getByLabelText(/^Token-Endpunkt/)
    await user.click(token)
    await user.paste('http://idp.intern/token')

    const create = within(dialog).getByRole('button', { name: 'Anlegen' })
    expect(authorization).toHaveAttribute('aria-invalid', 'true')
    expect(token).toHaveAttribute('aria-invalid', 'true')
    expect(
      within(dialog).getByText(/Der Autorisierungs-Endpunkt muss mit https:\/\/ beginnen/),
    ).toBeVisible()
    expect(
      within(dialog).getByText(/Der Token-Endpunkt muss mit https:\/\/ beginnen/),
    ).toBeVisible()
    expect(create).toBeDisabled()

    await user.clear(authorization)
    await user.click(authorization)
    await user.paste('https://idp.intern/auth')
    await user.clear(token)
    await user.click(token)
    await user.paste('https://idp.intern/token')
    expect(authorization).toHaveAttribute('aria-invalid', 'false')
    expect(token).toHaveAttribute('aria-invalid', 'false')
    expect(create).toBeEnabled()
  })

  it('says that no consent is possible where the server has no public address', async () => {
    server.use(
      http.get('/api/v1/admin/connection-profiles/oauth-redirect', () =>
        HttpResponse.json({ redirectUri: null }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await user.click(within(dialog).getByRole('radio', { name: /Nextcloud/ }))
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'OAuth' }))

    const notice = await within(dialog).findByTestId('connection-profile-redirect-uri')
    expect(notice).toHaveTextContent('OPAA_PUBLIC_BASE_URL')
    expect(notice).toHaveTextContent('lässt sich kein Konto beim Anbieter verbinden')
    expect(notice).not.toHaveTextContent('/connections/callback')
  })

  describe('a stored client secret and a moved endpoint', () => {
    const TOKEN = 'https://idp.example.org/realms/r/protocol/openid-connect/token'
    const MOVED = 'https://anderer.example.org/token'

    beforeEach(() => {
      mockConnectionProfiles[0] = {
        ...mockConnectionProfiles[0],
        authMethod: 'OAUTH',
        ownership: 'PERSON',
        clientId: 'opaa',
        clientSecretSet: true,
        connectorSettings: null,
        connectionCount: 0,
        authorizationEndpoint: 'https://idp.example.org/realms/r/protocol/openid-connect/auth',
        tokenEndpoint: TOKEN,
        revocationEndpoint: null,
      }
    })

    async function openWithMovedTokenEndpoint() {
      const user = userEvent.setup()
      const sent: Array<Record<string, unknown>> = []
      server.events.on('request:start', async ({ request }) => {
        if (request.method === 'PUT' && request.url.includes('/admin/connection-profiles/')) {
          sent.push((await request.clone().json()) as Record<string, unknown>)
        }
      })
      renderWithProviders(<ConnectionProfileManagementPage />)
      await user.click(
        await screen.findByRole('button', { name: 'Zugang Nextcloud intern bearbeiten' }),
      )
      const dialog = await screen.findByRole('dialog', { name: /bearbeiten/ })
      const save = within(dialog).getByRole('button', { name: 'Speichern' })
      expect(save).toBeEnabled()
      expect(within(dialog).getByLabelText(/^Client-Secret/)).not.toBeRequired()

      const token = within(dialog).getByLabelText(/^Token-Endpunkt/)
      await user.clear(token)
      await user.click(token)
      await user.paste(MOVED)
      return { user, dialog, save, sent }
    }

    it('asks for a new secret before a moved token endpoint can be saved', async () => {
      const { user, dialog, save, sent } = await openWithMovedTokenEndpoint()

      const secret = within(dialog).getByLabelText(/^Client-Secret/)
      expect(secret).toBeRequired()
      expect(
        within(dialog).getByText(/Der Token-Endpunkt ändert sich\. Das hinterlegte Secret/),
      ).toBeVisible()
      expect(save).toBeDisabled()

      await user.type(secret, 'neues-geheimnis')
      expect(save).toBeEnabled()
      await user.click(save)

      await waitFor(() => expect(sent).toHaveLength(1))
      expect(sent[0]).toMatchObject({ tokenEndpoint: MOVED, clientSecret: 'neues-geheimnis' })
    })

    it('saves a public client with an empty secret once that is chosen', async () => {
      const { user, dialog, save, sent } = await openWithMovedTokenEndpoint()
      expect(save).toBeDisabled()

      await user.click(
        within(dialog).getByRole('checkbox', {
          name: 'Ohne Client-Secret speichern (öffentlicher Client)',
        }),
      )
      expect(within(dialog).getByLabelText(/^Client-Secret/)).toBeDisabled()
      await user.click(save)

      await waitFor(() => expect(sent).toHaveLength(1))
      expect(sent[0]).toMatchObject({ tokenEndpoint: MOVED, clientSecret: '' })
    })

    it('keeps the stored secret where only the authorization endpoint moves', async () => {
      const user = userEvent.setup()
      renderWithProviders(<ConnectionProfileManagementPage />)
      await user.click(
        await screen.findByRole('button', { name: 'Zugang Nextcloud intern bearbeiten' }),
      )
      const dialog = await screen.findByRole('dialog', { name: /bearbeiten/ })
      const authorization = within(dialog).getByLabelText(/^Autorisierungs-Endpunkt/)
      await user.clear(authorization)
      await user.click(authorization)
      await user.paste('https://anderer.example.org/authorize')

      expect(within(dialog).getByLabelText(/^Client-Secret/)).not.toBeRequired()
      expect(within(dialog).queryByRole('checkbox')).not.toBeInTheDocument()
      expect(within(dialog).getByRole('button', { name: 'Speichern' })).toBeEnabled()
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
