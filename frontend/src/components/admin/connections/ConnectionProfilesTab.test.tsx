import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { mockSourceTypes } from '../../../mocks/libraryFixtures'
import { server } from '../../../mocks/server'

import { mockConnectionProfiles } from '../../../mocks/connectionProfileFixtures'
import { answerConfirm, renderWithProviders, waitForDialogClosed } from '../../../test/test-utils'
import { useAuthStore } from '../../../stores/authStore'
import type { ConnectionProfileUpdateRequest, SourceTypeDescriptor } from '../../../types/api'
import ConnectionProfilesTab from './ConnectionProfilesTab'
import { openProfile, useDesktopViewport } from './connectionTestUtils'

/** Chooses the source type of a new profile in the select of the dialog. */
async function chooseSourceType(
  user: ReturnType<typeof userEvent.setup>,
  dialog: HTMLElement,
  name: RegExp,
) {
  await user.click(within(dialog).getByRole('combobox', { name: /Quellart/ }))
  await user.click(await screen.findByRole('option', { name }))
}

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
    type: 'FILESYSTEM',
    displayName: 'Dateisystem',
    indexingRun: true,
    uploads: false,
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
  const panel = await openProfile(user, PROFILE)
  await user.click(within(panel).getByRole('button', { name: `${PROFILE} bearbeiten` }))
  return screen.findByRole('dialog', { name: `„${PROFILE}“ bearbeiten` })
}

describe('ConnectionProfilesTab', () => {
  useDesktopViewport()

  beforeEach(() => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.get('/api/v1/source-types', () => HttpResponse.json([...WITHOUT_PROFILES, NEXTCLOUD])),
    )
  })

  afterEach(() => {
    server.events.removeAllListeners()
  })

  it('opens the panel of the profile a link points at, e.g. from a notification', async () => {
    renderWithProviders(<ConnectionProfilesTab />, {
      withRouter: true,
      initialRoute: `/admin/connections/access?profile=${mockConnectionProfiles[0].id}`,
    })

    const panel = await screen.findByRole('dialog', { name: PROFILE })
    expect(within(panel).getByText('https://cloud.rheinfurt.example')).toBeVisible()
  })

  it('opens a profile nobody may use to all accounts in its panel, with the API of the Anlegerechte', async () => {
    const grants: string[] = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.includes('/admin/capabilities/')) {
        const body = (await request.clone().json()) as { subjectType: string; scope?: string }
        grants.push(`${body.subjectType} @${body.scope}`)
      }
    })
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const panel = await openProfile(user, PROFILE)
    expect(within(panel).getByText('Noch für niemanden freigegeben')).toBeVisible()
    await user.click(within(panel).getByRole('button', { name: 'Festlegen, wer ihn nutzen darf' }))
    await user.click(within(panel).getByLabelText('Alle Konten'))
    await user.click(within(panel).getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(grants).toEqual(['ALL_ACCOUNTS @PROFILE:connection-profile-nextcloud']),
    )
  })

  // Review #2515, finding 1: the summary must not call a state good that it could not load.
  const RELEASED_OVERVIEW = [
    {
      capability: 'CREATE_CONNECTOR_LIBRARY',
      scope: 'PROFILE:connection-profile-nextcloud',
      scopeLabel: 'Zugang Zugang Nextcloud intern',
      label: 'Konnektorbibliotheken anlegen',
      statement: '',
      grants: [
        {
          id: 'grant-all',
          capability: 'CREATE_CONNECTOR_LIBRARY',
          scope: 'PROFILE:connection-profile-nextcloud',
          subjectType: 'ALL_ACCOUNTS',
          subjectId: null,
          subjectName: null,
          grantedByUserId: null,
          createdAt: '2026-10-01T09:00:00Z',
        },
      ],
    },
  ]
  const failing = () => HttpResponse.json({ error: 'Interner Fehler' }, { status: 500 })

  it('shows no summary while the list of profiles failed, and offers to load it again', async () => {
    server.use(http.get('/api/v1/admin/connection-profiles', failing))
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    expect(await screen.findByRole('button', { name: 'Erneut laden' })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Kein Handlungsbedarf' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('region', { name: 'Handlungsbedarf der Zugänge' }),
    ).not.toBeInTheDocument()
  })

  it('calls the state incomplete, not good, while the releases failed', async () => {
    server.use(http.get('/api/v1/admin/capabilities', failing))
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    expect(await screen.findByRole('heading', { name: /Stand unvollständig/ })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Kein Handlungsbedarf' })).not.toBeInTheDocument()
  })

  it('calls the state incomplete, not good, while the resting sources failed', async () => {
    server.use(
      http.get('/api/v1/admin/capabilities', () => HttpResponse.json(RELEASED_OVERVIEW)),
      http.get('/api/v1/admin/source-connections/dormant', failing),
    )
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    expect(await screen.findByRole('heading', { name: /Stand unvollständig/ })).toBeVisible()
    expect(screen.queryByRole('heading', { name: 'Kein Handlungsbedarf' })).not.toBeInTheDocument()
  })

  it('says there is nothing to do once every part is known and nothing is open', async () => {
    server.use(http.get('/api/v1/admin/capabilities', () => HttpResponse.json(RELEASED_OVERVIEW)))
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    expect(await screen.findByRole('heading', { name: 'Kein Handlungsbedarf' })).toBeVisible()
  })

  it('sums up what needs doing above the overview', async () => {
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    expect(
      await screen.findByRole('heading', { name: '1 Zugang braucht Aufmerksamkeit' }),
    ).toBeVisible()
  })

  it('shows the same statements as a list in a narrow window', async () => {
    window.matchMedia = ((query: string) =>
      ({
        matches: false,
        media: query,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
      }) as unknown as MediaQueryList) as typeof window.matchMedia
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const entry = await screen.findByRole('article', { name: PROFILE })
    expect(entry).toHaveTextContent('cloud.rheinfurt.example')
    expect(entry).toHaveTextContent('Noch für niemanden freigegeben')
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  })

  it('offers no dead end while no source type admits profiles', async () => {
    server.use(http.get('/api/v1/source-types', () => HttpResponse.json(WITHOUT_PROFILES)))
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    expect(await screen.findByTestId('no-profile-source-type')).toHaveTextContent(
      'Zugänge stehen zur Verfügung, sobald eine Quellart sie unterstützt.',
    )
    expect(screen.getByRole('button', { name: 'Neuer Zugang' })).toBeDisabled()
  })

  it('lists the profiles with their connector, address and sign-in', async () => {
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const row = await screen.findByRole('row', { name: new RegExp(PROFILE) })
    expect(await within(row).findByText('Nextcloud')).toBeVisible()
    // The host only; the whole address stays in the title and in the panel.
    expect(within(row).getByText('cloud.rheinfurt.example')).toHaveAttribute(
      'title',
      'https://cloud.rheinfurt.example',
    )
    expect(row).toHaveTextContent('Nur Systemverwaltung')
    expect(row).toHaveTextContent('Noch für niemanden freigegeben')

    const panel = await openProfile(userEvent.setup(), PROFILE)
    expect(panel).toHaveTextContent('Persönliches Geheimnis, für Bibliothek')
  })

  it('shows the counts of connected and expired accounts exactly as the API rounds them', async () => {
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      ownership: 'BOTH',
      connectedAccountCount: { count: null, fewerThan: 5 },
      expiredConnectionCount: { count: null, fewerThan: 5 },
    }
    mockConnectionProfiles.push({
      ...mockConnectionProfiles[0],
      id: 'profile-exact',
      name: 'Zugang openDesk',
      connectedAccountCount: { count: 23, fewerThan: null },
      expiredConnectionCount: { count: 7, fewerThan: null },
    })
    mockConnectionProfiles.push({
      ...mockConnectionProfiles[0],
      id: 'profile-withheld',
      name: 'Zugang Partner',
      connectedAccountCount: { count: 9, fewerThan: null },
      expiredConnectionCount: null,
    })
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const counts = async (name: string) => {
      const panel = await openProfile(user, name)
      const text = panel.textContent ?? ''
      await user.click(within(panel).getByRole('button', { name: 'Schließen' }))
      await waitForDialogClosed()
      return text
    }
    expect(await counts(PROFILE)).toContain(
      'Verbundene Kontenweniger als 5Davon abgelaufenweniger als 5',
    )
    expect(await counts('Zugang openDesk')).toContain('Verbundene Konten23Davon abgelaufen7')
    expect(await counts('Zugang Partner')).toContain(
      'Verbundene Konten9Davon abgelaufennicht ausgewiesen',
    )
  })

  it('says a warning is only possible where the expired are not told exactly', async () => {
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      connectedAccountCount: { count: 23, fewerThan: null },
      expiredConnectionCount: { count: 12, fewerThan: null },
      expiredConnectionWarning: true,
    }
    mockConnectionProfiles.push({
      ...mockConnectionProfiles[0],
      id: 'profile-withheld',
      name: 'Zugang Partner',
      connectedAccountCount: { count: 12, fewerThan: null },
      expiredConnectionCount: null,
      expiredConnectionWarning: true,
    })
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const exact = await screen.findByRole('row', { name: new RegExp(PROFILE) })
    expect(within(exact).getByText('Viele Konten abgelaufen')).toBeVisible()
    const withheld = screen.getByRole('row', { name: /Zugang Partner/ })
    expect(within(withheld).getByText('Möglicherweise viele Konten abgelaufen')).toBeVisible()
    expect(within(withheld).queryByText('Viele Konten abgelaufen')).not.toBeInTheDocument()
    // Nothing to fix in the panel: the persons reconnect themselves.
    expect(within(withheld).getByRole('button', { name: 'Details: Zugang Partner' })).toBeVisible()
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
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    expect(await screen.findByText('Secret abgelaufen am 31.03.2020')).toBeVisible()
    expect(screen.getByText('Secret läuft ab am 24.12.2999')).toBeVisible()
  })

  it('preselects the source type when only one admits profiles', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    expect(within(dialog).getByRole('combobox', { name: /Quellart/ })).toHaveTextContent(
      /^Nextcloud$/,
    )
    expect(within(dialog).getByLabelText(/^Name/)).toBeVisible()
  })

  it('creates a profile after choosing the connector in the select; creating needs a complete choice', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    const create = within(dialog).getByRole('button', { name: 'Anlegen' })
    expect(create).toBeDisabled()
    await user.click(within(dialog).getByRole('combobox', { name: /Quellart/ }))
    const fileSystem = await screen.findByRole('option', { name: /Dateisystem/ })
    expect(fileSystem).toHaveAttribute('aria-disabled', 'true')
    expect(fileSystem).toHaveTextContent('keine Zugänge möglich')
    await user.click(await screen.findByRole('option', { name: /Nextcloud/ }))
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Nextcloud Partner')
    await user.type(within(dialog).getByLabelText(/^Server-Adresse/), 'https://partner.example.org')
    expect(create).toBeDisabled()
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'OAuth' }))
    expect(create).toBeDisabled()
    await user.type(within(dialog).getByLabelText(/^Client-ID/), 'opaa')
    await user.type(within(dialog).getByLabelText(/^Client-Secret/), 'geheim')
    await user.click(create)

    // A new profile opens in its panel, where its release is the next step.
    const panel = await screen.findByRole('dialog', { name: 'Zugang Nextcloud Partner' })
    expect(panel).toHaveTextContent('Noch für niemanden freigegeben')
    expect(panel).toHaveTextContent('OAuth')
    expect(panel).not.toHaveTextContent('geheim')
  }, 20000)

  it('shows a field per declared default and sends only the ones set, typed by kind', async () => {
    const user = userEvent.setup()
    const sent = capturePosts()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await chooseSourceType(user, dialog, /Nextcloud/)
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

    await screen.findByRole('dialog', { name: 'Zugang mit Vorgaben' })
    expect(sent).toHaveLength(1)
    // the empty region sets nothing; the yes/no travels as a boolean
    expect(sent[0].connectorSettings).toEqual({ edition: 'EXTERN', pathStyle: false })
  }, 20000)

  it('sends proxy and certificate switch of the profile', async () => {
    const user = userEvent.setup()
    const sent = capturePosts()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await chooseSourceType(user, dialog, /Nextcloud/)
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang mit Proxy')
    await user.type(within(dialog).getByLabelText(/^Server-Adresse/), 'https://cloud.example.org')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'Ohne Anmeldung' }))
    await user.type(within(dialog).getByLabelText(/^Proxy/), ' proxy.example.org:3128 ')
    await user.click(within(dialog).getByLabelText('Zertifikatsprüfung aussetzen'))
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await screen.findByRole('dialog', { name: 'Zugang mit Proxy' })
    expect(sent[0]).toMatchObject({
      sourceProxy: 'proxy.example.org:3128',
      sourceInsecureSsl: true,
    })
  }, 20000)

  it('offers neither proxy nor certificate switch for a file server and sends neither', async () => {
    const smb = mockSourceTypes.find((type) => type.type === 'SMB')!
    server.use(http.get('/api/v1/source-types', () => HttpResponse.json([NEXTCLOUD, smb])))
    const user = userEvent.setup()
    const sent = capturePosts()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await chooseSourceType(user, dialog, /Windows-Dateifreigabe/)
    expect(within(dialog).queryByLabelText(/^Proxy/)).not.toBeInTheDocument()
    expect(within(dialog).queryByLabelText('Zertifikatsprüfung aussetzen')).not.toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Dateiserver')
    await user.type(within(dialog).getByLabelText(/^Server-Adresse/), 'smb://dateiserver.example')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'Persönliches Geheimnis' }))
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await screen.findByRole('dialog', { name: 'Zugang Dateiserver' })
    expect(sent[0]).toMatchObject({ sourceProxy: null, sourceInsecureSsl: false })
  }, 20000)

  it('offers only the ownerships the chosen sign-in admits', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await chooseSourceType(user, dialog, /Nextcloud/)
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
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    await user.click(await screen.findByRole('button', { name: 'Neuer Zugang' }))
    const dialog = await screen.findByRole('dialog', { name: 'Zugang anlegen' })
    await chooseSourceType(user, dialog, /Ablage mit fester Adresse/)
    expect(within(dialog).queryByLabelText(/^Server-Adresse/)).not.toBeInTheDocument()
    expect(within(dialog).getByText(/feste Adresse: https:\/\/api\.ablage\.example/)).toBeVisible()
    expect(within(dialog).queryByText('Vorgaben für jede Bibliothek')).not.toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/^Name/), 'Zugang Ablage')
    await user.click(within(dialog).getByLabelText(/^Anmeldeart/))
    await user.click(await screen.findByRole('option', { name: 'Ohne Anmeldung' }))
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await screen.findByRole('dialog', { name: 'Zugang Ablage' })
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
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

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
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    await user.type(within(dialog).getByLabelText(/^Region/), ' eu-central-1 ')
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))
    await within(dialog).findByTestId('profile-change-preview')
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
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    const name = within(dialog).getByLabelText(/^Name/)
    await user.clear(name)
    await user.type(name, 'Zugang Nextcloud Rathaus')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await screen.findByRole('dialog', { name: 'Zugang Nextcloud Rathaus' })
    expect(sent).toHaveLength(1)
    expect(sent[0].connectorSettings).toEqual({ edition: 'INTERN' })
    // an empty secret field keeps the stored secret: the field is not sent at all
    expect(sent[0]).not.toHaveProperty('clientSecret')
    expect(mockConnectionProfiles[0].clientSecretSet).toBe(true)
  }, 20000)

  it('asks with the question of the server before a change that discards secrets', async () => {
    const user = userEvent.setup()
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    const address = within(dialog).getByLabelText(/^Server-Adresse/)
    await user.clear(address)
    await user.type(address, 'https://cloud-neu.rheinfurt.example')
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))
    await within(dialog).findByTestId('profile-change-preview')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    const question = await screen.findByRole('dialog', { name: /Änderung an/ })
    // word for word what the preview quoted: one text, from the server
    expect(question).toHaveTextContent(
      'Die Änderung verwirft alle Zugangsdaten und Token dieses Zugangs; 2 Verbindungen müssen neu angemeldet werden, die gespeicherten Zugangsdaten von 1 Bibliothek sind neu einzutragen. Bitte bestätigen.',
    )
    await answerConfirm(user, /Änderung an/, 'Bestätigen und speichern')

    expect(await screen.findByText('https://cloud-neu.rheinfurt.example')).toBeVisible()
    expect(sent.at(-1)).toMatchObject({ confirmDiscard: true })
  }, 20000)

  it('previews a change before saving: the libraries reached and what is discarded', async () => {
    const user = userEvent.setup()
    const previews: ConnectionProfileUpdateRequest[] = []
    server.events.on('request:start', async ({ request }) => {
      if (request.method === 'POST' && request.url.endsWith('/impact')) {
        previews.push((await request.clone().json()) as ConnectionProfileUpdateRequest)
      }
    })
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    const address = within(dialog).getByLabelText(/^Server-Adresse/)
    await user.clear(address)
    await user.type(address, 'https://cloud-neu.rheinfurt.example')
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))

    const preview = await within(dialog).findByTestId('profile-change-preview')
    expect(preview).toHaveTextContent('Betroffen: 1 Bibliothek.')
    // a profile for libraries only names no accounts of persons
    expect(preview).not.toHaveTextContent(/Konten von Personen/)
    expect(preview).toHaveTextContent(/nimmt die Änderung für alle Bibliotheken an/)
    // what saving discards, as the server plans it, and the question it will ask
    const discards = within(preview).getByTestId('profile-change-discards')
    expect(discards).toHaveTextContent(
      'Alle Zugangsdaten und Token des Zugangs werden verworfen; 2 Verbindungen müssen neu angemeldet werden.',
    )
    expect(discards).toHaveTextContent(
      'Die gespeicherten Zugangsdaten von 1 Bibliothek werden verworfen und sind neu einzutragen.',
    )
    expect(discards).toHaveTextContent(/Für 1 Bibliothek ändert sich die Konfiguration/)
    expect(within(preview).getByTestId('profile-change-confirmation')).toHaveTextContent(
      'Vor dem Speichern fragt OPAA noch einmal nach: „Die Änderung verwirft alle Zugangsdaten',
    )
    expect(previews).toHaveLength(1)
    expect(previews[0]).toMatchObject({ serverUrl: 'https://cloud-neu.rheinfurt.example' })
    expect(sent).toHaveLength(0)

    // a change after the preview withdraws it
    await user.type(within(dialog).getByLabelText(/^Region/), 'eu')
    expect(within(dialog).queryByTestId('profile-change-preview')).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Weiter' })).toBeEnabled()
  }, 20000)

  it('names the connected accounts of persons as the API rounds them', async () => {
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      authMethod: 'NONE',
      ownership: 'BOTH',
    }
    server.use(
      http.post('/api/v1/admin/connection-profiles/:profileId/impact', () =>
        HttpResponse.json({
          connections: 2,
          libraries: 2,
          connectedAccounts: { count: null, fewerThan: 5 },
          rejectedLibraries: 0,
          rejections: [],
          lastForProfileRequirement: false,
        }),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    await user.type(within(dialog).getByLabelText(/^Region/), 'eu')
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))

    const preview = await within(dialog).findByTestId('profile-change-preview')
    expect(preview).toHaveTextContent(
      'Betroffen: 2 Bibliotheken. Verbundene Konten von Personen: weniger als 5.',
    )
  }, 20000)

  it('previews a change of ownership first: without persons every private library is released', async () => {
    mockConnectionProfiles[0] = {
      ...mockConnectionProfiles[0],
      authMethod: 'NONE',
      ownership: 'BOTH',
    }
    const user = userEvent.setup()
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    await user.click(within(dialog).getByLabelText(/^Besitzart/))
    await user.click(await screen.findByRole('option', { name: 'Bibliothek' }))
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))

    const preview = await within(dialog).findByTestId('profile-change-preview')
    expect(sent).toHaveLength(0)
    expect(within(preview).getByTestId('profile-change-private-rejections')).toHaveTextContent(
      'Alle privaten Bibliotheken werden vom Zugang gelöst',
    )
    expect(within(preview).getByTestId('profile-change-discards')).toHaveTextContent(
      'der Zugang nimmt danach keine Konten mehr an',
    )
    expect(preview).not.toHaveTextContent(/verbindet sein Konto neu|für die der Konnektor ablehnt/)
  }, 20000)

  it('names every library whose connector refuses the change and saves nothing', async () => {
    const user = userEvent.setup()
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    await user.type(within(dialog).getByLabelText(/^Proxy/), 'proxy.invalid:8080')
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))

    const refusals = await within(dialog).findByTestId('profile-change-rejections')
    expect(refusals).toHaveTextContent(/lehnt die Änderung für 1 Bibliothek ab/)
    expect(refusals).toHaveTextContent(
      'Verbindung: Der Proxy proxy.invalid:8080 ist nicht erreichbar.',
    )
    expect(
      within(refusals).getByRole('link', { name: 'Abgelehnte Bibliothek 1 öffnen' }),
    ).toHaveAttribute('href', '/libraries/library-on-connection-profile-nextcloud-1')
    expect(within(dialog).getByRole('button', { name: 'Speichern' })).toBeDisabled()
    expect(sent).toHaveLength(0)
  }, 20000)

  it('explains a refusal that only arrives on saving and shows its reasons', async () => {
    let asked = 0
    server.use(
      http.post('/api/v1/admin/connection-profiles/:profileId/impact', () => {
        asked += 1
        return HttpResponse.json({
          connections: 2,
          libraries: 1,
          rejectedLibraries: asked === 1 ? 0 : 1,
          rejections:
            asked === 1
              ? []
              : [
                  {
                    libraryId: 'lib-wiki',
                    category: 'SETTINGS',
                    message: 'Die Edition einer Bibliothek ist nach der Anlage nicht änderbar.',
                  },
                ],
          lastForProfileRequirement: false,
        })
      }),
      http.put('/api/v1/admin/connection-profiles/:profileId', () =>
        HttpResponse.json(
          {
            error: 'Der Konnektor lehnt die Änderung für 1 Bibliothek ab (Einstellungen).',
            status: 400,
            code: 'CONNECTION_PROFILE_CHANGE_REJECTED',
          },
          { status: 400 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    await user.type(within(dialog).getByLabelText(/^Region/), 'eu')
    await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))
    await within(dialog).findByTestId('profile-change-preview')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(
      await within(dialog).findByText(/^Nichts wurde gespeichert: Der Konnektor lehnt/),
    ).toBeVisible()
    expect(await within(dialog).findByTestId('profile-change-rejections')).toHaveTextContent(
      'Einstellungen: Die Edition einer Bibliothek ist nach der Anlage nicht änderbar.',
    )
    expect(within(dialog).getByRole('button', { name: 'Speichern' })).toBeDisabled()
  }, 20000)

  it('saves a mere rename without a preview', async () => {
    const user = userEvent.setup()
    const sent = capturePuts()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    const name = within(dialog).getByLabelText(/^Name/)
    await user.clear(name)
    await user.type(name, 'Zugang Nextcloud Rathaus')
    expect(within(dialog).queryByRole('button', { name: 'Weiter' })).not.toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(sent).toHaveLength(1))
  }, 20000)

  it('asks with the number of connections before disconnecting all of them', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const panel = await openProfile(user, PROFILE)
    await user.click(
      within(panel).getByRole('button', { name: `Alle Verbindungen von ${PROFILE} trennen` }),
    )
    const question = await screen.findByRole('dialog', { name: /Alle Verbindungen von/ })
    expect(question).toHaveTextContent('2 Verbindungen')
    await answerConfirm(user, /Alle Verbindungen von/, 'Alle trennen')

    expect(await screen.findByText('2 Verbindungen getrennt.')).toBeVisible()
  })

  it('tells before the emergency shutdown that persons are notified and reconnect themselves', async () => {
    mockConnectionProfiles[0] = { ...mockConnectionProfiles[0], ownership: 'BOTH' }
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const panel = await openProfile(user, PROFILE)
    await user.click(
      within(panel).getByRole('button', { name: `Alle Verbindungen von ${PROFILE} trennen` }),
    )
    const question = await screen.findByRole('dialog', { name: /Alle Verbindungen von/ })
    expect(question).toHaveTextContent('verbundene Konten von Personen: weniger als 5')
    expect(question).toHaveTextContent(
      'Personen mit verbundenem Konto werden benachrichtigt und müssen ihr Konto selbst neu verbinden.',
    )
    await answerConfirm(user, /Alle Verbindungen von/, 'Abbrechen')
  })

  it('deletes a profile after naming what happens to its libraries', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const panel = await openProfile(user, PROFILE)
    await user.click(within(panel).getByRole('button', { name: `${PROFILE} löschen` }))
    const question = await screen.findByRole('dialog', { name: /löschen\?/ })
    expect(question).toHaveTextContent('Zugang entfernt')
    await answerConfirm(user, /löschen\?/, 'Löschen')

    await waitForDialogClosed()
    expect(await screen.findByText('Es sind noch keine Zugänge angelegt.')).toBeVisible()
  })

  // Spezifikation „Konnektor-Freigabe und Sperre“: die Sperre eines Zugangs mit Rückfrage, die
  // nennt, was mit Läufen und Inhalt geschieht; danach steht der Zugang als „Gesperrt“ in der Liste.
  it('locks a profile after naming what happens to its libraries, and unlocks it again', async () => {
    const user = userEvent.setup()
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const panel = await openProfile(user, PROFILE)
    await user.click(within(panel).getByRole('button', { name: `${PROFILE} sperren` }))
    const question = await screen.findByRole('dialog', { name: /sperren\?/ })
    expect(question).toHaveTextContent('bleibt durchsuchbar')
    await answerConfirm(user, /sperren\?/, 'Sperren')

    expect(await within(panel).findByText('Gesperrt')).toBeVisible()
    await user.click(
      await within(panel).findByRole('button', { name: `Sperre von ${PROFILE} aufheben` }),
    )
    await answerConfirm(user, /aufheben\?/, 'Entsperren')
    expect(await within(panel).findByRole('button', { name: `${PROFILE} sperren` })).toBeVisible()
    expect(within(panel).queryByText('Gesperrt')).not.toBeInTheDocument()
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
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const row = await screen.findByRole('row', { name: new RegExp(PROFILE) })
    expect(await within(row).findByText('Quellart gesperrt')).toBeVisible()
    const panel = await openProfile(user, PROFILE)
    await user.click(within(panel).getByRole('button', { name: `Sperre von ${PROFILE} aufheben` }))
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
    renderWithProviders(<ConnectionProfilesTab />, { withRouter: true })

    const dialog = await openEdit(user)
    expect(within(dialog).getByText('Die Angaben der Quellart werden geladen …')).toBeVisible()
    expect(within(dialog).queryByText(/liegen nicht vor/)).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Speichern' })).toBeDisabled()
  })
})
