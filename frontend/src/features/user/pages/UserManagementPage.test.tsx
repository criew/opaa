import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { Navigate, Route, Routes } from 'react-router'
import { server } from '@/test/server'
import {
  LAST_ADMIN_USER_ID,
  MAIL_FAILING_ADDRESS_SUFFIX,
  mockLocalAuthSettings,
  mockLocalUsers,
  setMockLocalAuthSettings,
  setMockLocalUsers,
} from '../mocks/localUserFixtures'
import { resetMockProviderAccounts } from '../mocks/accountFixtures'
import type { LocalAuthSettingsUpdateRequest, LocalUserUpdateRequest } from '@/shared/api/types'
import { answerConfirm, renderWithProviders, waitForDialogClosed } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import { mockLocalAccount } from '@/shared/mocks/authFixtures'
import {
  RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE,
  markRecentLoginReturn,
} from '@/shared/session/recentLogin'
import { resetProviderReturn } from '@/shared/session/useRecentLoginGate'
import { useMailStore } from '@/features/mail'
import { useUserAdminStore } from '../stores/userAdminStore'
import UserManagementPage from './UserManagementPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER', id = 'mock-user-id') {
  useAuthStore.setState({
    mode: 'oidc',
    isAuthenticated: true,
    isLoading: false,
    user: { id, email: 'admin@opaa.local', displayName: 'Admin', systemRole },
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
    within(rowOf(rowPattern)).getByRole('button', { name: `Weitere Aktionen für „${name}“` }),
  )
  return screen.findByRole('menu')
}

/**
 * The user management (ADR-0033 Entscheidungen 4 and 11): two areas, the
 * list of all accounts with origin and type-dependent actions, the standing hint about the
 * requirement, the two one-time displays and the switches with a consequence dialog.
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
    resetProviderReturn()
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

  it('lists local and provider accounts with their origin and state', async () => {
    signInAs('SYSTEM_ADMIN')
    renderAccounts()

    const table = await screen.findByRole('table', { name: 'Konten' })
    // local rows: state and marker; neither activity class nor creation reason
    expect(within(table).getByText('Eingeladen')).toBeInTheDocument()
    // Only „Gesperrt“ in the cell, the reason behind the info icon
    expect(within(table).getAllByText('Gesperrt').length).toBeGreaterThan(0)
    expect(within(table).queryByText(/Gesperrt (von|nach|wegen)|\(Verwalter\)/)).toBeNull()
    expect(
      within(table).getAllByRole('img', { name: 'Sperrgrund: Von der Verwaltung gesperrt' }).length,
    ).toBeGreaterThan(0)
    expect(within(table).getByText('Abgelaufen')).toBeInTheDocument()
    expect(within(table).getByText('Passwortwechsel ausstehend')).toBeInTheDocument()
    expect(within(table).queryByText(/Tage nicht|^nie$|^aktiv$/)).not.toBeInTheDocument()
    // local accounts without a date are called „unbefristet“, provider accounts carry no expiry
    expect(within(table).getAllByText('unbefristet').length).toBeGreaterThan(0)
    expect(within(rowOf('Maria Weber')).queryByText('unbefristet')).not.toBeInTheDocument()
    expect(within(table).getByRole('columnheader', { name: /Ablauf/ })).not.toHaveTextContent(
      /Aktivität/,
    )
    expect(within(table).queryByText(/Vertretung im Bauamt/)).not.toBeInTheDocument()
    expect(within(table).getByRole('columnheader', { name: /Angelegt/ })).not.toHaveTextContent(
      /Anlagegrund/,
    )
    expect(within(table).getByText('Notanker')).toBeInTheDocument()
    expect(within(rowOf('T. Klein')).getByText('Lokal')).toBeInTheDocument()

    // provider rows: the provider as origin, the lifecycle at the provider
    const maria = rowOf('Maria Weber')
    expect(within(maria).getByText('Verzeichnisdienst')).toBeInTheDocument()
    expect(within(maria).getByText('Beim Anbieter')).toBeInTheDocument()
    expect(within(rowOf('P. Admin')).getByText('Vom Anbieter geführt')).toBeInTheDocument()
    const ohneAnbieter = within(rowOf('Alte Anbieterin'))
    expect(ohneAnbieter.getByText('Kein Anbieter')).toBeInTheDocument()
    // Origin and state carry two statements, not the same one twice
    expect(ohneAnbieter.getByText('Anmeldung nicht möglich')).toBeInTheDocument()

    // No activity timestamp, no sort by activity and no export (ADR-0033, Entscheidung 11).
    expect(within(table).queryByRole('button', { name: /Aktivität/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /export/i })).not.toBeInTheDocument()
    expect(screen.queryByText(/Export dieser Liste/)).not.toBeInTheDocument()
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
   * The role dialog starts anew for every account: MUI only unmounts the children on close, the
   * last chosen role must not wander into the next opened account — an immediately active
   * „Speichern" would grant SYSTEM_ADMIN to the wrong account there with one click.
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
    await waitForDialogClosed()

    const second = await openRowMenu(user, 'Thomas Klein')
    await user.click(within(second).getByRole('menuitem', { name: /Rolle ändern/ }))
    const next = await screen.findByRole('dialog', { name: 'Rolle von „Thomas Klein“ ändern' })
    // The select shows the role of THIS account, not the discarded one of the previous …
    expect(within(next).getByRole('combobox', { name: 'Rolle' })).toHaveTextContent('Nutzer')
    // … and without an own change there is nothing to save.
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

  /**
   * A system role changes only after a recent sign-in: the refusal opens the confirmation, and the
   * change goes through once the person confirmed - without choosing the role a second time.
   */
  it('confirms the sign-in before a role change and then applies it', async () => {
    signInAs('SYSTEM_ADMIN')
    useAuthStore.setState({ sessionKind: 'local', token: 'old-token' })
    const user = userEvent.setup()
    let refusals = 0
    server.use(
      http.post('/api/v1/admin/users/:id/role', () => {
        if (refusals > 0) return undefined
        refusals += 1
        return HttpResponse.json(
          {
            error: 'Bitte bestätigen Sie Ihre Identität.',
            status: 403,
            code: 'RECENT_LOGIN_REQUIRED',
          },
          { status: 403 },
        )
      }),
    )
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Maria Weber')
    await user.click(within(menu).getByRole('menuitem', { name: /Rolle ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Rolle von „Maria Weber“ ändern' })
    await user.click(within(dialog).getByRole('combobox', { name: 'Rolle' }))
    await user.click(await screen.findByRole('option', { name: 'Revision' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    const confirm = await screen.findByRole('dialog', { name: 'Anmeldung bestätigen' })
    expect(within(confirm).getByText(/Rolle/)).toBeInTheDocument()
    await user.type(within(confirm).getByLabelText('Ihr Passwort'), mockLocalAccount.password)
    await user.click(within(confirm).getByRole('button', { name: 'Bestätigen' }))

    await waitFor(() =>
      expect(within(rowOf('Maria Weber')).getByText('Revision')).toBeInTheDocument(),
    )
    expect(refusals).toBe(1)
  }, 20000)

  /**
   * Back from the sign-in at the provider, the next refusal says the provider did not confirm it
   * instead of sending the person to the provider once more; the attempt after that asks again.
   */
  it('tells after a provider sign-in that it was not confirmed, without a loop', async () => {
    signInAs('SYSTEM_ADMIN')
    useAuthStore.setState({ sessionKind: 'oidc', activeProviderId: 'keycloak' })
    markRecentLoginReturn()
    const user = userEvent.setup()
    server.use(
      http.post('/api/v1/admin/users/:id/role', () =>
        HttpResponse.json(
          {
            error: 'Bitte bestätigen Sie Ihre Identität.',
            status: 403,
            code: 'RECENT_LOGIN_REQUIRED',
          },
          { status: 403 },
        ),
      ),
    )
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })
    expect(await screen.findByText(/von Ihrem Anmeldedienst zurück/)).toBeInTheDocument()

    const menu = await openRowMenu(user, 'Maria Weber')
    await user.click(within(menu).getByRole('menuitem', { name: /Rolle ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Rolle von „Maria Weber“ ändern' })
    await user.click(within(dialog).getByRole('combobox', { name: 'Rolle' }))
    await user.click(await screen.findByRole('option', { name: 'Revision' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(
      await within(dialog).findByText(RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE),
    ).toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Anmeldung bestätigen' })).not.toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))
    expect(await screen.findByRole('dialog', { name: 'Anmeldung bestätigen' })).toBeInTheDocument()
  }, 20000)

  /** Leaving the page drops the return: a later visit asks for the confirmation again. */
  it('forgets the return from the provider once the page is left', async () => {
    signInAs('SYSTEM_ADMIN')
    useAuthStore.setState({ sessionKind: 'oidc', activeProviderId: 'keycloak' })
    markRecentLoginReturn()
    const user = userEvent.setup()
    server.use(
      http.post('/api/v1/admin/users/:id/role', () =>
        HttpResponse.json(
          {
            error: 'Bitte bestätigen Sie Ihre Identität.',
            status: 403,
            code: 'RECENT_LOGIN_REQUIRED',
          },
          { status: 403 },
        ),
      ),
    )
    const first = renderAccounts()
    expect(await screen.findByText(/von Ihrem Anmeldedienst zurück/)).toBeInTheDocument()
    first.unmount()

    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })
    const menu = await openRowMenu(user, 'Maria Weber')
    await user.click(within(menu).getByRole('menuitem', { name: /Rolle ändern/ }))
    const dialog = await screen.findByRole('dialog', { name: 'Rolle von „Maria Weber“ ändern' })
    await user.click(within(dialog).getByRole('combobox', { name: 'Rolle' }))
    await user.click(await screen.findByRole('option', { name: 'Revision' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(await screen.findByRole('dialog', { name: 'Anmeldung bestätigen' })).toBeInTheDocument()
    expect(
      within(dialog).queryByText(RECENT_LOGIN_NOT_CONFIRMED_BY_PROVIDER_MESSAGE),
    ).not.toBeInTheDocument()
  }, 20000)

  it('confirms the sign-in before a password is generated for an account', async () => {
    signInAs('SYSTEM_ADMIN')
    useAuthStore.setState({ sessionKind: 'local', token: 'old-token' })
    const user = userEvent.setup()
    let refusals = 0
    server.use(
      http.post('/api/v1/admin/local-users/:id/password', () => {
        if (refusals > 0) return undefined
        refusals += 1
        return HttpResponse.json(
          {
            error: 'Bitte bestätigen Sie Ihre Identität.',
            status: 403,
            code: 'RECENT_LOGIN_REQUIRED',
          },
          { status: 403 },
        )
      }),
    )
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'T. Klein')
    await user.click(within(menu).getByRole('menuitem', { name: 'Passwort erzeugen' }))
    await answerConfirm(user, /Passwort für „T. Klein“ erzeugen/, 'Erzeugen')

    const confirm = await screen.findByRole('dialog', { name: 'Anmeldung bestätigen' })
    await user.type(within(confirm).getByLabelText('Ihr Passwort'), mockLocalAccount.password)
    await user.click(within(confirm).getByRole('button', { name: 'Bestätigen' }))

    expect(await screen.findByTestId('generated-password-value')).toBeInTheDocument()
    expect(refusals).toBe(1)
  }, 20000)

  it('confirms the sign-in before the edit dialog changes a role', async () => {
    signInAs('SYSTEM_ADMIN')
    useAuthStore.setState({ sessionKind: 'local', token: 'old-token' })
    const user = userEvent.setup()
    const bodies: LocalUserUpdateRequest[] = []
    server.use(
      http.patch('/api/v1/admin/local-users/:id', async ({ params, request }) => {
        const body = (await request.json()) as LocalUserUpdateRequest
        bodies.push(body)
        if (bodies.length === 1) {
          return HttpResponse.json(
            {
              error: 'Bitte bestätigen Sie Ihre Identität.',
              status: 403,
              code: 'RECENT_LOGIN_REQUIRED',
            },
            { status: 403 },
          )
        }
        const stored = mockLocalUsers.find((account) => account.id === String(params.id))!
        return HttpResponse.json({ ...stored, systemRole: body.systemRole ?? stored.systemRole })
      }),
    )
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'T. Klein')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('combobox', { name: 'Rolle' }))
    await user.click(await screen.findByRole('option', { name: 'Revision' }))
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    const confirm = await screen.findByRole('dialog', { name: 'Anmeldung bestätigen' })
    await user.type(within(confirm).getByLabelText('Ihr Passwort'), mockLocalAccount.password)
    await user.click(within(confirm).getByRole('button', { name: 'Bestätigen' }))

    await waitFor(() => expect(bodies).toHaveLength(2))
    expect(bodies[1].systemRole).toBe('AUDITOR')
    await waitForDialogClosed()
  }, 20000)

  it('names the review obligation behind a hint link and jumps into the matching filter', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()

    // No hint box above the list any more, but a link in the head row.
    const hint = await screen.findByRole('button', {
      name: '2 lokale Konten ohne Ablaufdatum · 1 offene Einladung',
    })
    expect(hint).toHaveAttribute('aria-haspopup', 'dialog')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.queryByText(/regelmäßig zu überprüfen/)).not.toBeInTheDocument()

    await user.click(hint)
    const popup = await screen.findByRole('dialog', { name: 'Hinweise zur Kontenprüfung' })
    expect(popup).toHaveTextContent(/regelmäßig zu überprüfen/)
    expect(popup).toHaveTextContent('2 lokale Konten ohne Ablaufdatum')
    expect(popup).toHaveTextContent('1 offene Einladung')

    await user.click(
      within(popup).getByRole('button', { name: 'Konten ohne Ablaufdatum anzeigen' }),
    )
    await waitFor(() => expect(useUserAdminStore.getState().filters.review).toBe('WITHOUT_EXPIRY'))
    expect(useUserAdminStore.getState().filters.providerType).toBe('LOCAL')
    await waitFor(() =>
      expect(
        screen.queryByRole('dialog', { name: 'Hinweise zur Kontenprüfung' }),
      ).not.toBeInTheDocument(),
    )
    // The number in the hint and the rows behind the jump are the same set: the
    // emergency account stays unlimited and is in neither of them.
    await waitFor(() => {
      const accounts = useUserAdminStore.getState().accounts
      expect(accounts).toHaveLength(2)
      expect(
        accounts.every(
          (account) => account.local && !account.local.expiresAt && !account.local.bootstrap,
        ),
      ).toBe(true)
    })

    await user.click(hint)
    const reopened = await screen.findByRole('dialog', { name: 'Hinweise zur Kontenprüfung' })
    await user.click(within(reopened).getByRole('button', { name: 'Offene Einladungen anzeigen' }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.status).toBe('INVITED'))
  })

  it('shows no hint link once no account is without an expiry date and none is invited', async () => {
    setMockLocalUsers(
      mockLocalUsers
        .filter((account) => account.status !== 'INVITED')
        .map((account) => ({ ...account, expiresAt: '2027-12-31T22:59:59Z' })),
    )
    signInAs('SYSTEM_ADMIN')
    renderAccounts()

    await screen.findByRole('table', { name: 'Konten' })
    await waitFor(() => expect(useUserAdminStore.getState().summary).not.toBeNull())
    expect(screen.getByRole('button', { name: 'Konto anlegen' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /ohne Ablaufdatum|offene Einladung/ })).toBeNull()
  })

  it('creates an account with a generated password and shows it exactly once', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    await user.click(screen.getByRole('button', { name: /Konto anlegen/ }))
    const dialog = await screen.findByRole('dialog')
    expect(
      within(dialog).getByText(/Mit dem Ablaufdatum enden etwaige verbundene Konten/),
    ).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText(/E-Mail-Adresse/), 'p.neu@stadt.example')
    await user.type(within(dialog).getByLabelText('Anzeigename'), 'P. Neu')
    await user.type(within(dialog).getByLabelText('Anlagegrund'), 'Neueinstellung im Bauamt')
    await user.click(within(dialog).getByRole('radio', { name: /Anfangspasswort/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    const once = await screen.findByTestId('generated-password-value')
    expect(once).toHaveTextContent('Mock-Anfangs-Passwort-7Q2')
    const shown = screen.getByRole('dialog', { name: 'Neues Passwort für „P. Neu“' })
    expect(shown).toHaveTextContent('p.neu@stadt.example')
    expect(screen.getByText(/erscheint nur einmal/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Schließen' }))
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
    expect(
      screen.getByRole('dialog', { name: 'Einladungslink für „E. Extern“' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/Der Versand ist fehlgeschlagen/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Schließen' }))
    expect(screen.queryByTestId('setup-link-value')).not.toBeInTheDocument()
  }, 20000)

  // regression guard for #1543: MUI's select renders a display with role="combobox" that a
  // <label for> does not name - the dialog's E2E accessibility check failed on it
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

    // The consequence is in the overlay before locking - it is the basis for the decision.
    const sperrfrage = await screen.findByRole('dialog', { name: /„T\. Klein“ sperren\?/ })
    expect(sperrfrage).toHaveTextContent('Sitzungen enden sofort')
    // neutral: no number, no hint whether this person connected anything
    expect(sperrfrage).toHaveTextContent(/etwaige verbundene Konten enden/i)
    expect(sperrfrage).toHaveTextContent(/Löschfrist/)
    await user.click(within(sperrfrage).getByRole('button', { name: 'Sperren' }))

    await waitFor(() =>
      expect(
        within(rowOf('T. Klein')).getByRole('img', {
          name: 'Sperrgrund: Von der Verwaltung gesperrt',
        }),
      ).toBeInTheDocument(),
    )
    // the reason appears as a tooltip on hover
    await user.hover(within(rowOf('T. Klein')).getByRole('img', { name: /^Sperrgrund:/ }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent('Von der Verwaltung gesperrt')
    await user.unhover(within(rowOf('T. Klein')).getByRole('img', { name: /^Sperrgrund:/ }))

    const lockedMenu = await openRowMenu(user, 'T. Klein')
    expect(within(lockedMenu).queryByRole('menuitem', { name: 'Sperren' })).not.toBeInTheDocument()
    await user.click(within(lockedMenu).getByRole('menuitem', { name: 'Entsperren' }))
    await waitFor(() => expect(within(rowOf('T. Klein')).getByText('Aktiv')).toBeInTheDocument())
  }, 20000)

  it('turns a lock for inactivity into a lock by the administration without unlocking', async () => {
    setMockLocalUsers(
      mockLocalUsers.map((account) =>
        account.displayName === 'T. Klein'
          ? { ...account, status: 'LOCKED' as const, lockedReason: 'INACTIVITY' as const }
          : account,
      ),
    )
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'T. Klein')
    expect(within(menu).getByRole('menuitem', { name: 'Entsperren' })).toBeInTheDocument()
    await user.click(within(menu).getByRole('menuitem', { name: 'Sperren' }))
    await user.click(
      within(await screen.findByRole('dialog', { name: /„T\. Klein“ sperren\?/ })).getByRole(
        'button',
        { name: 'Sperren' },
      ),
    )

    await waitFor(() =>
      expect(
        within(rowOf('T. Klein')).getByRole('img', {
          name: 'Sperrgrund: Von der Verwaltung gesperrt',
        }),
      ).toBeInTheDocument(),
    )
    const lockedMenu = await openRowMenu(user, 'T. Klein')
    expect(within(lockedMenu).queryByRole('menuitem', { name: 'Sperren' })).not.toBeInTheDocument()
  }, 20000)

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
    // The handover cannot even be selected on this account, and the entry says why
    // - the emergency account must stay a local account.
    const uebergabe = within(menu).getByRole('menuitem', {
      name: 'An Identitätsanbieter übergeben',
    })
    expect(uebergabe).toHaveAttribute('aria-disabled', 'true')
    expect(within(menu).getByText(/muss deshalb ein lokales Konto bleiben/)).toBeInTheDocument()
  }, 20000)

  /**
   * regression guard for #1640: on the emergency account the interface does not even offer
   * locking, role change and time-limiting – not even to another administrator, for whom the
   * lock of the own account does not apply.
   */
  it('offers another administrator no lock, role change or expiry on the bootstrap account', async () => {
    signInAs('SYSTEM_ADMIN', 'local-user-vogt')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Systemverwaltung', 'admin@opaa.local')
    expect(within(menu).getByRole('menuitem', { name: 'Sperren' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(within(menu).getByText(/weder gesperrt noch gelöscht/)).toBeInTheDocument()
    expect(within(menu).queryByText(/Das eigene Konto/)).not.toBeInTheDocument()
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('combobox', { name: 'Rolle' })).toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(within(dialog).getByText(/behält die Rolle der Systemverwaltung/)).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Ablaufdatum')).toBeDisabled()
    expect(within(dialog).getByRole('checkbox', { name: 'Kein Ablaufdatum' })).toBeDisabled()
  }, 20000)

  /**
   * regression guard for #1988: on the emergency account the interface offers neither a reset link
   * nor a generated password and names the reason; on a regular account both stay
   * selectable.
   */
  it('offers no reset link and no generated password on the bootstrap account', async () => {
    signInAs('SYSTEM_ADMIN', 'local-user-vogt')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Systemverwaltung', 'admin@opaa.local')
    for (const name of ['Rücksetz-Link per E-Mail', 'Passwort erzeugen']) {
      const item = within(menu).getByRole('menuitem', { name })
      expect(item).toHaveAttribute('aria-disabled', 'true')
      expect(item).toHaveAccessibleDescription(/Passwort .* nicht von der Verwaltung/)
    }
    await user.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('menu')).not.toBeInTheDocument())

    const regularMenu = await openRowMenu(user, 'T. Klein')
    for (const name of ['Rücksetz-Link per E-Mail', 'Passwort erzeugen']) {
      expect(within(regularMenu).getByRole('menuitem', { name })).not.toHaveAttribute(
        'aria-disabled',
      )
    }
    expect(within(regularMenu).queryByText(/nicht von der Verwaltung/)).not.toBeInTheDocument()
  }, 20000)

  /**
   * An older emergency account can carry an expiry date and another role. The
   * dialog allows exactly the return to the promised state: remove the date, back to
   * system administration – and nothing the backend would reject.
   */
  it('lets a bootstrap account from an older state return to no expiry and Systemverwaltung', async () => {
    setMockLocalUsers(
      mockLocalUsers.map((account) =>
        account.bootstrap
          ? { ...account, systemRole: 'USER', expiresAt: '2027-01-31T22:59:59Z' }
          : account,
      ),
    )
    signInAs('SYSTEM_ADMIN', 'local-user-vogt')
    const user = userEvent.setup()
    const bodies: LocalUserUpdateRequest[] = []
    server.use(
      http.patch('/api/v1/admin/local-users/:id', async ({ params, request }) => {
        const body = (await request.json()) as LocalUserUpdateRequest
        bodies.push(body)
        const stored = mockLocalUsers.find((account) => account.id === String(params.id))!
        return HttpResponse.json({ ...stored, systemRole: body.systemRole ?? stored.systemRole })
      }),
    )
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Systemverwaltung', 'admin@opaa.local')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))
    const dialog = await screen.findByRole('dialog')

    const role = within(dialog).getByRole('combobox', { name: 'Rolle' })
    expect(role).not.toHaveAttribute('aria-disabled')
    await user.click(role)
    const options = await screen.findByRole('listbox')
    expect(
      within(options)
        .getAllByRole('option')
        .map((option) => option.textContent),
    ).toEqual(['Nutzer', 'Systemverwaltung'])
    await user.click(within(options).getByRole('option', { name: 'Systemverwaltung' }))

    expect(within(dialog).getByLabelText('Ablaufdatum')).toBeDisabled()
    const noExpiry = within(dialog).getByRole('checkbox', { name: 'Kein Ablaufdatum' })
    expect(noExpiry).toBeEnabled()
    await user.click(noExpiry)
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    await waitFor(() => expect(bodies).toHaveLength(1))
    expect(bodies[0]).toMatchObject({ systemRole: 'SYSTEM_ADMIN', noExpiry: true })
    expect(bodies[0]).not.toHaveProperty('expiresAt')
  }, 20000)

  it('opens the handover dialog for a regular local account', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    useAuthStore.setState({
      providers: [
        {
          id: 'provider-1',
          displayName: 'Identitätsanbieter der Stadt',
          issuerUri: 'https://idp.stadt.example/realms/beschaeftigte',
          clientId: 'opaa-frontend',
          isDefault: true,
          sortOrder: 0,
        },
      ],
    })

    const menu = await openRowMenu(user, 'T. Klein')
    await user.click(
      within(menu).getByRole('menuitem', { name: 'An Identitätsanbieter übergeben' }),
    )

    const dialog = await screen.findByRole('dialog', { name: 'An Identitätsanbieter übergeben' })
    // The dialog asks for provider and reason - and for no identifier.
    expect(within(dialog).getByRole('combobox', { name: /Identitätsanbieter/ })).toBeInTheDocument()
    expect(within(dialog).getByRole('textbox', { name: /Anlass/ })).toBeInTheDocument()
    expect(within(dialog).getByText(/Einen Rückweg gibt es nicht/)).toBeInTheDocument()
  }, 20000)

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
   * The emergency account is not in the hint about the requirement – the help text below „Kein
   * Ablaufdatum" must therefore not promise it one, but must for a regular account. Both
   * directions are here, so that a swapped distinction is noticed.
   */
  it('tells the bootstrap account it stays unlimited and a regular one about the notice', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderAccounts()
    await screen.findByRole('table', { name: 'Konten' })

    const menu = await openRowMenu(user, 'Systemverwaltung', 'admin@opaa.local')
    await user.click(within(menu).getByRole('menuitem', { name: 'Bearbeiten' }))

    const bootstrapDialog = await screen.findByRole('dialog')
    expect(within(bootstrapDialog).getByText(/bleibt unbefristet/)).toBeInTheDocument()
    expect(within(bootstrapDialog).queryByText(/erscheint im Hinweis zur/)).not.toBeInTheDocument()
    await user.click(within(bootstrapDialog).getByRole('button', { name: 'Abbrechen' }))
    // only once the dialog is gone is the table reachable again
    await waitForDialogClosed()

    // „M. Weber (Partner)" carries no expiry date either, but is a regular account
    // the row name goes into the search as a RegExp - leave out the display name's parentheses
    const regularMenu = await openRowMenu(user, 'M. Weber (Partner)', 'M\\. Weber')
    await user.click(within(regularMenu).getByRole('menuitem', { name: 'Bearbeiten' }))

    const regularDialog = await screen.findByRole('dialog')
    expect(within(regularDialog).getByText(/erscheint im Hinweis zur/)).toBeInTheDocument()
    expect(within(regularDialog).getByRole('combobox', { name: 'Rolle' })).not.toHaveAttribute(
      'aria-disabled',
    )
    expect(within(regularDialog).queryByText(/bleibt unbefristet/)).not.toBeInTheDocument()
  }, 20000)

  /**
   * The PATCH sends `expiresAt` only for a changed day — otherwise, recalculated to 23:59:59
   * local time, a mere name change would shift the expiry date and appear as a
   * period shift in the `LOCAL_USER_CHANGED` event, which records before/after only for
   * `expires_at`.
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
    // the renamed row keeps its origin and folds the local row back in; the list reloads after the
    // dialog closes, which takes longer than the default second under a full parallel run
    await waitFor(() => expect(rowOf('T. Klein-Meier')).toBeInTheDocument(), { timeout: 5000 })
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
   * The switch does not save the half-typed rule draft along — otherwise an
   * expiry default would jump from, say, 365 to 3, and an empty number field would let the toggle
   * silently fail as `NaN`.
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
    // The domain list is the precondition of exactly this switch and therefore travels along.
    expect(bodies[0].selfRegistrationAllowedDomains).toEqual(['amt.example'])
    expect(bodies[0].selfRegistrationEnabled).toBe(true)
  }, 20000)

  /** Re-check N1: when switching **off**, the domain list is no part of the action. */
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
    // The half-typed entry is still in the form - a switch click does not discard it
    // (re-check N4).
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

  /** A switch on „ein" must not claim that the flow is in effect. */
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
    // Four keystrokes, one request: the field is debounced.
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

    // Origin: local accounts first, then the providers by name, last an account without
    // a provider row - a category next to proper names does not sort alphabetically.
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
      // partner portal before directory service
      expect(origins.indexOf('Partnerportal')).toBeLessThan(origins.indexOf('Verzeichnisdienst'))
    })

    // Role: by privilege, which is also the order of the labels
    await user.click(within(table).getByRole('button', { name: /Rolle/ }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.sort).toBe('role'))
    await waitFor(() => {
      const roles = useUserAdminStore.getState().accounts.map((account) => account.systemRole)
      expect(roles[0]).toBe('USER')
      expect(roles.at(-1)).toBe('SYSTEM_ADMIN')
    })

    // State: what needs action first; provider accounts last, they carry none
    await user.click(within(table).getByRole('button', { name: /Zustand/ }))
    await waitFor(() => expect(useUserAdminStore.getState().filters.sort).toBe('status'))
    await waitFor(() => {
      const accounts = useUserAdminStore.getState().accounts
      expect(accounts[0].local?.status).toBe('LOCKED')
      expect(accounts.at(-1)?.local).toBeUndefined()
    })

    // Sorting by activity is not possible – the list does not show it at all.
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
