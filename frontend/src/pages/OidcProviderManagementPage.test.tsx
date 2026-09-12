import { screen, waitFor, within } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { afterAll, beforeAll, beforeEach, describe, expect, it, onTestFinished, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockOidcProviders } from '../mocks/fixtures'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import { DEFAULT_PROVIDER_HINT } from '../components/admin/providers/ProviderRowMenu'
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

const findProviderTable = () => screen.findByRole('table', { name: 'Identitätsanbieter' })

/** Die Anbieterzeilen in ihrer Reihenfolge - der Tabellenkörper, ohne die Kopfzeile. */
function providerRows() {
  const table = screen.getByRole('table', { name: 'Identitätsanbieter' })
  const [, koerper] = within(table).getAllByRole('rowgroup')
  return within(koerper).getAllByRole('row')
}

/** Die Tabellenzeile eines Anbieters - erkannt am Anzeigenamen, den ihre Anbieterspalte führt. */
function rowOf(name: string) {
  return screen.getByRole('row', { name: new RegExp(name) })
}

async function openRowMenu(user: UserEvent, name: string) {
  await findProviderTable()
  await user.click(within(rowOf(name)).getByRole('button', { name: `Aktionen für „${name}“` }))
  return screen.findByRole('menu')
}

/** Wählt eine Handlung im Zeilenmenü eines Anbieters. */
async function clickRowAction(user: UserEvent, name: string, action: string) {
  const menu = await openRowMenu(user, name)
  await user.click(within(menu).getByRole('menuitem', { name: action }))
}

/**
 * Öffnet das Formular eines Anbieters über „Bearbeiten“ seines Zeilenmenüs - und sichert dabei zu,
 * dass der Dialog den Anbieter trägt, dessen Zeile ihn geöffnet hat.
 */
async function openEditDialog(user: UserEvent, name: string) {
  await clickRowAction(user, name, 'Bearbeiten')
  const dialog = await screen.findByRole('dialog')
  expect(within(dialog).getByRole('textbox', { name: /^Anzeigename\s*\*?$/ })).toHaveValue(name)
  return dialog
}

/**
 * The provider management (#1333, ADR-0025, table with row menu since #1625): the list with state,
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
    // Die Reihenfolge dieser Liste *ist* die Reihenfolge der Anmeldeseite: Zeilenfolge und die
    // sichtbare Positionsziffer müssen dasselbe sagen, sonst trägt die Liste ihre Aussage nicht.
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
    // Wofür die Ziffer steht, sagt der Spaltenkopf - auch vorgelesen.
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
   * „Nicht erreichbar" allein sagt nicht, ob die Adresse falsch ist oder der Dienst steht. Die
   * Antwort des Anbieters steht deshalb wörtlich in der Zeile - sie ist der erste Anhaltspunkt
   * bei einer Störung.
   */
  it('names why an unreachable provider cannot be reached', async () => {
    signInAs('SYSTEM_ADMIN')

    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const zeile = await screen.findByRole('row', { name: /Partnerportal/ })

    expect(within(zeile).getByText('Nicht erreichbar')).toBeInTheDocument()
    expect(within(zeile).getByText('Discovery-Dokument: Antwort mit HTTP 503.')).toBeInTheDocument()
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
    // 20 s statt der voreingestellten 5: Der Test tippt in vier MUI-Felder, und jede Eingabe
    // rendert die Seite neu - auf einer ausgelasteten Maschine reicht das Standardlimit nicht.
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
   * Die Verhaltensänderung von #1625: Am Standardanbieter **fehlen** „Deaktivieren“ und „Löschen“
   * nicht mehr, sie sind gesperrt und nennen ihren Grund. Eine fehlende Handlung wirft die Frage
   * auf, ob sie je da war; eine gesperrte beantwortet sie.
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
    // Der Grund steht im DOM und ist über `aria-describedby` mit beiden gesperrten Einträgen
    // verbunden, wird also auch vorgelesen - ein Tooltip fände nur eine Maus.
    const grund = within(menu).getByText(DEFAULT_PROVIDER_HINT)
    expect(deaktivieren).toHaveAttribute('aria-describedby', grund.closest('li')!.id)
    expect(loeschen).toHaveAttribute('aria-describedby', grund.closest('li')!.id)

    // Gesperrt heißt auch: Der Eintrag nimmt keinen Klick an - `pointer-events: none`, nicht bloß
    // ein Vermerk im Namen; testing-library weist den Versuch genau deshalb ab.
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

    // the mock mirrors OidcProviderService#makeDefault: an unreachable provider is refused. Die
    // Ablehnung kommt als Benachrichtigung, nicht mehr als Meldung in der Zeile - und nennt den
    // Grund, den das Backend gemeldet hat.
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

    // Die Sperre hing am Standard, nicht am Anbieter: Der vorherige Standard ist jetzt
    // deaktivierbar, und sein Menü führt keinen Grund mehr.
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
    expect(await screen.findByText(/Kein Anbieter ist Standardanbieter/)).toBeInTheDocument()
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
    const abschaltfrage = await screen.findByRole('dialog', { name: /deaktivieren\?/ })
    expect(abschaltfrage).toHaveTextContent(/nicht mehr anmelden/)
    await user.click(within(abschaltfrage).getByRole('button', { name: 'Deaktivieren' }))
    await waitFor(() => {
      expect(within(rowOf('Partnerportal')).getByText('Deaktiviert')).toBeInTheDocument()
    })

    // Das Einschalten fragt nicht - es nimmt niemandem die Anmeldung. Der Anbieter kommt mit dem
    // Decoder-Zustand zurück, den er vor dem Abschalten hatte.
    await clickRowAction(user, 'Partnerportal', 'Aktivieren')
    await waitFor(() => {
      expect(within(rowOf('Partnerportal')).getByText('Nicht erreichbar')).toBeInTheDocument()
    })

    const menu = await openRowMenu(user, 'Partnerportal')
    expect(within(menu).getByRole('menuitem', { name: 'Deaktivieren' })).toBeInTheDocument()
    await user.click(within(menu).getByRole('menuitem', { name: 'Löschen' }))
    const loeschfrage = await screen.findByRole('dialog', { name: /löschen\?/ })
    expect(loeschfrage).toHaveTextContent(/Konten bleiben erhalten/)
    await user.click(within(loeschfrage).getByRole('button', { name: 'Löschen' }))
    await waitFor(() => {
      expect(screen.queryByText('Partnerportal')).not.toBeInTheDocument()
    })
  })

  it('moves a provider up in the sign-in order', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await findProviderTable()
    // Die Enden der Liste haben keinen Schritt mehr in ihre Richtung.
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
    // Die gesendete Reihenfolge enthält alle Zeilen der Tabelle; Position 0 hält die LOCAL-Zeile,
    // der erste Anbieter liegt damit auf 1 (#1541).
    expect(mockOidcProviders.find((p) => p.displayName === 'Partnerportal')?.sortOrder).toBe(1)
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

  /** #1369: the two values an operator carries over to the provider are copied with one click. */
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

    // Die Zeile nennt nur das Zustandswort; was es bedeutet, steht hier - deshalb muss die Legende
    // die drei Zustände samt Erläuterung führen (#1625).
    const legend = screen.getByRole('region', { name: 'Status verstehen' })
    expect(within(legend).getByText('Erreichbar')).toBeInTheDocument()
    expect(within(legend).getByText('Nicht erreichbar')).toBeInTheDocument()
    expect(within(legend).getByText('Deaktiviert')).toBeInTheDocument()
    expect(within(legend).getByText(/Anmeldungen funktionieren/)).toBeInTheDocument()
    expect(within(legend).getByText(/Anmeldungen schlagen fehl/)).toBeInTheDocument()
    expect(within(legend).getByText(/Konten bleiben erhalten/)).toBeInTheDocument()
  })

  /** #1369, #1625: die Liste nennt, was der Anbieter aus dem Token holt, statt es im Dialog zu
   * verstecken - vor allem, wer über ihn Systemverwalter wird. */
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
   * ADR-0033, Entscheidung 4 (#1541): Die `LOCAL`-Zeile steht in derselben Tabelle, ist aber kein
   * Anbieter dieser Seite - ihr Schalter ist der Schalter der Benutzerverwaltung.
   */
  it('does not list the local account management among the providers', async () => {
    signInAs('SYSTEM_ADMIN')
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })

    await findProviderTable()
    expect(providerRows()).toHaveLength(3)
    expect(screen.queryByText('Lokale Konten')).not.toBeInTheDocument()
    expect(screen.getByText(/unter\s+Administration → Benutzer geführt/)).toBeInTheDocument()
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

    // Als letzter aktivierter Anbieter ist auch der Standard abschaltbar - die Sperre des
    // Standardanbieters endet genau hier (ADR-0033, Entscheidung 4).
    await clickRowAction(user, 'Verzeichnisdienst', 'Deaktivieren')

    // Die Bestätigung *ist* das Acknowledgement, das das Backend verlangt (ADR-0025):
    // Der Zusatzsatz zum letzten Anbieter muss deshalb vor dem Absenden gestanden haben.
    const letzterAnbieter = await screen.findByRole('dialog', { name: /deaktivieren\?/ })
    expect(letzterAnbieter).toHaveTextContent('Danach können sich nur noch lokale Konten anmelden')
    await user.click(within(letzterAnbieter).getByRole('button', { name: 'Deaktivieren' }))

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
    await answerConfirm(user, /deaktivieren\?/, 'Deaktivieren')

    // Die Erklärung erscheint als Benachrichtigung, nicht mehr als Meldung im Anbietereintrag.
    expect(await screen.findByRole('alert')).toHaveTextContent(
      /lokales Systemverwalterkonto mit Passwort/,
    )
  })
})
