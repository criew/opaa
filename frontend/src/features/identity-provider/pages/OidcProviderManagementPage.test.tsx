import { screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { mockOidcProviderImpacts, mockOidcProviders } from '../mocks/identityProviderFixtures'
import { answerConfirm, renderWithProviders, waitForDialogClosed } from '@/test/test-utils'
import { useAuthStore } from '@/shared/session/authStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import { DEFAULT_PROVIDER_HINT } from '../components/ProviderRowMenu'
import OidcProviderManagementPage from './OidcProviderManagementPage'

function signInAs(systemRole: 'SYSTEM_ADMIN' | 'USER', mode: 'dev' | 'oidc' = 'oidc') {
  useAuthStore.setState({
    mode,
    isAuthenticated: true,
    isLoading: false,
    user: { id: 'user-1', email: 'admin@opaa.local', displayName: 'Admin', systemRole },
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

/** A connection profile admits persons: the confirmation names the effect, never a number. */
const PERSONS_ADMITTED = {
  confirmationRequired: true,
  disableEffect: 'CONNECTIONS_REST',
  deleteEffect: 'CONNECTIONS_END',
} as const

const findProviderTable = () => screen.findByRole('table', { name: 'Identitätsanbieter' })

/** The provider rows in their order - the table body, without the header row. */
function providerRows() {
  const table = screen.getByRole('table', { name: 'Identitätsanbieter' })
  const [, koerper] = within(table).getAllByRole('rowgroup')
  return within(koerper).getAllByRole('row')
}

/** A provider's table row - recognized by the display name its provider column shows. */
function rowOf(name: string) {
  return screen.getByRole('row', { name: new RegExp(name) })
}

async function openRowMenu(user: UserEvent, name: string) {
  await findProviderTable()
  await user.click(
    within(rowOf(name)).getByRole('button', { name: `Weitere Aktionen für „${name}“` }),
  )
  return screen.findByRole('menu')
}

/** Chooses an action in a provider's row menu. */
async function clickRowAction(user: UserEvent, name: string, action: string) {
  const menu = await openRowMenu(user, name)
  await user.click(within(menu).getByRole('menuitem', { name: action }))
}

/**
 * Confirms the question before deactivating or deleting as soon as the consequences for connected
 * accounts are loaded - only then is the button enabled -, waits until the dialog is closed, and
 * returns the text the confirmation showed when confirming.
 */
async function confirmShutdown(user: UserEvent, question: RegExp, verb: string) {
  const dialog = await screen.findByRole('dialog', { name: question })
  const button = within(dialog).getByRole('button', { name: verb })
  await waitFor(() => expect(button).toBeEnabled())
  const shown = dialog.textContent ?? ''
  await user.click(button)
  await waitForDialogClosed()
  return shown
}

/** The query parameters of every request to `pathSuffix` while the test runs. */
function recordParams(pathSuffix: string, method: string) {
  const recorded: URLSearchParams[] = []
  const record = ({ request }: { request: Request }) => {
    const url = new URL(request.url)
    if (request.method === method && url.pathname.endsWith(pathSuffix)) {
      recorded.push(url.searchParams)
    }
  }
  server.events.on('request:start', record)
  onTestFinished(() => server.events.removeListener('request:start', record))
  return recorded
}

/**
 * Opens a provider's form via „Bearbeiten“ in its row menu - and asserts along the way
 * that the dialog carries the provider whose row opened it.
 */
async function openEditDialog(user: UserEvent, name: string) {
  await clickRowAction(user, name, 'Bearbeiten')
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByRole('textbox', { name: /^Anzeigename\s*\*?$/ })).toHaveValue(name)
  return dialog
}

/**
 * The provider management (ADR-0025, table with row menu): the list with state,
 * order and default, the form dialog without any secret field, the roles-claim confirmation, the
 * connection test, the consequence hints, and the setup instructions composed from this app's own
 * origin.
 */
describe('OidcProviderManagementPage', () => {
  const originalMatchMedia = window.matchMedia

  beforeAll(() => {
    window.matchMedia = desktopMatchMedia
  })
  afterAll(() => {
    window.matchMedia = originalMatchMedia
  })

  beforeEach(() => {
    useOidcProviderStore.setState({ providers: [], isLoading: false, error: null })
  })

  it('shows no provider management to a user who is not a system administrator', () => {
    signInAs('USER')
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    expect(screen.queryByRole('button', { name: 'Neuer Anbieter' })).not.toBeInTheDocument()
    expect(screen.getByText(/nicht freigegeben/i)).toBeInTheDocument()
  })

  it('lists the providers in sign-in order with default, state and roles marker', async () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    const table = await findProviderTable()
    const zeilen = providerRows()
    expect(zeilen).toHaveLength(3)
    // The order of this list *is* the order of the login page: row sequence and the
    // visible position number must say the same, otherwise the list does not carry its statement.
    expect(
      ['Verzeichnisdienst', 'Partnerportal', 'Landesportal'].map((name) =>
        zeilen.indexOf(rowOf(name)),
      ),
    ).toEqual([0, 1, 2])
    expect(zeilen.map((zeile) => within(zeile).getAllByRole('cell')[0].textContent)).toEqual([
      '1',
      '2',
      '3',
    ])
    // What the number stands for is said by the column header - read out as well.
    expect(
      within(table).getByRole('columnheader', { name: /^Nr\.\s*auf der Anmeldeseite$/ }),
    ).toBeInTheDocument()

    expect(within(zeilen[0]).getByLabelText('Standardanbieter')).toBeInTheDocument()
    expect(within(zeilen[1]).queryByLabelText('Standardanbieter')).not.toBeInTheDocument()
    expect(within(zeilen[0]).getByText('Erreichbar')).toBeInTheDocument()
    expect(within(zeilen[1]).getByText('Nicht erreichbar')).toBeInTheDocument()
    expect(within(zeilen[1]).getByText('Rollen aus dem Token')).toBeInTheDocument()
  })

  /**
   * „Nicht erreichbar" alone does not say whether the address is wrong or the service is down.
   * The provider's answer is therefore shown verbatim in the row - it is the first clue
   * in case of a failure.
   */
  it('names why an unreachable provider cannot be reached', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const zeile = await screen.findByRole('row', { name: /Partnerportal/ })

    expect(within(zeile).getByText('Nicht erreichbar')).toBeInTheDocument()
    expect(within(zeile).getByText('Discovery-Dokument: Antwort mit HTTP 503.')).toBeInTheDocument()
  })

  /**
   * Each backend instance builds the token decoder itself (ADR-0053, Entscheidung 12), so the list
   * marks the reachability as the state of the instance that answered; a disabled provider is
   * disabled on every instance and carries no such mark.
   */
  it('marks the reachability of a provider as the state of this instance', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const reachable = await screen.findByRole('row', { name: /Verzeichnisdienst/ })
    const unreachable = screen.getByRole('row', { name: /Partnerportal/ })

    expect(within(reachable).getByText(/auf dieser Instanz/)).toBeInTheDocument()
    expect(within(unreachable).getByText(/auf dieser Instanz/)).toBeInTheDocument()
    const legend = screen.getByRole('region', { name: 'Status verstehen' })
    expect(within(legend).getByText(/gelten für die Backend-Instanz/)).toBeInTheDocument()
  })

  it('creates a provider through the dialog without any secret field', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await screen.findByText('Verzeichnisdienst')

    await user.click(screen.getByRole('button', { name: 'Neuer Anbieter' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByLabelText(/secret/i)).not.toBeInTheDocument()
    await user.type(
      within(dialog).getByRole('textbox', { name: /^Anzeigename\s*\*?$/ }),
      'Landesportal',
    )
    await user.type(within(dialog).getByLabelText(/^Issuer-URI/), 'https://land.example/realms/x')
    await user.type(within(dialog).getByLabelText(/^Client-ID/), 'opaa-land')
    await user.click(within(dialog).getByRole('button', { name: 'Anlegen' }))

    await waitFor(() => {
      expect(useOidcProviderStore.getState().providers.map((p) => p.displayName)).toContain(
        'Landesportal',
      )
    })
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(mockOidcProviders.find((p) => p.displayName === 'Landesportal')?.clientId).toBe(
      'opaa-land',
    )
    // 20 s instead of the default 5: the test types into four MUI fields, and every input
    // re-renders the page - on a busy machine the default limit is not enough.
  }, 20000)

  it('asks for confirmation before a roles claim is set and saves it afterwards', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const dialog = await openEditDialog(user, 'Verzeichnisdienst')

    await user.type(within(dialog).getByLabelText(/^Rollen-Claim/), 'realm_access.roles')
    await user.type(within(dialog).getByLabelText(/SYSTEM_ADMIN/), 'opaa-admin')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    // nothing is saved yet: the confirmation is owed first
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(/führend/)
    expect(
      useOidcProviderStore.getState().providers.find((p) => p.displayName === 'Verzeichnisdienst')
        ?.claimMapping.rolesClaim,
    ).toBeNull()

    await user.click(within(dialog).getByRole('button', { name: 'Rollen-Claim setzen' }))

    await waitFor(() => {
      expect(
        useOidcProviderStore.getState().providers.find((p) => p.displayName === 'Verzeichnisdienst')
          ?.claimMapping,
      ).toMatchObject({ rolesClaim: 'realm_access.roles', systemAdminRole: 'opaa-admin' })
    })
  }, 20000)

  /** ADR-0025: the confirmation is answered by its own button only - a second click on the
   * footer button cannot stand in for it, and editing without a new roles claim needs none. */
  it('cannot be bypassed by a second click and is not asked when the roles claim is unchanged', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const dialog = await openEditDialog(user, 'Verzeichnisdienst')

    await user.type(within(dialog).getByLabelText(/^Rollen-Claim/), 'realm_access.roles')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))
    // while the question is open the footer button is gone: no way to save past it, and the
    // confirming button holds the focus
    expect(within(dialog).queryByRole('button', { name: 'Speichern' })).not.toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Rollen-Claim setzen' })).toHaveFocus()
    // both role values empty: the consequence is spelled out
    expect(within(dialog).getByRole('alert')).toHaveTextContent(/allen seinen Konten/)
    await user.click(
      within(within(dialog).getByRole('alert')).getByRole('button', { name: 'Abbrechen' }),
    )
    expect(
      useOidcProviderStore.getState().providers.find((p) => p.displayName === 'Verzeichnisdienst')
        ?.claimMapping.rolesClaim,
    ).toBeNull()

    // a rename alone: saved without any question
    await user.clear(within(dialog).getByLabelText(/^Rollen-Claim/))
    const name = within(dialog).getByRole('textbox', { name: /^Anzeigename\s*\*?$/ })
    await user.clear(name)
    await user.type(name, 'Verzeichnisdienst (Haus)')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))
    await waitFor(() => {
      expect(useOidcProviderStore.getState().providers.map((p) => p.displayName)).toContain(
        'Verzeichnisdienst (Haus)',
      )
    })
  }, 20000)

  /**
   * On the default provider „Deaktivieren“ and „Löschen“ are not **missing**, they are disabled
   * and name their reason. A missing action raises the question
   * whether it was ever there; a disabled one answers it.
   */
  it('locks disabling and deleting on the default provider and names the reason', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    const menu = await openRowMenu(user, 'Verzeichnisdienst')
    const deaktivieren = within(menu).getByRole('menuitem', { name: 'Deaktivieren' })
    const loeschen = within(menu).getByRole('menuitem', { name: 'Löschen' })
    expect(deaktivieren).toHaveAttribute('aria-disabled', 'true')
    expect(loeschen).toHaveAttribute('aria-disabled', 'true')
    // The reason is in the DOM and connected to both disabled entries via `aria-describedby`,
    // so it is read out as well - a tooltip would only be found by a mouse.
    const grund = within(menu).getByText(DEFAULT_PROVIDER_HINT)
    expect(deaktivieren).toHaveAttribute('aria-describedby', grund.closest('li')!.id)
    expect(loeschen).toHaveAttribute('aria-describedby', grund.closest('li')!.id)

    // Disabled also means: the entry accepts no click - `pointer-events: none`, not just
    // a note in the name; testing-library rejects the attempt for exactly that reason.
    await expect(user.click(deaktivieren)).rejects.toThrow(/pointer-events/)
    expect(useOidcProviderStore.getState().providers.find((p) => p.isDefault)?.enabled).toBe(true)
  })

  it('makes a reachable provider the default with a consequence hint, and refuses an unreachable one', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await clickRowAction(user, 'Partnerportal', 'Zum Standard machen')
    const standardfrage = await screen.findByRole('dialog', {
      name: /zum Standardanbieter machen\?/,
    })
    expect(standardfrage).toHaveTextContent(/weder deaktiviert noch gelöscht/)
    await user.click(within(standardfrage).getByRole('button', { name: 'Zum Standard machen' }))

    // the mock mirrors OidcProviderService#makeDefault: an unreachable provider is refused. The
    // rejection comes as a notification, not as a message in the row - and names the
    // reason the backend reported.
    const abweisung = await screen.findByRole('alert')
    expect(abweisung).toHaveTextContent(/nicht abrufbar/)
    expect(abweisung).toHaveTextContent('Discovery-Dokument: Antwort mit HTTP 503.')
    expect(
      within(rowOf('Verzeichnisdienst')).getByLabelText('Standardanbieter'),
    ).toBeInTheDocument()

    await clickRowAction(user, 'Landesportal', 'Zum Standard machen')
    await answerConfirm(user, /zum Standardanbieter machen\?/, 'Zum Standard machen')
    await waitFor(() => {
      expect(within(rowOf('Landesportal')).getByLabelText('Standardanbieter')).toBeInTheDocument()
    })
    expect(
      within(rowOf('Verzeichnisdienst')).queryByLabelText('Standardanbieter'),
    ).not.toBeInTheDocument()

    // The lock depended on the default, not on the provider: the previous default can now be
    // deactivated, and its menu no longer shows a reason.
    const altesMenu = await openRowMenu(user, 'Verzeichnisdienst')
    expect(within(altesMenu).getByRole('menuitem', { name: 'Deaktivieren' })).not.toHaveAttribute(
      'aria-disabled',
      'true',
    )
    expect(within(altesMenu).queryByText(DEFAULT_PROVIDER_HINT)).not.toBeInTheDocument()
  })

  it('warns when no provider is the default', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.get('/api/v1/admin/oidc-providers', () =>
        HttpResponse.json(mockOidcProviders.map((p) => ({ ...p, isDefault: false }))),
      ),
    )
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const hinweis = await screen.findByText(/Kein Anbieter ist Standardanbieter/)
    expect(hinweis).toBeInTheDocument()
    // The directory sync depends on the provider row itself; the hint must not name it as a
    // consequence of a missing default provider.
    expect(hinweis).toHaveTextContent(/Der Verzeichnisabgleich h.ngt nicht daran/)
    expect(hinweis).not.toHaveTextContent(/Verzeichnisabgleich greifen nicht/)
    // The way back is the emergency account; the retired bootstrap variable is never named.
    expect(hinweis).toHaveTextContent(/OPAA_LOCAL_ADMIN_RESET=force/)
    expect(hinweis).not.toHaveTextContent(/OPAA_OIDC_BOOTSTRAP/)
  })

  it('runs the connection test from the dialog and shows the outcome', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const dialog = await openEditDialog(user, 'Partnerportal')

    await user.click(within(dialog).getByRole('button', { name: 'Verbindung testen' }))

    expect(await within(dialog).findByText(/Anbieter erreichbar/)).toBeInTheDocument()
  })

  it('disables, re-enables and deletes a non-default provider with a consequence hint', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await clickRowAction(user, 'Partnerportal', 'Deaktivieren')
    const abschaltfrage = await confirmShutdown(user, /deaktivieren\?/, 'Deaktivieren')
    expect(abschaltfrage).toMatch(/nicht mehr anmelden/)
    await waitFor(() => {
      expect(within(rowOf('Partnerportal')).getByText('Deaktiviert')).toBeInTheDocument()
    })

    // Switching on does not ask - it takes nobody's login away. The provider comes back with the
    // decoder state it had before being switched off.
    await clickRowAction(user, 'Partnerportal', 'Aktivieren')
    await waitFor(() => {
      expect(within(rowOf('Partnerportal')).getByText('Nicht erreichbar')).toBeInTheDocument()
    })

    const menu = await openRowMenu(user, 'Partnerportal')
    expect(within(menu).getByRole('menuitem', { name: 'Deaktivieren' })).toBeInTheDocument()
    await user.click(within(menu).getByRole('menuitem', { name: 'Löschen' }))
    const loeschfrage = await confirmShutdown(user, /löschen\?/, 'Löschen')
    expect(loeschfrage).toMatch(/Konten bleiben erhalten/)
    await waitFor(() => {
      expect(screen.queryByText('Partnerportal')).not.toBeInTheDocument()
    })
  })

  it('moves a provider up in the sign-in order', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await findProviderTable()
    // The ends of the list have no further step in their direction.
    expect(
      screen.getByRole('button', { name: '„Verzeichnisdienst“ nach oben verschieben' }),
    ).toBeDisabled()
    expect(
      screen.getByRole('button', { name: '„Landesportal“ nach unten verschieben' }),
    ).toBeDisabled()

    await user.click(screen.getByRole('button', { name: '„Partnerportal“ nach oben verschieben' }))

    await waitFor(() => {
      expect(providerRows().indexOf(rowOf('Partnerportal'))).toBe(0)
    })
    // The order sent contains all rows of the table; position 0 holds the LOCAL row,
    // so the first provider is at 1.
    expect(mockOidcProviders.find((p) => p.displayName === 'Partnerportal')?.sortOrder).toBe(1)
  })

  it('warns before an issuer change that a derived directory access is removed', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const dialog = await openEditDialog(user, 'Verzeichnisdienst')
    expect(within(dialog).queryByText(/Verzeichniszugang/)).not.toBeInTheDocument()

    const issuer = within(dialog).getByLabelText(/^Issuer-URI/)
    await user.clear(issuer)
    await user.type(issuer, 'https://anderer-host.example/realms/haus')

    expect(
      within(dialog).getByText(/Verzeichniszugang ohne eigene Admin-API-Adresse/),
    ).toBeVisible()
  })

  it('shows the API message when an issuer change is refused for a provider with accounts', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const dialog = await openEditDialog(user, 'Verzeichnisdienst')
    const issuer = within(dialog).getByLabelText(/^Issuer-URI/)
    await user.clear(issuer)
    await user.type(issuer, 'https://idp.example/realms/neu')

    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    expect(await within(dialog).findByText(/12 Konten/)).toBeInTheDocument()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })

  it('shows the redirect URI and origin of this installation in the setup instructions', async () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await findProviderTable()

    expect(screen.getByTestId('oidc-redirect-uri')).toHaveTextContent(
      `${window.location.origin}/auth/callback`,
    )
    expect(screen.getByTestId('oidc-origin')).toHaveTextContent(window.location.origin)
    expect(screen.getByText(/OPAA_CSP_CONNECT_SRC_EXTRA/)).toBeInTheDocument()
  })

  /** The two values an operator carries over to the provider are copied with one click. */
  it('offers copy buttons for the redirect URI and the origin, and a legend of the states', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      configurable: true,
    })
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await findProviderTable()

    await user.click(screen.getByRole('button', { name: 'Weiterleitungs-URI kopieren' }))
    expect(writeText).toHaveBeenCalledWith(`${window.location.origin}/auth/callback`)
    expect(
      screen.getByRole('button', { name: 'Web-Origin und Abmelde-Weiterleitung kopieren' }),
    ).toBeInTheDocument()

    // The row names only the state word; what it means is stated here - that is why the legend
    // must list the three states including their explanation.
    const legend = screen.getByRole('region', { name: 'Status verstehen' })
    expect(within(legend).getByText('Erreichbar')).toBeInTheDocument()
    expect(within(legend).getByText('Nicht erreichbar')).toBeInTheDocument()
    expect(within(legend).getByText('Deaktiviert')).toBeInTheDocument()
    expect(within(legend).getByText(/Anmeldungen funktionieren/)).toBeInTheDocument()
    expect(within(legend).getByText(/Anmeldungen schlagen fehl/)).toBeInTheDocument()
    expect(within(legend).getByText(/Konten bleiben erhalten/)).toBeInTheDocument()
  })

  /** The list names what the provider takes from the token instead of hiding it in the
   * dialog - above all, who becomes system administrator through it. */
  it('summarises in the list what each provider takes from the token', async () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await findProviderTable()
    // the fixture's first provider manages roles in OPAA, the second reads them from the token
    const verzeichnis = rowOf('Verzeichnisdienst')
    expect(within(verzeichnis).getByText('Rollen in OPAA verwaltet')).toBeInTheDocument()
    expect(within(verzeichnis).getByText('Keine Gruppen aus dem Token')).toBeInTheDocument()

    const partner = rowOf('Partnerportal')
    expect(within(partner).queryByText('Rollen in OPAA verwaltet')).not.toBeInTheDocument()
    expect(within(partner).getByText('Rollen aus dem Token')).toBeInTheDocument()
    expect(within(partner).getByText('SYSTEM_ADMIN = opaa-admin')).toBeInTheDocument()
    expect(within(partner).getByText('Gruppen aus groups')).toBeInTheDocument()
  })

  it('explains in the dev mode that providers only take effect in the OIDC mode', async () => {
    signInAs('SYSTEM_ADMIN', 'dev')
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    expect(await screen.findByText(/erst im OIDC-Modus/)).toBeInTheDocument()
  })

  // One create label per object: the empty state repeats the label of the page head.
  it('offers „Neuer Anbieter“ in the empty state as in the page head', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(http.get('/api/v1/admin/oidc-providers', () => HttpResponse.json([])))
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    expect(await screen.findByText(/Ohne Anbieter gibt es keine Anmeldeseite/)).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Neuer Anbieter' })).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Anbieter anlegen' })).not.toBeInTheDocument()
  })

  it('shows the load error', async () => {
    signInAs('SYSTEM_ADMIN')
    server.use(
      http.get('/api/v1/admin/oidc-providers', () =>
        HttpResponse.json({ error: 'Datenbank nicht erreichbar' }, { status: 500 }),
      ),
    )
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    expect(await screen.findByText('Datenbank nicht erreichbar')).toBeInTheDocument()
  })

  /**
   * ADR-0033, Entscheidung 4: the `LOCAL` row is in the same table but is no
   * provider of this page - its switch is the switch of the user management.
   */
  it('does not list the local account management among the providers', async () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await findProviderTable()
    expect(providerRows()).toHaveLength(3)
    expect(screen.queryByText('Lokale Konten')).not.toBeInTheDocument()
    expect(
      screen.getByText(/Lokale Konten stehen unter\s+Administration → Benutzer/),
    ).toBeInTheDocument()
  })

  it('names the consequence of disabling the last enabled provider and sends the acknowledgement', async () => {
    // only the default is left enabled: disabling it is the step into a local-only installation
    mockOidcProviders
      .filter((provider) => !provider.isDefault && provider.providerType === 'OIDC')
      .forEach((provider) => {
        provider.enabled = false
      })
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const acknowledged: Array<string | null> = []
    const record = ({ request }: { request: Request }) => {
      const url = new URL(request.url)
      if (url.pathname.endsWith('/disable')) {
        acknowledged.push(url.searchParams.get('acknowledgeLastProvider'))
      }
    }
    server.events.on('request:start', record)
    // Removed even when an assertion below throws - a listener left behind would watch every
    // request of the remaining tests in this file.
    onTestFinished(() => server.events.removeListener('request:start', record))
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    // As the last enabled provider, the default can be switched off as well - the default
    // provider's lock ends exactly here (ADR-0033, Entscheidung 4).
    await clickRowAction(user, 'Verzeichnisdienst', 'Deaktivieren')

    // The confirmation *is* the acknowledgement the backend requires (ADR-0025):
    // the additional sentence about the last provider must therefore have been shown before
    // submitting.
    const letzterAnbieter = await confirmShutdown(user, /deaktivieren\?/, 'Deaktivieren')
    expect(letzterAnbieter).toContain('Danach können sich nur noch lokale Konten anmelden')

    await waitFor(() => expect(acknowledged).toEqual(['true']))
    await waitFor(() =>
      expect(useOidcProviderStore.getState().providers.find((p) => p.isDefault)?.enabled).toBe(
        false,
      ),
    )
  })

  it('explains the lockout guard when the backend refuses the last provider', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    server.use(
      http.post('/api/v1/admin/oidc-providers/:providerId/disable', () =>
        HttpResponse.json(
          { error: 'Kein anmeldefähiger Systemverwalter', code: 'LAST_LOGIN_CAPABLE_ADMIN' },
          { status: 409 },
        ),
      ),
    )
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await clickRowAction(user, 'Partnerportal', 'Deaktivieren')
    await confirmShutdown(user, /deaktivieren\?/, 'Deaktivieren')

    // The explanation appears as a notification, not as a message in the provider entry.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      /lokales Systemverwalterkonto mit Passwort/,
    )
  })

  /** ADR-0041, Entscheidung 4: deactivating suspends - reversible, no number per provider. */
  it('names what disabling does to connected accounts and sends the confirmation', async () => {
    mockOidcProviderImpacts['oidc-provider-partner'] = PERSONS_ADMITTED
    const sent = recordParams('/disable', 'POST')
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await clickRowAction(user, 'Partnerportal', 'Deaktivieren')
    const frage = await screen.findByRole('dialog', { name: /deaktivieren\?/ })
    const konten = await within(frage).findByRole('region', { name: 'Verbundene Konten' })
    expect(konten).toHaveTextContent(/Etwaige verbundene Konten/)
    expect(konten).toHaveTextContent(/ruhen: Es wird nichts gelöscht/)
    expect(konten).toHaveTextContent(/ohne neues Verbinden weiter/)
    expect(konten.textContent).not.toMatch(/\d/)
    await confirmShutdown(user, /deaktivieren\?/, 'Deaktivieren')

    expect(sent.map((params) => params.get('confirmConnections'))).toEqual(['true'])
    await waitFor(() => {
      expect(within(rowOf('Partnerportal')).getByText('Deaktiviert')).toBeInTheDocument()
    })
  })

  it('names that deleting deletes the access data irreversibly and starts the deletion period', async () => {
    mockOidcProviderImpacts['oidc-provider-partner'] = PERSONS_ADMITTED
    const sent = recordParams('/oidc-provider-partner', 'DELETE')
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await clickRowAction(user, 'Partnerportal', 'Löschen')
    const frage = await screen.findByRole('dialog', { name: /löschen\?/ })
    const konten = await within(frage).findByRole('region', { name: 'Verbundene Konten' })
    expect(konten).toHaveTextContent(/etwaiger verbundener Konten/)
    expect(konten).toHaveTextContent(/sofort\s+gelöscht – das lässt sich nicht rückgängig machen/)
    expect(konten).toHaveTextContent(/Löschfrist/)
    expect(konten.textContent).not.toMatch(/\d/)
    await confirmShutdown(user, /löschen\?/, 'Löschen')

    expect(sent.map((params) => params.get('confirmConnections'))).toEqual(['true'])
    await waitFor(() => expect(screen.queryByText('Partnerportal')).not.toBeInTheDocument())
  })

  it('asks nothing about connected accounts where no access admits persons', async () => {
    const sent = recordParams('/disable', 'POST')
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await clickRowAction(user, 'Partnerportal', 'Deaktivieren')
    const frage = await confirmShutdown(user, /deaktivieren\?/, 'Deaktivieren')

    expect(frage).not.toContain('Verbundene Konten')
    expect(sent.map((params) => params.get('confirmConnections'))).toEqual([null])
  })

  /** Neither action hangs on the impact: unknown, it names the effect as a precaution. */
  it('names the effect and confirms it when the impact cannot be loaded', async () => {
    server.use(
      http.get('/api/v1/admin/oidc-providers/:providerId/impact', () =>
        HttpResponse.json({ error: 'Dienst nicht erreichbar' }, { status: 500 }),
      ),
    )
    const sent = recordParams('/disable', 'POST')
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await clickRowAction(user, 'Partnerportal', 'Deaktivieren')
    const text = await confirmShutdown(user, /deaktivieren\?/, 'Deaktivieren')

    expect(text).toMatch(/ruhen: Es wird nichts gelöscht/)
    expect(sent.map((params) => params.get('confirmConnections'))).toEqual(['true'])
    await waitFor(() => {
      expect(within(rowOf('Partnerportal')).getByText('Deaktiviert')).toBeInTheDocument()
    })
  })
})
