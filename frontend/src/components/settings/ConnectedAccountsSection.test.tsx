import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { server } from '../../mocks/server'
import { leaveFor } from '../../services/leaveApp'
import { answerConfirm, renderWithProviders, waitForDialogClosed } from '../../test/test-utils'
import type {
  ConnectedAccount,
  ConnectedAccountConnectRequest,
  ConnectedAccountsOverview,
} from '../../types/api'
import ConnectedAccountsSection from './ConnectedAccountsSection'

vi.mock('../../services/leaveApp', () => ({ leaveFor: vi.fn() }))

const ME = '/api/v1/me/connected-accounts'
const AUTHORIZATIONS = '/api/v1/connections/authorizations'

const OAUTH_PROFILE = {
  profileId: 'dropbox',
  name: 'Zugang Dropbox',
  sourceType: 'DROPBOX',
  authMethod: 'OAUTH' as const,
  secretForm: null,
}

afterEach(() => {
  vi.mocked(leaveFor).mockReset()
})

function account(overrides: Partial<ConnectedAccount>): ConnectedAccount {
  return {
    profileId: 'profile-1',
    profileName: 'Zugang Nextcloud intern',
    sourceType: 'NEXTCLOUD',
    authMethod: 'PERSONAL_SECRET',
    secretForm: 'USERNAME_AND_PASSWORD',
    state: 'CONNECTED',
    accountLabel: 'avogt',
    released: true,
    reconnectable: true,
    notice: null,
    responsible: null,
    connectedAt: '2026-09-20T08:00:00Z',
    reconnectedAt: null,
    usedBy: [],
    ...overrides,
  }
}

const MISSING = {
  responsible: 'Systemverwaltung',
  text: 'Zugänge für verbundene Konten legt die Systemverwaltung an und gibt sie frei.',
}

function serve(overview: ConnectedAccountsOverview) {
  server.use(http.get(ME, () => HttpResponse.json(overview)))
}

function renderSection() {
  return renderWithProviders(<ConnectedAccountsSection />, { withRouter: true })
}

function errorBody(status: number, error: string, code?: string) {
  return HttpResponse.json({ error, status, timestamp: '2026-10-04T00:00:00Z', code }, { status })
}

describe('ConnectedAccountsSection', () => {
  it('shows every state with its notice, who is in charge and the libraries using it', async () => {
    serve({
      accounts: [
        account({
          profileId: 'connected',
          profileName: 'Zugang verbunden',
          usedBy: [{ id: 'lib-1', name: 'Meine Ablage' }],
        }),
        account({
          profileId: 'expired',
          profileName: 'Zugang abgelaufen',
          state: 'EXPIRED',
          notice: 'Abgelaufen – bitte neu verbinden. Bis dahin wird der Inhalt nicht aktualisiert.',
        }),
        account({
          profileId: 'disconnected',
          profileName: 'Zugang getrennt',
          state: 'DISCONNECTED',
          usedBy: [{ id: 'lib-2', name: 'Projektordner' }],
        }),
        account({
          profileId: 'unreleased',
          profileName: 'Zugang Partner',
          released: false,
          notice: 'Nicht mehr freigegeben – die Verbindung läuft weiter.',
          responsible: 'Systemverwaltung',
        }),
        account({
          profileId: 'locked',
          profileName: 'Zugang Archiv',
          reconnectable: false,
          notice:
            'Der Zugang ist gesperrt; neu verbinden ist erst nach Aufhebung der Sperre möglich.',
          responsible: 'Systemverwaltung',
        }),
      ],
      connectable: [],
      missingAccess: MISSING,
    })
    renderSection()

    const connected = await screen.findByTestId('connected-account-connected')
    expect(within(connected).getByText('Verbunden')).toBeInTheDocument()
    expect(within(connected).getByRole('link', { name: 'Meine Ablage' })).toHaveAttribute(
      'href',
      '/libraries/lib-1',
    )
    expect(within(connected).getByText(/Konto: avogt/)).toBeInTheDocument()

    const expired = screen.getByTestId('connected-account-expired')
    expect(within(expired).getByText('Abgelaufen')).toBeInTheDocument()
    expect(within(expired).getByRole('button', { name: /Konto neu verbinden/ })).toBeInTheDocument()

    const disconnected = screen.getByTestId('connected-account-disconnected')
    expect(within(disconnected).getByText('Getrennt')).toBeInTheDocument()
    expect(disconnected).toHaveTextContent('· getrennt')
    expect(disconnected).not.toHaveTextContent('verbunden seit')
    expect(
      within(disconnected).getByRole('button', { name: 'Konto neu verbinden: Zugang getrennt' }),
    ).toHaveTextContent('Neu verbinden')
    expect(within(disconnected).queryByRole('button', { name: /trennen/ })).not.toBeInTheDocument()

    const unreleased = screen.getByTestId('connected-account-unreleased')
    expect(within(unreleased).getByText('Nicht mehr freigegeben')).toBeInTheDocument()
    expect(within(unreleased).getByTestId('connected-account-notice')).toHaveTextContent(
      'Nicht mehr freigegeben – die Verbindung läuft weiter. Zuständig: Systemverwaltung.',
    )

    const locked = screen.getByTestId('connected-account-locked')
    expect(within(locked).getByText('Zugang gesperrt')).toBeInTheDocument()
    expect(within(locked).queryByRole('button', { name: /verbinden/ })).not.toBeInTheDocument()
    expect(
      within(locked).getByRole('button', { name: 'Verbindung trennen: Zugang Archiv' }),
    ).toBeInTheDocument()
  })

  it('explains connected accounts neutrally when there is none, and answers "Warum fehlt mein Zugang?"', async () => {
    serve({ accounts: [], connectable: [], missingAccess: MISSING })
    renderSection()

    expect(await screen.findByText(/Sie haben kein Konto verbunden/)).toBeInTheDocument()
    expect(screen.getByText(/alles andere in OPAA funktioniert ohne/)).toBeInTheDocument()
    // no source type of the mock offers personal accounts, as no shipped connector does today
    expect(await screen.findByText(/noch keine Quellart verbundene Konten an/)).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Warum fehlt mein Zugang?' })).toBeInTheDocument()
    expect(screen.getByText(MISSING.text)).toBeInTheDocument()
    expect(screen.getByText('Zuständig: Systemverwaltung')).toBeInTheDocument()
  })

  it('does not claim a missing connector once a source type offers personal accounts', async () => {
    serve({ accounts: [], connectable: [], missingAccess: MISSING })
    server.use(
      http.get('/api/v1/source-types', () =>
        HttpResponse.json([
          {
            type: 'NEXTCLOUD',
            displayName: 'Nextcloud',
            indexingRun: true,
            uploads: false,
            pushIntake: false,
            browsable: false,
            profileSupport: 'OPTIONAL',
            profileRequired: false,
            signIns: [
              {
                method: 'PERSONAL_SECRET',
                ownerships: ['LIBRARY', 'PERSON'],
                secretForm: 'USERNAME_AND_PASSWORD',
              },
            ],
            profileDefaults: [],
            serverAddress: { schemes: ['https'] },
            creatable: true,
            creatableWithOwnAddress: true,
            locked: false,
          },
        ]),
      ),
    )
    renderSection()

    expect(
      await screen.findByText(
        'Derzeit gibt es keinen weiteren Zugang, auf dem Sie ein Konto verbinden können.',
      ),
    ).toBeInTheDocument()
    expect(screen.queryByText(/noch keine Quellart/)).not.toBeInTheDocument()
  })

  it('names the source type of every account and of every profile to connect', async () => {
    serve({
      accounts: [account({})],
      connectable: [{ ...OAUTH_PROFILE }],
      missingAccess: MISSING,
    })
    server.use(
      http.get('/api/v1/source-types', () =>
        HttpResponse.json([
          {
            type: 'NEXTCLOUD',
            displayName: 'Nextcloud',
            indexingRun: true,
            uploads: false,
            pushIntake: false,
            browsable: false,
            profileSupport: 'OPTIONAL',
            profileRequired: false,
            signIns: [],
            profileDefaults: [],
            serverAddress: { schemes: ['https'] },
            creatable: true,
            creatableWithOwnAddress: true,
            locked: false,
          },
        ]),
      ),
    )
    renderSection()

    const item = await screen.findByTestId('connected-account-profile-1')
    await waitFor(() => expect(item).toHaveTextContent('Quellart: Nextcloud'))
    // a type this installation does not describe is named by its key
    expect(screen.getByText(/Quellart: DROPBOX/)).toBeInTheDocument()
  })

  it('connects a new account with the generic token form and clears the secret', async () => {
    let sent: ConnectedAccountConnectRequest | null = null
    serve({
      accounts: [],
      connectable: [
        {
          profileId: 'opendesk',
          name: 'Zugang openDesk',
          sourceType: 'NEXTCLOUD',
          authMethod: 'PERSONAL_SECRET',
          secretForm: 'TOKEN',
        },
      ],
      missingAccess: MISSING,
    })
    server.use(
      http.put(`${ME}/opendesk`, async ({ request }) => {
        sent = (await request.json()) as ConnectedAccountConnectRequest
        return HttpResponse.json(
          account({ profileId: 'opendesk', profileName: 'Zugang openDesk', secretForm: 'TOKEN' }),
        )
      }),
    )
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: 'Konto verbinden: Zugang openDesk' }),
    )
    const dialog = screen.getByRole('dialog', { name: /Konto verbinden: Zugang openDesk/ })
    // says honestly who sees what - the audit sees the connection under a pseudonym
    expect(dialog).toHaveTextContent('Die Systemverwaltung sieht nur gerundete Anzahlen')
    expect(within(dialog).getByText(/im Verbindungsprotokoll der Revision/)).toHaveTextContent(
      'unter einem Pseudonym',
    )
    expect(dialog).not.toHaveTextContent('Nur Sie sehen, dass')
    expect(within(dialog).queryByLabelText(/Benutzername/)).not.toBeInTheDocument()
    const secret = within(dialog).getByLabelText(/App-Passwort oder Token/)
    expect(secret).toHaveAttribute('type', 'password')
    expect(secret).toHaveAttribute('autocomplete', 'new-password')
    expect(secret).toHaveValue('')
    await user.click(secret)
    await user.paste('geheim-123')
    await user.click(within(dialog).getByRole('button', { name: 'Verbinden' }))

    await waitForDialogClosed()
    expect(sent).toEqual({ username: null, secret: 'geheim-123' })
    expect(
      await screen.findByText('Ihr Konto ist mit „Zugang openDesk“ verbunden.'),
    ).toBeInTheDocument()
    expect(screen.queryByDisplayValue('geheim-123')).not.toBeInTheDocument()
  })

  it('names a rejected sign-in (400) in German, keeps the user name and empties the secret field', async () => {
    serve({
      accounts: [],
      connectable: [
        {
          profileId: 'nextcloud',
          name: 'Zugang Nextcloud intern',
          sourceType: 'NEXTCLOUD',
          authMethod: 'PERSONAL_SECRET',
          secretForm: 'USERNAME_AND_PASSWORD',
        },
      ],
      missingAccess: MISSING,
    })
    server.use(
      http.put(`${ME}/nextcloud`, () =>
        errorBody(400, 'Die Anmeldung beim Zugang „Zugang Nextcloud intern“ ist fehlgeschlagen.'),
      ),
    )
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: /Konto verbinden: Zugang Nextcloud/ }),
    )
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByLabelText(/Benutzername/))
    await user.paste('avogt')
    await user.click(within(dialog).getByLabelText(/Passwort oder App-Passwort/))
    await user.paste('falsch')
    await user.click(within(dialog).getByRole('button', { name: 'Verbinden' }))

    const alert = await within(dialog).findByRole('alert')
    expect(alert).toHaveTextContent(
      'Die Anmeldung wurde nicht angenommen: Die Anmeldung beim Zugang „Zugang Nextcloud intern“ ist fehlgeschlagen.',
    )
    expect(alert).not.toHaveTextContent('falsch')
    expect(within(dialog).getByLabelText(/Benutzername/)).toHaveValue('avogt')
    expect(within(dialog).getByLabelText(/Passwort oder App-Passwort/)).toHaveValue('')
  })

  it.each([
    ['CAPABILITY_REQUIRED', /nicht freigegeben/],
    ['CONNECTOR_LOCKED', /Der Zugang ist gesperrt/],
  ])('explains a refusal 403 %s', async (code, text) => {
    serve({
      accounts: [],
      connectable: [
        {
          profileId: 'opendesk',
          name: 'Zugang openDesk',
          sourceType: 'NEXTCLOUD',
          authMethod: 'PERSONAL_SECRET',
          secretForm: 'TOKEN',
        },
      ],
      missingAccess: MISSING,
    })
    server.use(http.put(`${ME}/opendesk`, () => errorBody(403, 'Forbidden', code)))
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: 'Konto verbinden: Zugang openDesk' }),
    )
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByLabelText(/App-Passwort oder Token/))
    await user.paste('geheim')
    await user.click(within(dialog).getByRole('button', { name: 'Verbinden' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(text)
  })

  it('asks to connect again when the profile changed during the sign-in (409)', async () => {
    serve({
      accounts: [],
      connectable: [
        {
          profileId: 'opendesk',
          name: 'Zugang openDesk',
          sourceType: 'NEXTCLOUD',
          authMethod: 'PERSONAL_SECRET',
          secretForm: 'TOKEN',
        },
      ],
      missingAccess: MISSING,
    })
    server.use(
      http.put(`${ME}/opendesk`, () =>
        errorBody(
          409,
          'Der Zugang „Zugang openDesk“ wurde geändert, während Sie sich angemeldet haben. Bitte verbinden Sie erneut.',
          'CONNECTED_ACCOUNT_PROFILE_CHANGED',
        ),
      ),
    )
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: 'Konto verbinden: Zugang openDesk' }),
    )
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByLabelText(/App-Passwort oder Token/))
    await user.paste('geheim')
    await user.click(within(dialog).getByRole('button', { name: 'Verbinden' }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'wurde geändert, während Sie sich angemeldet haben. Bitte verbinden Sie erneut.',
    )
  })

  it('reconnects an expired account with the user name entered last time', async () => {
    let sent: ConnectedAccountConnectRequest | null = null
    serve({
      accounts: [account({ state: 'EXPIRED' })],
      connectable: [],
      missingAccess: MISSING,
    })
    server.use(
      http.put(`${ME}/profile-1`, async ({ request }) => {
        sent = (await request.json()) as ConnectedAccountConnectRequest
        return HttpResponse.json(account({ reconnectedAt: '2026-10-04T08:00:00Z' }))
      }),
    )
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: 'Konto neu verbinden: Zugang Nextcloud intern' }),
    )
    const dialog = screen.getByRole('dialog', { name: /Konto neu verbinden/ })
    expect(within(dialog).getByLabelText(/Benutzername/)).toHaveValue('avogt')
    await user.click(within(dialog).getByLabelText(/Passwort oder App-Passwort/))
    await user.paste('neu-123')
    await user.click(within(dialog).getByRole('button', { name: 'Neu verbinden' }))

    await waitForDialogClosed()
    expect(sent).toEqual({ username: 'avogt', secret: 'neu-123' })
  })

  it('disconnects after a confirmation that says what happens to the libraries', async () => {
    let deleted = false
    serve({
      accounts: [account({ usedBy: [{ id: 'lib-1', name: 'Meine Ablage' }] })],
      connectable: [],
      missingAccess: MISSING,
    })
    server.use(
      http.delete(`${ME}/profile-1`, () => {
        deleted = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: 'Verbindung trennen: Zugang Nextcloud intern' }),
    )
    const confirm = await screen.findByRole('dialog', {
      name: 'Verbindung zu „Zugang Nextcloud intern“ trennen?',
    })
    expect(confirm).toHaveTextContent('sofort gelöscht')
    expect(confirm).toHaveTextContent(
      'Ihre Bibliothek „Meine Ablage“ ruht danach: Ihr Inhalt bleibt durchsuchbar',
    )
    await answerConfirm(user, 'Verbindung zu „Zugang Nextcloud intern“ trennen?', 'Trennen')

    await waitFor(() => expect(deleted).toBe(true))
  })

  it('sends one disconnection only, and takes a 404 of the second tab as done', async () => {
    let calls = 0
    serve({ accounts: [account({})], connectable: [], missingAccess: MISSING })
    server.use(
      http.delete(`${ME}/profile-1`, async () => {
        calls += 1
        await new Promise((resolve) => setTimeout(resolve, 50))
        return errorBody(404, 'Keine Verbindung zu diesem Zugang')
      }),
    )
    const user = userEvent.setup()
    renderSection()

    const button = await screen.findByRole('button', {
      name: 'Verbindung trennen: Zugang Nextcloud intern',
    })
    await user.click(button)
    await answerConfirm(user, /trennen\?/, 'Trennen')
    await waitFor(() => expect(button).toHaveAttribute('aria-busy', 'true'))
    await user.click(button)

    expect(
      await screen.findByText('Die Verbindung zu „Zugang Nextcloud intern“ ist getrennt.'),
    ).toBeInTheDocument()
    expect(screen.queryByText('Keine Verbindung zu diesem Zugang')).not.toBeInTheDocument()
    expect(calls).toBe(1)
  })

  it('tells before disconnecting an unreleased connection that no new one is possible', async () => {
    serve({
      accounts: [account({ released: false })],
      connectable: [],
      missingAccess: MISSING,
    })
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: 'Verbindung trennen: Zugang Nextcloud intern' }),
    )
    expect(await screen.findByRole('dialog', { name: /trennen\?/ })).toHaveTextContent(
      'können Sie danach hier kein Konto mehr verbinden',
    )
    await answerConfirm(user, /trennen\?/, 'Abbrechen')
  })

  it('connects an OAuth account at the provider after saying where it goes and what is logged', async () => {
    let started: unknown = null
    serve({ accounts: [], connectable: [OAUTH_PROFILE], missingAccess: MISSING })
    server.use(
      http.post(AUTHORIZATIONS, async ({ request }) => {
        started = await request.json()
        return HttpResponse.json({
          authorizationUrl: 'https://provider.example/authorize?state=s',
          expiresAt: '2026-10-04T12:10:00Z',
        })
      }),
    )
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: 'Konto beim Anbieter verbinden: Zugang Dropbox' }),
    )
    const dialog = await screen.findByRole('dialog', {
      name: /Konto bei „Zugang Dropbox“ verbinden/,
    })
    expect(dialog).toHaveTextContent('zur Anmeldung beim Anbieter weitergeleitet')
    expect(dialog).toHaveTextContent('unter einem Pseudonym')
    await answerConfirm(user, /Konto bei „Zugang Dropbox“ verbinden/, 'Weiter zum Anbieter')

    await waitFor(() =>
      expect(leaveFor).toHaveBeenCalledWith('https://provider.example/authorize?state=s'),
    )
    expect(started).toEqual({ profileId: 'dropbox', purpose: 'ACCOUNT' })
  })

  it.each([
    [
      409,
      'Für die Anmeldung beim Anbieter fehlt die öffentliche Adresse von OPAA (OPAA_PUBLIC_BASE_URL). Zuständig ist die Systemverwaltung.',
      'PUBLIC_BASE_URL_MISSING',
    ],
    [
      429,
      'Sie haben in den letzten Minuten bereits 10 Anmeldungen beim Anbieter begonnen. Bitte versuchen Sie es später erneut.',
      undefined,
    ],
  ])('stays when the consent cannot start (%i) and says why', async (status, message, code) => {
    serve({ accounts: [], connectable: [OAUTH_PROFILE], missingAccess: MISSING })
    server.use(http.post(AUTHORIZATIONS, () => errorBody(status, message, code)))
    const user = userEvent.setup()
    renderSection()

    await user.click(
      await screen.findByRole('button', { name: 'Konto beim Anbieter verbinden: Zugang Dropbox' }),
    )
    await answerConfirm(user, /verbinden\?/, 'Weiter zum Anbieter')

    expect(await screen.findByText(message)).toBeInTheDocument()
    expect(leaveFor).not.toHaveBeenCalled()
    expect(
      screen.getByRole('button', { name: 'Konto beim Anbieter verbinden: Zugang Dropbox' }),
    ).toBeEnabled()
  })

  it('reconnects an OAuth account at the provider and flags a consent that ends soon', async () => {
    const soon = new Date(Date.now() + 5 * 24 * 60 * 60 * 1000).toISOString()
    const later = new Date(Date.now() + 60 * 24 * 60 * 60 * 1000).toISOString()
    serve({
      accounts: [
        account({
          profileId: 'dropbox',
          profileName: 'Zugang Dropbox',
          authMethod: 'OAUTH',
          secretForm: null,
          accountLabel: null,
          expiresAt: soon,
        }),
        account({
          profileId: 'box',
          profileName: 'Zugang Box',
          authMethod: 'OAUTH',
          secretForm: null,
          accountLabel: null,
          expiresAt: later,
        }),
      ],
      connectable: [],
      missingAccess: MISSING,
    })
    server.use(
      http.post(AUTHORIZATIONS, () =>
        HttpResponse.json({
          authorizationUrl: 'https://provider.example/authorize?state=r',
          expiresAt: '2026-10-04T12:10:00Z',
        }),
      ),
    )
    const user = userEvent.setup()
    renderSection()

    const dropbox = await screen.findByTestId('connected-account-dropbox')
    expect(within(dropbox).getByText(/^Zustimmung endet am/)).toBeInTheDocument()
    expect(dropbox).not.toHaveTextContent('lässt sich hier noch nicht neu verbinden')
    const box = screen.getByTestId('connected-account-box')
    expect(box).toHaveTextContent('Zustimmung gültig bis')
    expect(within(box).queryByText(/^Zustimmung endet am/)).not.toBeInTheDocument()

    await user.click(
      within(dropbox).getByRole('button', {
        name: 'Konto beim Anbieter neu verbinden: Zugang Dropbox',
      }),
    )
    await answerConfirm(user, /neu verbinden\?/, 'Weiter zum Anbieter')
    await waitFor(() =>
      expect(leaveFor).toHaveBeenCalledWith('https://provider.example/authorize?state=r'),
    )
  })

  it('tells a failed load apart from an empty list', async () => {
    server.use(http.get(ME, () => errorBody(500, 'Interner Fehler')))
    renderSection()

    expect(await screen.findByText('Interner Fehler')).toBeInTheDocument()
    expect(screen.queryByText(/Sie haben kein Konto verbunden/)).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Erneut laden' })).toBeInTheDocument()
  })
})
