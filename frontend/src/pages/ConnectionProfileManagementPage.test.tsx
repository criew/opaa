import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'

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
  profileSupport: 'REQUIRED',
  profileRequired: true,
  signIns: [
    { method: 'NONE', ownerships: ['LIBRARY', 'PERSON'] },
    { method: 'PERSONAL_SECRET', ownerships: ['LIBRARY'], secretForm: 'USERNAME_AND_PASSWORD' },
    { method: 'OAUTH', ownerships: ['LIBRARY', 'PERSON'] },
  ],
  profileDefaults: [
    { key: 'edition', label: 'Edition', kind: 'CHOICE', choices: ['INTERN', 'EXTERN'] },
    { key: 'region', label: 'Region', kind: 'TEXT', choices: [] },
    { key: 'pathStyle', label: 'Pfad-Adressierung', kind: 'BOOLEAN', choices: [] },
  ],
  serverAddress: { schemes: ['https', 'http'] },
  creatable: true,
  creatableWithOwnAddress: true,
  locked: false,
}

/** Source types without profiles - set by the tests themselves, not taken from the global mock. */
const WITHOUT_PROFILES: SourceTypeDescriptor[] = [
  {
    type: 'CONFLUENCE',
    displayName: 'Confluence',
    indexingRun: true,
    uploads: false,
    pushIntake: true,
    browsable: true,
    profileSupport: 'FORBIDDEN',
    profileRequired: false,
    signIns: [],
    profileDefaults: [],
    serverAddress: { schemes: ['https', 'http'] },
    creatable: true,
    creatableWithOwnAddress: true,
    locked: false,
  },
  {
    type: 'S3',
    displayName: 'S3-Objektspeicher',
    indexingRun: true,
    uploads: false,
    pushIntake: true,
    browsable: true,
    profileSupport: 'FORBIDDEN',
    profileRequired: false,
    signIns: [],
    profileDefaults: [],
    serverAddress: { schemes: ['https', 'http'] },
    creatable: true,
    creatableWithOwnAddress: true,
    locked: false,
  },
  {
    type: 'UPLOAD',
    displayName: 'Upload',
    indexingRun: false,
    uploads: true,
    pushIntake: false,
    browsable: false,
    profileSupport: 'FORBIDDEN',
    profileRequired: false,
    signIns: [],
    profileDefaults: [],
    serverAddress: { schemes: ['https', 'http'] },
    creatable: true,
    creatableWithOwnAddress: true,
    locked: false,
  },
]

/** A type with a fixed address and no defaults - its form asks for neither. */
const FIXED: SourceTypeDescriptor = {
  type: 'DRIVE_PROBE',
  displayName: 'Ablage mit fester Adresse',
  indexingRun: true,
  uploads: false,
  pushIntake: false,
  browsable: false,
  profileSupport: 'OPTIONAL',
  profileRequired: false,
  signIns: [{ method: 'NONE', ownerships: ['LIBRARY'] }],
  profileDefaults: [],
  serverAddress: { schemes: [], fixed: 'https://api.ablage.example' },
  creatable: true,
  creatableWithOwnAddress: true,
  locked: false,
}

const PROFILE = 'Zugang Nextcloud intern'

/** Captures the body of every POST to the profile administration. */
function capturePosts(): Array<Record<string, unknown>> {
  const sent: Array<Record<string, unknown>> = []
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'POST' && request.url.endsWith('/admin/connection-profiles')) {
      sent.push((await request.clone().json()) as Record<string, unknown>)
    }
  })
  return sent
}

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
      http.get('/api/v1/source-types', () => HttpResponse.json([...WITHOUT_PROFILES, NEXTCLOUD])),
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
    server.use(http.get('/api/v1/source-types', () => HttpResponse.json(WITHOUT_PROFILES)))
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

  it('shows a field per declared default and sends only the ones set, typed by kind', async () => {
    const user = userEvent.setup()
    const sent = capturePosts()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await user.click(within(dialog).getByRole('radio', { name: /Nextcloud/ }))
    expect(within(dialog).queryByLabelText(/Konnektor-Vorgaben/)).not.toBeInTheDocument()
    expect(within(dialog).getByText('Vorgaben für jede Bibliothek')).toBeVisible()
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang mit Vorgaben')
    await user.type(within(dialog).getByLabelText(/^Server-Adresse/), 'https://cloud.example.org')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'Ohne Anmeldung' }))
    await user.click(within(dialog).getByLabelText(/^Edition/))
    await user.click(await screen.findByRole('option', { name: 'EXTERN' }))
    await user.click(within(dialog).getByLabelText(/^Pfad-Adressierung/))
    await user.click(await screen.findByRole('option', { name: 'Nein' }))
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await screen.findByRole('row', { name: /Zugang mit Vorgaben/ })
    expect(sent).toHaveLength(1)
    // the empty region sets nothing; the yes/no travels as a boolean
    expect(sent[0].connectorSettings).toEqual({ edition: 'EXTERN', pathStyle: false })
  }, 20000)

  it('offers only the ownerships the chosen sign-in admits', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await user.click(within(dialog).getByRole('radio', { name: /Nextcloud/ }))
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'Persönliches Geheimnis' }))
    await user.click(within(dialog).getByLabelText(/^Besitzart/))
    const listbox = await screen.findByRole('listbox')
    expect(
      within(listbox)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['Bibliothek'])
  }, 20000)

  it('asks for no address and shows no defaults for a type with a fixed address', async () => {
    server.use(
      http.get('/api/v1/source-types', () =>
        HttpResponse.json([...WITHOUT_PROFILES, NEXTCLOUD, FIXED]),
      ),
    )
    const user = userEvent.setup()
    const sent = capturePosts()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await user.click(within(dialog).getByRole('radio', { name: /Ablage mit fester Adresse/ }))
    expect(within(dialog).queryByLabelText(/^Server-Adresse/)).not.toBeInTheDocument()
    expect(within(dialog).getByText(/feste Adresse: https:\/\/api\.ablage\.example/)).toBeVisible()
    expect(within(dialog).queryByText('Vorgaben für jede Bibliothek')).not.toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Ablage')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'Ohne Anmeldung' }))
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await screen.findByRole('row', { name: /Zugang Ablage/ })
    expect(sent[0]).toMatchObject({
      serverUrl: 'https://api.ablage.example',
      connectorSettings: null,
    })
  }, 20000)

  // regression guard: without the source type's description the form knows no defaults, so saving
  // would send none and erase the stored ones for every library on the profile
  it('refuses to save an edit while the description of the source type is missing', async () => {
    server.use(
      http.get('/api/v1/source-types', () =>
        HttpResponse.json({ error: 'nicht erreichbar' }, { status: 500 }),
      ),
    )
    const user = userEvent.setup()
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const dialog = await openEdit(user)
    const name = within(dialog).getByLabelText(/^Name/)
    await user.clear(name)
    await user.type(name, 'Zugang Nextcloud Rathaus')

    const save = within(dialog).getByRole('button', { name: 'Speichern' })
    expect(save).toBeDisabled()
    expect(within(dialog).getByText(/Angaben der Quellart liegen nicht vor/)).toBeVisible()
    expect(sent).toHaveLength(0)
    expect(mockConnectionProfiles[0].connectorSettings).toEqual({ edition: 'INTERN' })
  }, 20000)

  it('sends a text default as typed, trimmed, beside the stored choice', async () => {
    const user = userEvent.setup()
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const dialog = await openEdit(user)
    await user.type(within(dialog).getByLabelText(/^Region/), ' eu-central-1 ')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(1))
    expect(sent[0].connectorSettings).toEqual({ edition: 'INTERN', region: 'eu-central-1' })
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

  // Spezifikation „Konnektor-Freigabe und Sperre“: die Sperre eines Zugangs mit Rückfrage, die
  // nennt, was mit Läufen und Inhalt geschieht; danach steht der Zugang als „Gesperrt“ in der Liste.
  it('locks a profile after naming what happens to its libraries, and unlocks it again', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(await screen.findByRole('button', { name: `${PROFILE} sperren` }))
    const question = await screen.findByRole('dialog', { name: /sperren\?/ })
    expect(question).toHaveTextContent('bleibt durchsuchbar')
    await answerConfirm(user, /sperren\?/, 'Sperren')

    const row = await screen.findByRole('row', { name: new RegExp(PROFILE) })
    expect(await within(row).findByText('Gesperrt')).toBeVisible()

    await user.click(screen.getByRole('button', { name: `Sperre von ${PROFILE} aufheben` }))
    await answerConfirm(user, /aufheben\?/, 'Entsperren')
    expect(await screen.findByRole('button', { name: `${PROFILE} sperren` })).toBeVisible()
  })

  it('locks a connector type after a confirmation and sends nothing on abort', async () => {
    const sent: Array<{ type: string; locked: boolean }> = []
    server.use(
      http.put('/api/v1/admin/connector-types/:sourceType/lock', async ({ params, request }) => {
        const { locked } = (await request.json()) as { locked: boolean }
        sent.push({ type: String(params.sourceType), locked })
        return HttpResponse.json({
          sourceType: String(params.sourceType),
          displayName: 'Nextcloud',
          locked,
          lockedAt: null,
        })
      }),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const types = await screen.findByRole('table', { name: 'Quellarten' })
    await user.click(within(types).getByRole('button', { name: 'Quellart Nextcloud sperren' }))
    await answerConfirm(user, /Quellart „Nextcloud“ sperren\?/, 'Abbrechen')
    expect(sent).toEqual([])

    await user.click(within(types).getByRole('button', { name: 'Quellart Nextcloud sperren' }))
    await answerConfirm(user, /Quellart „Nextcloud“ sperren\?/, 'Sperren')
    expect(await screen.findByText('Die Quellart „Nextcloud“ ist gesperrt.')).toBeVisible()
    expect(sent).toEqual([{ type: 'NEXTCLOUD', locked: true }])
  })

  // Eine Sperre der Quellart hat Vorrang: Wer nur den Zugang entsperrt, bekommt keinen Weiterlauf
  // versprochen, und die Zeile zeigt die Sperre der Quellart.
  it('does not promise a restart when the profile is unlocked while its source type stays locked', async () => {
    server.use(
      http.get('/api/v1/admin/connection-profiles', () =>
        HttpResponse.json(mockConnectionProfiles.map((profile) => ({ ...profile, locked: true }))),
      ),
      http.get('/api/v1/admin/connector-types', () =>
        HttpResponse.json([
          {
            sourceType: 'NEXTCLOUD',
            displayName: 'Nextcloud',
            locked: true,
            lockedAt: null,
            profileSupport: 'OPTIONAL',
            profileRequired: false,
          },
        ]),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const row = await screen.findByRole('row', { name: new RegExp(PROFILE) })
    expect(await within(row).findByText('Quellart gesperrt')).toBeVisible()
    await user.click(within(row).getByRole('button', { name: `Sperre von ${PROFILE} aufheben` }))
    const question = await screen.findByRole('dialog', { name: /aufheben\?/ })
    expect(question).toHaveTextContent('Die Quellart „Nextcloud“ bleibt gesperrt')
    expect(question).not.toHaveTextContent('ohne Neueinrichtung weiter')
    await answerConfirm(user, /aufheben\?/, 'Abbrechen')
  })

  // „Zugang bearbeiten“ wartet auf die Beschreibung der Quellart: solange sie lädt, ist das kein
  // Fehler, aber gespeichert wird erst danach.
  it('shows the description of the source type as loading rather than missing', async () => {
    server.use(http.get('/api/v1/source-types', () => new Promise<never>(() => {})))
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const dialog = await openEdit(user)
    expect(within(dialog).getByText('Die Angaben der Quellart werden geladen …')).toBeVisible()
    expect(within(dialog).queryByText(/liegen nicht vor/)).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Speichern' })).toBeDisabled()
  })
})

describe('ConnectionProfileManagementPage - „Nur über Zugänge“ (#2162)', () => {
  const NEXTCLOUD_STATE = {
    sourceType: 'NEXTCLOUD',
    displayName: 'Nextcloud',
    locked: false,
    lockedAt: null,
    profileSupport: 'OPTIONAL',
    profileRequired: false,
  }

  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
  })

  afterEach(() => {
    server.events.removeAllListeners()
  })

  /** Captures the body of every PUT to a profile requirement. */
  function captureRequirementPuts(): Array<Record<string, unknown>> {
    const sent: Array<Record<string, unknown>> = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'PUT' && request.url.endsWith('/profile-requirement')) {
        sent.push((await request.clone().json()) as Record<string, unknown>)
      }
    })
    return sent
  }

  async function typesTable() {
    return screen.findByRole('table', { name: 'Quellarten' })
  }

  it('offers the switch only for a type that admits profiles as optional', async () => {
    renderWithProviders(<ConnectionProfileManagementPage />)

    const types = await typesTable()
    expect(
      within(types).getByRole('switch', { name: 'Nur über Zugänge für Nextcloud' }),
    ).not.toBeChecked()
    expect(within(types).queryByRole('switch', { name: /für Confluence/ })).not.toBeInTheDocument()
    expect(within(types).getAllByText('Keine Zugänge möglich').length).toBeGreaterThan(0)
  })

  it('says why the requirement cannot be switched on, and sends nothing', async () => {
    const sent = captureRequirementPuts()
    server.use(
      http.get('/api/v1/admin/connector-types/:sourceType/profile-requirement', () =>
        HttpResponse.json({
          state: NEXTCLOUD_STATE,
          switchable: false,
          notSwitchableReason:
            'Für Nextcloud gibt es keinen nicht gesperrten Zugang, der Bibliotheken zulässt.',
          ownAddressLibraries: [],
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(
      within(await typesTable()).getByRole('switch', { name: 'Nur über Zugänge für Nextcloud' }),
    )
    const dialog = await screen.findByRole('dialog', { name: /Nur über Zugänge/ })
    expect(
      await within(dialog).findByTestId('profile-requirement-not-switchable'),
    ).toHaveTextContent('keinen nicht gesperrten Zugang')
    expect(within(dialog).queryByRole('button', { name: 'Einschalten' })).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Schließen' }))
    expect(sent).toEqual([])
  })

  it.each([
    ['RUNS', /Weiterlaufen lassen/],
    ['LOCKED', /Sperren – sie laufen nicht mehr/],
  ])(
    'switches the requirement on with the choice %s for the libraries it lists',
    async (stock, choice) => {
      const sent = captureRequirementPuts()
      const user = userEvent.setup()
      renderWithProviders(<ConnectionProfileManagementPage />)

      await user.click(
        within(await typesTable()).getByRole('switch', { name: 'Nur über Zugänge für Nextcloud' }),
      )
      const dialog = await screen.findByRole('dialog', { name: /Nur über Zugänge/ })
      const list = await within(dialog).findByRole('list', {
        name: 'Bibliotheken mit eigener Adresse',
      })
      expect(list).toHaveTextContent('Projektablage · Gruppe Referat 50')
      await user.click(within(dialog).getByRole('radio', { name: choice }))
      await user.click(within(dialog).getByRole('button', { name: 'Einschalten' }))

      await waitFor(() => expect(sent).toEqual([{ required: true, ownAddressStock: stock }]))
      expect(
        await screen.findByText('Die Quellart „Nextcloud“ ist jetzt nur über Zugänge nutzbar.'),
      ).toBeVisible()
      expect(
        within(await typesTable()).getByRole('switch', { name: 'Nur über Zugänge für Nextcloud' }),
      ).toBeChecked()
    },
    20000,
  )

  it('names what the requirement does not cover for an RSS feed', async () => {
    const coverage =
      'Die Pflicht legt nur die Feed-Adresse fest. Detailseiten auf anderen Servern werden weiter abgerufen, aber ohne Zugangsdaten.'
    server.use(
      http.get('/api/v1/admin/connector-types', () =>
        HttpResponse.json([
          { ...NEXTCLOUD_STATE, sourceType: 'RSS_FEED', displayName: 'RSS-Feed' },
        ]),
      ),
      http.get('/api/v1/admin/connector-types/:sourceType/profile-requirement', () =>
        HttpResponse.json({
          state: { ...NEXTCLOUD_STATE, sourceType: 'RSS_FEED', displayName: 'RSS-Feed' },
          switchable: true,
          ownAddressLibraries: [],
          coverageNotice: coverage,
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    await user.click(
      within(await typesTable()).getByRole('switch', { name: 'Nur über Zugänge für RSS-Feed' }),
    )
    const dialog = await screen.findByRole('dialog', { name: /Nur über Zugänge/ })
    expect(await within(dialog).findByTestId('profile-requirement-coverage')).toHaveTextContent(
      coverage,
    )
    expect(
      within(dialog).getByText('Keine Bibliothek dieser Quellart nutzt eine eigene Adresse.'),
    ).toBeVisible()
  })

  it('changes the choice for the stock while the requirement holds', async () => {
    const required = { ...NEXTCLOUD_STATE, profileRequired: true, ownAddressStock: 'RUNS' }
    server.use(http.get('/api/v1/admin/connector-types', () => HttpResponse.json([required])))
    const sent = captureRequirementPuts()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const types = await typesTable()
    expect(within(types).getByText(/laufen weiter, ihre Adresse ist eingefroren/)).toBeVisible()
    await user.click(
      within(types).getByRole('button', { name: 'Bestandswahl für Nextcloud ändern' }),
    )
    const dialog = await screen.findByRole('dialog', { name: /Bestand von Nextcloud/ })
    expect(await within(dialog).findByRole('radio', { name: /Weiterlaufen lassen/ })).toBeChecked()
    await user.click(within(dialog).getByRole('radio', { name: /Sperren – sie laufen nicht mehr/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Wahl speichern' }))

    await waitFor(() => expect(sent).toEqual([{ required: true, ownAddressStock: 'LOCKED' }]))
  })

  it('switches the requirement off only after a confirmation', async () => {
    const required = { ...NEXTCLOUD_STATE, profileRequired: true, ownAddressStock: 'LOCKED' }
    server.use(http.get('/api/v1/admin/connector-types', () => HttpResponse.json([required])))
    const sent = captureRequirementPuts()
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfileManagementPage />)

    const toggle = within(await typesTable()).getByRole('switch', {
      name: 'Nur über Zugänge für Nextcloud',
    })
    expect(toggle).toBeChecked()
    await user.click(toggle)
    await answerConfirm(
      user,
      /„Nur über Zugänge“ für die Quellart „Nextcloud“ ausschalten\?/,
      'Abbrechen',
    )
    expect(sent).toEqual([])

    await user.click(toggle)
    const question = await screen.findByRole('dialog', { name: /ausschalten\?/ })
    expect(question).toHaveTextContent('ohne Neueinrichtung weiter')
    await answerConfirm(user, /ausschalten\?/, 'Ausschalten')
    await waitFor(() => expect(sent).toEqual([{ required: false }]))
  })
})
