import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockSourceTypes } from '../mocks/libraryFixtures'
import { mockConnectionProfiles } from '../mocks/connectionProfileFixtures'
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

const PROFILE = 'Zugang Nextcloud intern'

/** Captures the body of every PUT the page sends. */
function capturePuts(): ConnectionProfileUpdateRequest[] {
  const sent: ConnectionProfileUpdateRequest[] = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PUT') {
      sent.push((await request.clone().json()) as ConnectionProfileUpdateRequest)
    }
  })
  return sent
}

async function openEdit(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: `${PROFILE} bearbeiten` }))
  return screen.findByRole('dialog', { name: new RegExp(PROFILE) })
}

describe('ConnectionProfileManagementPage', () => {
  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.get('/api/v1/source-types', () => HttpResponse.json([...mockSourceTypes, NEXTCLOUD])),
    )
  })

  afterEach(() => {
    server.events.removeAllListeners()
  })

  it('tells a regular account that the system administration keeps the profiles', async () => {
    signInAs('USER')
    renderWithProviders(<ConnectionProfileManagementPage />)

    expect(
      await screen.findByText(/Zugänge werden von der Systemverwaltung gepflegt/),
    ).toBeVisible()
  })

  it('offers no dead end while no source type admits profiles', async () => {
    server.use(http.get('/api/v1/source-types', () => HttpResponse.json(mockSourceTypes)))
    renderWithProviders(<ConnectionProfileManagementPage />)

    expect(await screen.findByTestId('no-profile-source-type')).toHaveTextContent(
      'Zugänge stehen zur Verfügung, sobald eine Quellart sie unterstützt.',
    )
    expect(screen.getByRole('button', { name: 'Neuer Zugang' })).toBeDisabled()
  })

  it('lists the profiles with their connector, address and sign-in', async () => {
    renderWithProviders(<ConnectionProfileManagementPage />)

    const row = await screen.findByRole('row', { name: new RegExp(PROFILE) })
    expect(await within(row).findByText('Nextcloud')).toBeVisible()
    expect(row).toHaveTextContent('https://cloud.rheinfurt.example')
    expect(row).toHaveTextContent('Persönliches Geheimnis')
  })

  it('shows the expiry of the secret as a German date and tells expired from expiring', async () => {
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      clientSecretExpiresOn: '2020-03-31',
      clientSecretExpiresSoon: true,
    }
    mockConnectionProfiles.push({
      ...mockConnectionProfiles[0],
      id: 'p-soon',
      name: 'Zugang bald',
      clientSecretExpiresOn: '2999-12-24',
    })
    renderWithProviders(<ConnectionProfileManagementPage />)

    expect(await screen.findByText('Secret abgelaufen am 31.03.2020')).toBeVisible()
    expect(screen.getByText('Secret läuft ab am 24.12.2999')).toBeVisible()
  })

  it('creates a profile after choosing the connector as a tile; creating needs a complete choice', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    const create = within(dialog).getByRole('button', { name: 'Anlegen' })
    expect(create).toBeDisabled()
    expect(within(dialog).getByRole('radio', { name: /Confluence/ })).toBeDisabled()
    await user.click(within(dialog).getByRole('radio', { name: /Nextcloud/ }))
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Nextcloud Partner')
    await user.type(within(dialog).getByLabelText(/^Server-Adresse/), 'https://partner.example.org')
    expect(create).toBeDisabled()
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'OAuth' }))
    expect(create).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/^Client-ID/), 'opaa')
    await user.type(within(dialog).getByLabelText(/^Client-Secret/), 'geheim')
    await user.click(create)

    const row = await screen.findByRole('row', { name: /Zugang Nextcloud Partner/ })
    expect(row).toHaveTextContent('OAuth')
    expect(row).not.toHaveTextContent('geheim')
  }, 20000)

  it('keeps the connector defaults and the secret on a mere rename', async () => {
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      authMethod: 'OAUTH',
      clientId: 'opaa',
      clientSecretSet: true,
    }
    const user = userEvent.setup()
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const dialog = await openEdit(user)
    const name = within(dialog).getByLabelText(/^Name/)
    await user.clear(name)
    await user.type(name, 'Zugang Nextcloud Rathaus')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await screen.findByRole('row', { name: /Zugang Nextcloud Rathaus/ })
    expect(sent).toHaveLength(1)
    expect(sent[0].connectorSettings).toEqual({ edition: 'INTERN' })
    // an empty secret field keeps the stored secret: the field is not sent at all
    expect(sent[0]).not.toHaveProperty('clientSecret')
    expect(mockConnectionProfiles[0].clientSecretSet).toBe(true)
  }, 20000)

  it('asks with connections and libraries before a change that discards secrets', async () => {
    const user = userEvent.setup()
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const dialog = await openEdit(user)
    const address = within(dialog).getByLabelText(/^Server-Adresse/)
    await user.clear(address)
    await user.type(address, 'https://cloud-neu.rheinfurt.example')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    const question = await screen.findByRole('dialog', { name: /Zugangsdaten von/ })
    expect(question).toHaveTextContent('2 Verbindungen und 1 Bibliothek')
    await answerConfirm(user, /Zugangsdaten von/, 'Verwerfen und speichern')

    expect(await screen.findByText('https://cloud-neu.rheinfurt.example')).toBeVisible()
    expect(sent.at(-1)).toMatchObject({ confirmDiscard: true })
  }, 20000)

  it('asks with the number of connections before disconnecting all of them', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(
      await screen.findByRole('button', { name: `Alle Verbindungen von ${PROFILE} trennen` }),
    )
    const question = await screen.findByRole('dialog', { name: /Alle Verbindungen von/ })
    expect(question).toHaveTextContent('2 Verbindungen')
    await answerConfirm(user, /Alle Verbindungen von/, 'Alle trennen')

    expect(await screen.findByText('2 Verbindungen getrennt.')).toBeVisible()
  })

  it('deletes a profile after naming what happens to its libraries', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: `${PROFILE} löschen` }))
    const question = await screen.findByRole('dialog', { name: /löschen\?/ })
    expect(question).toHaveTextContent('Zugang entfernt')
    await answerConfirm(user, /löschen\?/, 'Löschen')

    expect(await screen.findByText('Es sind noch keine Zugänge angelegt.')).toBeVisible()
  })
})
