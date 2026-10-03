import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockSourceTypes } from '../mocks/libraryFixtures'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import type { ConnectionProfileUpdateRequest, SourceTypeDescriptor } from '../types/api'
import ConnectionProfileManagementPage from './ConnectionProfileManagementPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: { id: 'user-1', email: 'admin@opaa.local', displayName: 'Admin', systemRole },
    token: null,
    error: null,
    userManager: null,
  })
}

const NEXTCLOUD: SourceTypeDescriptor = {
  type: 'NEXTCLOUD',
  displayName: 'Nextcloud',
  indexingRun: true,
  uploads: false,
  pushIntake: false,
  browsable: false,
  profileSupport: 'OPTIONAL',
  authMethods: ['NONE', 'PERSONAL_SECRET', 'OAUTH'],
}

describe('ConnectionProfileManagementPage', () => {
  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.get('/api/v1/source-types', () => HttpResponse.json([...mockSourceTypes, NEXTCLOUD])),
    )
  })

  it('tells a regular account that the system administration keeps the profiles', async () => {
    signInAs('USER')
    renderWithProviders(<ConnectionProfileManagementPage />)

    expect(
      await screen.findByText(/Zugänge werden von der Systemverwaltung gepflegt/),
    ).toBeVisible()
  })

  it('lists the profiles with their connector, address and connections', async () => {
    renderWithProviders(<ConnectionProfileManagementPage />)

    const row = await screen.findByRole('row', { name: /Zugang Wiki intern/ })
    expect(await within(row).findByText('Confluence')).toBeVisible()
    expect(row).toHaveTextContent('https://wiki.rheinfurt.example')
    expect(row).toHaveTextContent('Persönliches Geheimnis')
  })

  it('creates a profile after choosing the connector as a tile; types without profiles are locked', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    expect(within(dialog).getByRole('radio', { name: /Confluence/ })).toBeDisabled()
    await user.click(within(dialog).getByRole('radio', { name: /Nextcloud/ }))
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Nextcloud intern')
    await user.type(within(dialog).getByLabelText(/^Server-Adresse/), 'https://cloud.example.org')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'OAuth' }))
    await user.type(within(dialog).getByLabelText(/^Client-ID/), 'opaa')
    await user.type(within(dialog).getByLabelText(/^Client-Secret/), 'geheim')
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    const row = await screen.findByRole('row', { name: /Zugang Nextcloud intern/ })
    expect(row).toHaveTextContent('OAuth')
    expect(row).not.toHaveTextContent('geheim')
  }, 20000)

  it('asks before a change that discards the secrets of existing connections', async () => {
    const user = userEvent.setup()
    let sent: ConnectionProfileUpdateRequest | null = null
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'PUT') {
        sent = (await request.clone().json()) as ConnectionProfileUpdateRequest
      }
    })
    renderWithProviders(<ConnectionProfileManagementPage />)

    const row = await screen.findByRole('row', { name: /Zugang Wiki intern/ })
    await user.click(within(row).getByRole('button', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog', { name: /Zugang Wiki intern/ })
    const address = within(dialog).getByLabelText(/^Server-Adresse/)
    await user.clear(address)
    await user.type(address, 'https://wiki-neu.rheinfurt.example')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    const question = await screen.findByRole('dialog', { name: /Zugangsdaten von/ })
    expect(question).toHaveTextContent('2 Verbindungen')
    await answerConfirm(user, /Zugangsdaten von/, 'Verwerfen und speichern')

    expect(await screen.findByText('https://wiki-neu.rheinfurt.example')).toBeVisible()
    expect(sent).toMatchObject({ confirmDiscard: true })
    server.events.removeAllListeners()
  }, 20000)

  it('asks with the number of connections before disconnecting all of them', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(
      await screen.findByRole('button', {
        name: 'Alle Verbindungen von Zugang Wiki intern trennen',
      }),
    )
    const question = await screen.findByRole('dialog', { name: /Alle Verbindungen von/ })
    expect(question).toHaveTextContent('2 Verbindungen')
    await answerConfirm(user, /Alle Verbindungen von/, 'Alle trennen')

    expect(await screen.findByText('2 Verbindungen getrennt.')).toBeVisible()
  })
})
