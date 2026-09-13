import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { Navigate, Route, Routes } from 'react-router'
import { server } from '../mocks/server'
import {
  LAST_ADMIN_USER_ID,
  MAIL_FAILING_ADDRESS_SUFFIX,
  mockLocalAuthSettings,
  mockLocalUsers,
  setMockLocalAuthSettings,
  setMockLocalUsers,
} from '../mocks/localUserFixtures'
import { resetMockProviderAccounts } from '../mocks/accountFixtures'
import type { LocalAuthSettingsUpdateRequest, LocalUserUpdateRequest } from '../types/api'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { useMailStore } from '../stores/mailStore'
import { useUserAdminStore } from '../stores/userAdminStore'
import UserManagementPage from './UserManagementPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER') {
  useAuthStore.setState({
    mode: 'oidc',
    isAuthenticated: true,
    isLoading: false,
    user: { id: 'mock-user-id', email: 'admin@opaa.local', displayName: 'Admin', systemRole },
    token: null,
    error: null,
    providers: [],
    userManager: null,
    activeProviderId: null,
  })
}

/** jsdom has no matchMedia; the table renders only on a desktop viewport (guidelines 5.3). */
function desktopMatchMedia(query: string): MediaQueryList {
  return {
    matches: query.includes('min-width'),
    media: query,
    onchange: null,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
  } as unknown as MediaQueryList
}

/**
 * The page reads its area from the route (`/admin/users/:tab`), so the tests mount it under the
 * same routes App.tsx declares - including the redirect of the bare path.
 */
function renderPage(route = '/admin/users/accounts') {
  return renderWithProviders(
    <Routes>
      <Route path="/admin/users" element={<Navigate to="/admin/users/accounts" replace />} />
      <Route path="/admin/users/:tab" element={<UserManagementPage />} />
      <Route path="/admin/identity-providers" element={<div>Anbieterverwaltung</div>} />
    </Routes>,
    { withRouter: true, initialRoute: route },
  )
}

const renderAccounts = () => renderPage('/admin/users/accounts')
const renderSettings = () => renderPage('/admin/users/settings')

/** `rowPattern` defaults to the name; the e-mail address disambiguates where a role label collides. */
function rowOf(pattern: string) {
  return screen.getByRole('row', { name: new RegExp(pattern) })
}

async function openRowMenu(
  user: ReturnType<typeof userEvent.setup>,
  name: string,
  rowPattern = name,
) {
  await user.click(
    within(rowOf(rowPattern)).getByRole('button', { name: `Aktionen für „${name}“` }),
  )
  return screen.findByRole('menu')
}

/**
 * Die Benutzerverwaltung (#1541, #1601, ADR-0033 Entscheidungen 4 und 11): zwei Bereiche, die
 * Liste aller Konten mit Herkunft und typabhängigen Handlungen, der stehende Hinweis zur Auflage,
 * die beiden einmaligen Anzeigen und die Schalter mit Konsequenz-Dialog.
 */
describe('UserManagementPage', () => {
  const originalMatchMedia = window.matchMedia

  beforeAll(() => {
    window.matchMedia = desktopMatchMedia
  })
  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  beforeEach(() => {
    useUserAdminStore.getState().reset()
    useMailStore.getState().reset()
    resetMockProviderAccounts()
  })

  it('shows no user management to an account that is not a system administrator', () => {
    signInAs('USER')
    renderAccounts()
    expect(screen.getByText(/nicht freigegeben/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Konto anlegen/ })).not.toBeInTheDocument()
  })

  it('lands on the accounts area and keeps the switches in the settings area', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()

    expect(await screen.findByRole('table', { name: 'Konten' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Konten' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('switch', { name: 'Lokale Anmeldung aktiv' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('tab', { name: 'Einstellungen' }))
    expect(
      await screen.findByRole('switch', { name: 'Lokale Anmeldung aktiv' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('table', { name: 'Konten' })).not.toBeInTheDocument()
  })

  it('sends the bare path and an unknown area to the accounts area', async () => {
    signInAs('SYSTEM_ADMIN')
    renderPage('/admin/users')
    expect(await screen.findByRole('table', { name: 'Konten' })).toBeInTheDocument()
  })

  it('lists local and provider accounts with their origin, state, activity class and reason', async () => {
    signInAs('SYSTEM_ADMIN')
    renderAccounts()

    const table = await screen.findByRole('table', { name: 'Konten' })
    // local rows: state, marker, activity class, reason - as before #1601
    expect(within(table).getByText('Eingeladen')).toBeInTheDocument()
    expect(within(table).getByText('Gesperrt (Verwalter)')).toBeInTheDocument()
    expect(within(table).getByText('Abgelaufen')).toBeInTheDocument()
    expect(within(table).getByText('Passwortwechsel ausstehend')).toBeInTheDocument()
    expect(within(table).getAllByText('länger als 90 Tage nicht').length).toBeGreaterThan(0)
    expect(within(table).getByText(/Vertretung im Bauamt/)).toBeInTheDocument()
    expect(within(table).getByText('Notanker')).toBeInTheDocument()
    expect(within(rowOf('T. Klein')).getByText('Lokal')).toBeInTheDocument()

    // provider rows: the provider as origin, the lifecycle at the provider, no activity class
    const maria = rowOf('Maria Weber')
    expect(within(maria).getByText('Verzeichnisdienst')).toBeInTheDocument()
    expect(within(maria).getByText('Beim Anbieter')).toBeInTheDocument()
    expect(within(maria).queryByText(/Tage nicht|^nie$|^aktiv$/)).not.toBeInTheDocument()
    expect(within(rowOf('P. Admin')).getByText('Vom Anbieter geführt')).toBeInTheDocument()
    const ohneAnbieter = within(rowOf('Alte Anbieterin'))
    expect(ohneAnbieter.getByText('Kein Anbieter')).toBeInTheDocument()
    // Herkunft und Zustand tragen zwei Aussagen, nicht zweimal dieselbe
    expect(ohneAnbieter.getByText('Anmeldung nicht möglich')).toBeInTheDocument()

    // No activity timestamp, no sort by activity and no export (ADR-0033, Entscheidung 11).
    expect(within(table).queryByRole('button', { name: /Aktivität/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /export/i })).not.toBeInTheDocument()
    expect(screen.getByText(/nur für lokale Konten/)).toBeInTheDocument()
  })

  it('filters by origin and passes the provider type to the API', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    await user.click(screen.getByRole('combobox', { name: 'Herkunft' }))
    await user.click(await screen.findByRole('option', { name: 'Lokal' }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.providerType).toBe('LOCAL'))
    await waitFor(() =>
      expect(
        useUserAdminStore.getState().accounts.every((account) => account.providerType === 'LOCAL'),
      ).toBe(true),
    )
    expect(screen.queryByRole('row', { name: /Maria Weber/ })).not.toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: 'Herkunft' }))
    await user.click(await screen.findByRole('option', { name: 'Alle Anbieter' }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.providerType).toBe('OIDC'))
    expect(await screen.findByRole('row', { name: /Maria Weber/ })).toBeInTheDocument()
    expect(screen.queryByRole('row', { name: /T. Klein/ })).not.toBeInTheDocument()
  })

  it('explains an empty result when a local-only filter meets the provider origin', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    await user.click(screen.getByRole('combobox', { name: 'Herkunft' }))
    await user.click(await screen.findByRole('option', { name: 'Alle Anbieter' }))
    await user.click(screen.getByRole('combobox', { name: 'Zustand' }))
    await user.click(await screen.findByRole('option', { name: 'Eingeladen' }))

    expect(
      await screen.findByText(/Zustand und Auflage gelten nur für lokale Konten/),
    ).toBeInTheDocument()
  })

  it('offers a provider account the role change and points lock and delete at the provider', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Maria Weber')
    expect(within(menu).getByRole('menuitem', { name: /Rolle ändern/ })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(within(menu).queryByRole('menuitem', { name: 'Sperren' })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: 'Löschen' })).not.toBeInTheDocument()
    expect(
      within(menu).getByText(
        /Sperren, Befristen und Löschen erfolgen beim Identitätsanbieter „Verzeichnisdienst“/,
      ),
    ).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: 'Anbieter verwalten' })).toHaveAttribute(
      'href',
      '/admin/identity-providers',
    )
  })

  it('changes the role of a provider account through the one role endpoint', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Maria Weber')
    await user.click(within(menu).getByRole('menuitem', { name: /Rolle ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Rolle von „Maria Weber“ ändern' })
    expect(within(dialog).getByRole('button', { name: 'Speichern' })).toBeDisabled()
    await user.click(within(dialog).getByRole('combobox', { name: 'Rolle' }))
    await user.click(await screen.findByRole('option', { name: 'Revision' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() =>
      expect(within(rowOf('Maria Weber')).getByText('Revision')).toBeInTheDocument(),
    )
    expect(await screen.findByText(/ist jetzt Revision/)).toBeInTheDocument()
  })

  /**
   * Regressionsschutz zum Review von #1601: Der Rollendialog überlebte das Schließen (MUI unmountet
   * nur die Kinder), trug die zuletzt gewählte Rolle in das nächste geöffnete Konto und hatte dort
   * sofort ein aktives „Speichern" — ein Klick hätte SYSTEM_ADMIN an das falsche Konto vergeben.
   */
  it('starts the role dialog fresh for the next account after an abandoned change', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const first = await openRowMenu(user, 'Maria Weber')
    await user.click(within(first).getByRole('menuitem', { name: /Rolle ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Rolle von „Maria Weber“ ändern' })
    await user.click(within(dialog).getByRole('combobox', { name: 'Rolle' }))
    await user.click(await screen.findByRole('option', { name: 'Systemverwaltung' }))
    await user.click(within(dialog).getByRole('button', { name: 'Abbrechen' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())

    const second = await openRowMenu(user, 'Thomas Klein')
    await user.click(within(second).getByRole('menuitem', { name: /Rolle ändern/ }))
    const next = await screen.findByRole('dialog', { name: 'Rolle von „Thomas Klein“ ändern' })
    // Die Auswahl zeigt die Rolle DIESES Kontos, nicht die verworfene des vorherigen …
    expect(within(next).getByRole('combobox', { name: 'Rolle' })).toHaveTextContent('Nutzer')
    // … und ohne eigene Änderung gibt es nichts zu speichern.
    expect(within(next).getByRole('button', { name: 'Speichern' })).toBeDisabled()
  }, 20000)

  it('refuses the role change of an account whose provider manages the roles', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'P. Admin (Partner)', 'p\\.admin@partner\\.example')
    expect(within(menu).getByRole('menuitem', { name: /Rolle ändern/ })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(
      within(menu).getByText(/vom Identitätsanbieter „Partnerportal“ geführt/),
    ).toBeInTheDocument()
  })

  it('shows the guard refusal of the role endpoint as the next step to take', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    server.use(
      http.post('/api/v1/admin/users/:id/role', () =>
        HttpResponse.json(
          { error: 'Abgelehnt', status: 409, code: 'LAST_LOGIN_CAPABLE_ADMIN' },
          { status: 409 },
        ),
      ),
    )
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Maria Weber')
    await user.click(within(menu).getByRole('menuitem', { name: /Rolle ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Rolle von „Maria Weber“ ändern' })
    await user.click(within(dialog).getByRole('combobox', { name: 'Rolle' }))
    await user.click(await screen.findByRole('option', { name: 'Systemverwaltung' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(
      await within(dialog).findByText(/Richten Sie zuerst ein weiteres Systemverwalterkonto/),
    ).toBeInTheDocument()
  })

  it('names the review obligation and jumps into the matching filter of local accounts', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()

    const notice = await screen.findByTestId('local-user-review-notice')
    expect(notice).toHaveTextContent('3 lokale Konten ohne Ablaufdatum')
    expect(notice).toHaveTextContent('1 offene Einladung')
    expect(notice).toHaveTextContent(/regelmäßig zu überprüfen/)

    await user.click(within(notice).getByRole('button', { name: /ohne Ablaufdatum anzeigen/ }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.review).toBe('WITHOUT_EXPIRY'))
    expect(useUserAdminStore.getState().filters.providerType).toBe('LOCAL')
    await waitFor(() =>
      expect(
        useUserAdminStore
          .getState()
          .accounts.every((account) => account.local && !account.local.expiresAt),
      ).toBe(true),
    )

    await user.click(screen.getByRole('button', { name: /Offene Einladungen anzeigen/ }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.status).toBe('INVITED'))
  })

  it('hides the notice once no account is without an expiry date and none is invited', async () => {
    setMockLocalUsers(
      mockLocalUsers
        .filter((account) => account.status !== 'INVITED')
        .map((account) => ({ ...account, expiresAt: '2027-12-31T22:59:59Z' })),
    )
    signInAs('SYSTEM_ADMIN')
    renderAccounts()

    await screen.findByRole('table', { name: 'Konten' })
    await waitFor(() => expect(useUserAdminStore.getState().summary).not.toBeNull())
    expect(screen.queryByTestId('local-user-review-notice')).not.toBeInTheDocument()
  })

  it('creates an account with a generated password and shows it exactly once', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    await user.click(screen.getByRole('button', { name: /Konto anlegen/ }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText(/E-Mail-Adresse/), 'p.neu@stadt.example')
    await user.type(within(dialog).getByLabelText('Anzeigename'), 'P. Neu')
    await user.type(within(dialog).getByLabelText('Anlagegrund'), 'Neueinstellung im Bauamt')
    await user.click(within(dialog).getByRole('radio', { name: /Anfangspasswort/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    const once = await screen.findByTestId('generated-password-value')
    expect(once).toHaveTextContent('Mock-Anfangs-Passwort-7Q2')
    expect(screen.getByText(/erscheint nur einmal/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Übergeben, schließen' }))
    expect(screen.queryByTestId('generated-password-value')).not.toBeInTheDocument()
  }, 20000)

  it('shows the invitation link exactly once when the mail did not go out', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    await user.click(screen.getByRole('button', { name: /Konto anlegen/ }))
    const dialog = await screen.findByRole('dialog')
    await user.type(
      within(dialog).getByLabelText(/E-Mail-Adresse/),
      `extern${MAIL_FAILING_ADDRESS_SUFFIX}`,
    )
    await user.type(within(dialog).getByLabelText('Anzeigename'), 'E. Extern')
    await user.type(within(dialog).getByLabelText('Anlagegrund'), 'Externe Prüfbegleitung')
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    const link = await screen.findByTestId('setup-link-value')
    expect(link).toHaveTextContent('token=')
    expect(screen.getByText(/Der Versand ist fehlgeschlagen/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Übergeben, schließen' }))
    expect(screen.queryByTestId('setup-link-value')).not.toBeInTheDocument()
  }, 20000)

  // regression guard for #1543: MUIs Auswahl rendert eine Anzeige mit role="combobox", die ein
  // <label for> nicht benennt - die E2E-Barrierefreiheitsprüfung des Dialogs fiel darüber
  // (axe aria-input-field-name, serious).
  it('gives the role selector an accessible name', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    await user.click(screen.getByRole('button', { name: /Konto anlegen/ }))
    const dialog = await screen.findByRole('dialog')

    expect(within(dialog).getByRole('combobox', { name: 'Rolle' })).toBeInTheDocument()
  })

  it('refuses the creation reason as a required field before any request', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    await user.click(screen.getByRole('button', { name: /Konto anlegen/ }))
    const dialog = await screen.findByRole('dialog')
    expect(
      within(dialog).getByText(/Nicht hineingehören Angaben zu Gesundheit/),
    ).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/E-Mail-Adresse/), 'ohne.grund@stadt.example')
    await user.type(within(dialog).getByLabelText('Anzeigename'), 'O. Grund')
    expect(within(dialog).getByRole('button', { name: 'Anlegen' })).toBeDisabled()
  }, 20000)

  it('locks and unlocks a local account and updates its row', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'T. Klein')
    await user.click(within(menu).getByRole('menuitem', { name: 'Sperren' }))

    // Die Folge steht im Overlay, bevor gesperrt wird - sie ist die Entscheidungsgrundlage.
    const sperrfrage = await screen.findByRole('dialog', { name: /„T\. Klein“ sperren\?/ })
    expect(sperrfrage).toHaveTextContent('Sitzungen enden sofort')
    await user.click(within(sperrfrage).getByRole('button', { name: 'Sperren' }))

    await waitFor(() =>
      expect(within(rowOf('T. Klein')).getByText('Gesperrt (Verwalter)')).toBeInTheDocument(),
    )

    const lockedMenu = await openRowMenu(user, 'T. Klein')
    expect(within(lockedMenu).queryByRole('menuitem', { name: 'Sperren' })).not.toBeInTheDocument()
    await user.click(within(lockedMenu).getByRole('menuitem', { name: 'Entsperren' }))
    await waitFor(() => expect(within(rowOf('T. Klein')).getByText('Aktiv')).toBeInTheDocument())
  })

  it('offers neither locking nor deletion on the own row and none on the bootstrap account', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Systemverwaltung', 'admin@opaa.local')
    expect(within(menu).getByRole('menuitem', { name: 'Sperren' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(within(menu).getByRole('menuitem', { name: 'Löschen' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
  })

  it('keeps the own expiry date out of the past when editing the own account', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Systemverwaltung', 'admin@opaa.local')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/nicht rückwirkend ablaufen/)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Ablaufdatum')).toHaveAttribute(
      'min',
      expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
    )
  })

  /**
   * Regressionsschutz zu Review-Runde 1 (HIGH 2): Der PATCH sendete `expiresAt` immer mit —
   * auf 23:59:59 Ortszeit zurückgerechnet. Eine reine Namensänderung verschob damit das
   * Ablaufdatum und stand als Fristverschiebung im `LOCAL_USER_CHANGED`-Ereignis, das
   * Vorher/Nachher ausschließlich für `expires_at` führt.
   */
  it('leaves the expiry date out of a PATCH that only renames an account', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const bodies: LocalUserUpdateRequest[] = []
    server.use(
      http.patch('/api/v1/admin/local-users/:id', async ({ params, request }) => {
        const body = (await request.json()) as LocalUserUpdateRequest
        bodies.push(body)
        const stored = mockLocalUsers.find((account) => account.id === String(params.id))!
        return HttpResponse.json({ ...stored, displayName: body.displayName ?? stored.displayName })
      }),
    )
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'T. Klein')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    const name = within(dialog).getByLabelText('Anzeigename')
    await user.clear(name)
    await user.type(name, 'T. Klein-Meier')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0].displayName).toBe('T. Klein-Meier')
    expect(bodies[0]).not.toHaveProperty('expiresAt')
    expect(bodies[0].noExpiry).toBe(false)
    // the renamed row keeps its origin and folds the local row back in
    await waitFor(() => expect(rowOf('T. Klein-Meier')).toBeInTheDocument())
    expect(within(rowOf('T. Klein-Meier')).getByText('Lokal')).toBeInTheDocument()
  }, 20000)

  it('sends the new expiry date when it actually changed', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const bodies: LocalUserUpdateRequest[] = []
    server.use(
      http.patch('/api/v1/admin/local-users/:id', async ({ params, request }) => {
        bodies.push((await request.json()) as LocalUserUpdateRequest)
        return HttpResponse.json(mockLocalUsers.find((a) => a.id === String(params.id))!)
      }),
    )
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'T. Klein')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('checkbox', { name: 'Kein Ablaufdatum' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0].noExpiry).toBe(true)
  }, 20000)

  it('shows the backend reason when a deletion is refused', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'M. Weber (Partner)', 'm.weber@partner.example')
    await user.click(within(menu).getByRole('menuitem', { name: 'Löschen' }))

    const loeschfrage = await screen.findByRole('dialog', { name: /„M\. Weber.*“ löschen\?/ })
    expect(loeschfrage).toHaveTextContent('Sperren ist der Regelweg')
    await user.click(within(loeschfrage).getByRole('button', { name: 'Löschen' }))

    expect(
      await screen.findByText(/in Nachweis- oder Rechtebeständen referenziert/),
    ).toBeInTheDocument()
  })

  it('shows the lockout guard refusal as the next step to take', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })
    expect(mockLocalUsers.some((account) => account.id === LAST_ADMIN_USER_ID)).toBe(true)

    const menu = await openRowMenu(user, 'J. Hoffmann')
    await user.click(within(menu).getByRole('menuitem', { name: 'Sperren' }))
    await answerConfirm(user, /„J\. Hoffmann“ sperren\?/, 'Sperren')

    expect(
      await screen.findByText(/Richten Sie zuerst ein weiteres Systemverwalterkonto/),
    ).toBeInTheDocument()
  })

  it('hands over a reset link once and deletes an account that owns nothing', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const resetMenu = await openRowMenu(user, 'R. Sommer')
    await user.click(within(resetMenu).getByRole('menuitem', { name: /Rücksetz-Link/ }))
    await answerConfirm(user, /Passwort von „R\. Sommer“ zurücksetzen\?/, 'Zurücksetzen')
    await waitFor(() => expect(screen.getByText(/wurde an .* versendet/)).toBeInTheDocument())
    expect(screen.queryByTestId('setup-link-value')).not.toBeInTheDocument()

    const deleteMenu = await openRowMenu(user, 'R. Sommer')
    await user.click(within(deleteMenu).getByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, /„R\. Sommer“ löschen\?/, 'Löschen')
    await waitFor(() => expect(screen.queryByText('R. Sommer')).not.toBeInTheDocument())
  })

  it('asks before the local sign-in is switched off and names the ended sessions', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderSettings()

    await user.click(await screen.findByRole('switch', { name: 'Lokale Anmeldung aktiv' }))

    const abschaltfrage = await screen.findByRole('dialog', {
      name: 'Lokale Anmeldung abschalten?',
    })
    expect(abschaltfrage).toHaveTextContent('Lokale Systemverwalter bleiben angemeldet')
    await user.click(within(abschaltfrage).getByRole('button', { name: 'Abschalten' }))

    expect(await screen.findByText(/haben ihre Sitzung verloren/)).toBeInTheDocument()
    await waitFor(() => expect(useUserAdminStore.getState().settings?.enabled).toBe(false))
  })

  /**
   * Regressionsschutz zu Review-Runde 1 (HIGH 1): Der Schalter speicherte den halb getippten
   * Regel-Entwurf mit — gemessen wurde eine Ablauf-Vorbelegung, die von 365 auf 3 sprang, und ein
   * leeres Zahlenfeld ließ das Umschalten als `NaN` still scheitern.
   */
  it('saves a switch from the server state, not from the half-typed rules draft', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const bodies: LocalAuthSettingsUpdateRequest[] = []
    server.use(
      http.put('/api/v1/admin/local-auth-settings', async ({ request }) => {
        const body = (await request.json()) as LocalAuthSettingsUpdateRequest
        bodies.push(body)
        setMockLocalAuthSettings({ ...mockLocalAuthSettings, ...body })
        return HttpResponse.json(mockLocalAuthSettings)
      }),
    )
    renderSettings()

    const expiryField = await screen.findByLabelText(/Vorbelegtes Ablaufdatum/)
    await user.clear(expiryField)
    await user.type(expiryField, '3')
    const minLength = screen.getByLabelText(/Mindestlänge des Passworts/)
    await user.clear(minLength)

    await user.click(screen.getByRole('switch', { name: 'Passwort vergessen' }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0].passwordResetEnabled).toBe(false)
    expect(bodies[0].defaultExpiryDays).toBe(365)
    expect(bodies[0].passwordMinLength).toBe(12)
    expect(await screen.findByText(/ist abgeschaltet/)).toBeInTheDocument()
  }, 20000)

  it('carries the typed domain list into the self-registration switch', async () => {
    setMockLocalAuthSettings({ ...mockLocalAuthSettings, selfRegistrationAllowedDomains: [] })
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const bodies: LocalAuthSettingsUpdateRequest[] = []
    server.use(
      http.put('/api/v1/admin/local-auth-settings', async ({ request }) => {
        const body = (await request.json()) as LocalAuthSettingsUpdateRequest
        bodies.push(body)
        setMockLocalAuthSettings({ ...mockLocalAuthSettings, ...body })
        return HttpResponse.json(mockLocalAuthSettings)
      }),
    )
    renderSettings()

    await user.type(await screen.findByLabelText(/Adress-Domänen/), 'amt.example')
    await user.click(screen.getByRole('switch', { name: 'Selbstregistrierung' }))
    await answerConfirm(user, 'Selbstregistrierung einschalten?', 'Einschalten')

    await waitFor(() => expect(bodies).toHaveLength(1))
    // Die Domänenliste ist die Vorbedingung genau dieses Schalters und reist deshalb mit.
    expect(bodies[0].selfRegistrationAllowedDomains).toEqual(['amt.example'])
    expect(bodies[0].selfRegistrationEnabled).toBe(true)
  }, 20000)

  /** Nachprüfung N1: Beim **Ausschalten** ist die Domänenliste kein Teil der Handlung. */
  it('leaves the typed domain list out of a self-registration switch-off', async () => {
    setMockLocalAuthSettings({ ...mockLocalAuthSettings, selfRegistrationEnabled: true })
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const bodies: LocalAuthSettingsUpdateRequest[] = []
    server.use(
      http.put('/api/v1/admin/local-auth-settings', async ({ request }) => {
        const body = (await request.json()) as LocalAuthSettingsUpdateRequest
        bodies.push(body)
        setMockLocalAuthSettings({ ...mockLocalAuthSettings, ...body })
        return HttpResponse.json(mockLocalAuthSettings)
      }),
    )
    renderSettings()

    const domains = await screen.findByLabelText(/Adress-Domänen/)
    await user.clear(domains)
    await user.type(domains, 'stadt.exa')

    await user.click(screen.getByRole('switch', { name: 'Selbstregistrierung' }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0].selfRegistrationEnabled).toBe(false)
    expect(bodies[0].selfRegistrationAllowedDomains).toEqual(['stadt.example'])
    // Der halb getippte Eintrag steht weiter im Formular - ein Schalterklick verwirft ihn nicht
    // (Nachprüfung N4).
    expect(screen.getByLabelText(/Adress-Domänen/)).toHaveValue('stadt.exa')
  }, 20000)

  it('asks before self-registration is switched on and refuses it without a domain list', async () => {
    setMockLocalAuthSettings({ ...mockLocalAuthSettings, selfRegistrationAllowedDomains: [] })
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderSettings()

    await user.click(await screen.findByRole('switch', { name: 'Selbstregistrierung' }))
    expect(await screen.findByText(/mindestens eine Adress-Domäne/)).toBeInTheDocument()
    expect(useUserAdminStore.getState().settings?.selfRegistrationEnabled).toBe(false)

    await user.type(screen.getByLabelText(/Adress-Domänen/), 'stadt.example')
    await user.click(screen.getByRole('switch', { name: 'Selbstregistrierung' }))
    const einschaltfrage = await screen.findByRole('dialog', {
      name: 'Selbstregistrierung einschalten?',
    })
    expect(einschaltfrage).toHaveTextContent('öffentlich erreichbares Formular')
    await user.click(within(einschaltfrage).getByRole('button', { name: 'Einschalten' }))
    await waitFor(() =>
      expect(useUserAdminStore.getState().settings?.selfRegistrationEnabled).toBe(true),
    )
  })

  it('locks the two link flows while no public base URL is configured', async () => {
    setMockLocalAuthSettings({
      ...mockLocalAuthSettings,
      publicBaseUrlConfigured: false,
      passwordResetEnabled: false,
    })
    signInAs('SYSTEM_ADMIN')
    renderSettings()

    expect(await screen.findByRole('switch', { name: 'Passwort vergessen' })).toBeDisabled()
    expect(screen.getByRole('switch', { name: 'Selbstregistrierung' })).toBeDisabled()
    expect(screen.getByText(/OPAA_PUBLIC_BASE_URL nicht gesetzt/)).toBeInTheDocument()
  })

  /** Review-Runde 1, MEDIUM 6: Ein Schalter auf „ein" darf nicht behaupten, der Fluss wirke. */
  it('says why a switched-on link flow is currently unreachable', async () => {
    setMockLocalAuthSettings({ ...mockLocalAuthSettings, enabled: false })
    signInAs('SYSTEM_ADMIN')
    renderSettings()

    expect(await screen.findByRole('switch', { name: 'Passwort vergessen' })).toBeChecked()
    expect(
      screen.getByText(/Derzeit nicht erreichbar, weil die lokale Anmeldung abgeschaltet ist/),
    ).toBeInTheDocument()
  })

  it('points at the mail settings when nothing can be sent', async () => {
    signInAs('SYSTEM_ADMIN')
    renderSettings()

    const link = await screen.findByRole('link', { name: 'E-Mail-Einstellungen' })
    expect(link).toHaveAttribute('href', '/admin/mail/server')
    await waitFor(() => expect(screen.getByText(/E-Mail-Versand:/)).toBeInTheDocument())
  })

  it('searches debounced and passes the term to the API', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const requested: string[] = []
    const record = ({ request }: { request: Request }) => {
      const url = new URL(request.url)
      if (url.pathname === '/api/v1/admin/accounts') {
        requested.push(url.searchParams.get('query') ?? '')
      }
    }
    server.events.on('request:start', record)
    // Removed even when an assertion below throws - see the same guard in the provider page test.
    onTestFinished(() => server.events.removeListener('request:start', record))
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    await user.type(screen.getByRole('searchbox', { name: 'Konten suchen' }), 'vogt')

    await waitFor(() => expect(useUserAdminStore.getState().filters.query).toBe('vogt'))
    await waitFor(() =>
      expect(useUserAdminStore.getState().accounts.map((a) => a.displayName)).toEqual(['A. Vogt']),
    )
    // Four keystrokes, one request: the field is debounced (#1541).
    expect(requested.filter((query) => query !== '')).toEqual(['vogt'])
  })

  it('sorts only by an allow-listed field', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    const table = await screen.findByRole('table', { name: 'Konten' })

    await user.click(within(table).getByRole('button', { name: /E-Mail/ }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.sort).toBe('email'))
    expect(useUserAdminStore.getState().error).toBeNull()
  })

  it('sorts by origin, role and state - and by nothing the activity says', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    const table = await screen.findByRole('table', { name: 'Konten' })

    // Herkunft: lokale Konten zuerst, dann die Anbieter nach Namen, zuletzt ein Konto ohne
    // Anbieterzeile - eine Kategorie neben Eigennamen ordnet sich nicht alphabetisch.
    await user.click(within(table).getByRole('button', { name: /Herkunft/ }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.sort).toBe('origin'))
    await waitFor(() => {
      const origins = useUserAdminStore
        .getState()
        .accounts.map((account) =>
          account.providerType === 'LOCAL' ? 'LOKAL' : (account.provider?.displayName ?? 'OHNE'),
        )
      expect(origins[0]).toBe('LOKAL')
      expect(origins.at(-1)).toBe('OHNE')
      // Partnerportal vor Verzeichnisdienst
      expect(origins.indexOf('Partnerportal')).toBeLessThan(origins.indexOf('Verzeichnisdienst'))
    })

    // Rolle: nach Privileg, was zugleich die Reihenfolge der Beschriftungen ist
    await user.click(within(table).getByRole('button', { name: /Rolle/ }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.sort).toBe('role'))
    await waitFor(() => {
      const roles = useUserAdminStore.getState().accounts.map((account) => account.systemRole)
      expect(roles[0]).toBe('USER')
      expect(roles.at(-1)).toBe('SYSTEM_ADMIN')
    })

    // Zustand: was Handlung braucht, zuerst; Anbieterkonten zuletzt, sie tragen keinen
    await user.click(within(table).getByRole('button', { name: /Zustand/ }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.sort).toBe('status'))
    await waitFor(() => {
      const accounts = useUserAdminStore.getState().accounts
      expect(accounts[0].local?.status).toBe('LOCKED')
      expect(accounts.at(-1)?.local).toBeUndefined()
    })

    // Die Aktivität bleibt, was sie ist: eine Klasse ohne Sortierung.
    expect(within(table).queryByRole('button', { name: /Aktivität/ })).not.toBeInTheDocument()
    expect(useUserAdminStore.getState().error).toBeNull()
  }, 20000)

  it('falls back to a card list below tablet width', async () => {
    window.matchMedia = (query: string) =>
      ({ ...desktopMatchMedia(query), matches: false }) as MediaQueryList
    signInAs('SYSTEM_ADMIN')
    renderAccounts()

    expect(await screen.findByRole('article', { name: 'T. Klein' })).toBeInTheDocument()
    const maria = screen.getByRole('article', { name: 'Maria Weber' })
    expect(within(maria).getByText('Verzeichnisdienst')).toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    window.matchMedia = desktopMatchMedia
  })

  it('reports a failed list load as an inline error', async () => {
    server.use(
      http.get('/api/v1/admin/accounts', () =>
        HttpResponse.json({ error: 'Datenbank nicht erreichbar' }, { status: 503 }),
      ),
    )
    signInAs('SYSTEM_ADMIN')
    renderAccounts()

    expect(await screen.findByText('Datenbank nicht erreichbar')).toBeInTheDocument()
  })
})
