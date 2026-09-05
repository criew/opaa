import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../mocks/server'
import { mockOidcProviders } from '../mocks/fixtures'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
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

/**
 * The provider management (#1333, ADR-0025): list with state, order and default, the form
 * dialog without any secret field, the roles-claim confirmation, the connection test, the
 * consequence hints, and the setup instructions composed from this app's own origin.
 */
describe('OidcProviderManagementPage', () => {
  beforeEach(() => {
    useOidcProviderStore.setState({ providers: [], isLoading: false, error: null })
    vi.spyOn(window, 'confirm').mockReturnValue(true)
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

    const cards = await screen.findAllByRole('article')
    expect(cards.map((c) => within(c).getByRole('heading', { level: 2 }).textContent)).toEqual([
      'Verzeichnisdienst',
      'Partnerportal',
    ])
    expect(within(cards[0]).getByLabelText('Standardanbieter')).toBeInTheDocument()
    expect(within(cards[0]).getByText('Erreichbar')).toBeInTheDocument()
    expect(within(cards[1]).getByText('Nicht erreichbar')).toBeInTheDocument()
    expect(
      within(cards[1]).getByText('Discovery-Dokument: Antwort mit HTTP 503.'),
    ).toBeInTheDocument()
    expect(within(cards[1]).getByText('Rollen aus dem Token')).toBeInTheDocument()
    // the default can neither be disabled nor deleted
    expect(within(cards[0]).queryByRole('button', { name: 'Löschen' })).not.toBeInTheDocument()
    expect(within(cards[0]).queryByRole('button', { name: 'Deaktivieren' })).not.toBeInTheDocument()
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
  })

  it('asks for confirmation before a roles claim is set and saves it afterwards', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await user.click((await screen.findAllByRole('button', { name: 'Bearbeiten' }))[0])
    const dialog = await screen.findByRole('dialog')

    await user.type(within(dialog).getByLabelText(/^Rollen-Claim/), 'realm_access.roles')
    await user.type(within(dialog).getByLabelText(/SYSTEM_ADMIN/), 'opaa-admin')
    await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

    // nothing is saved yet: the confirmation is owed first
    expect(await within(dialog).findByRole('alertdialog')).toHaveTextContent(/führend/)
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
  })

  it('runs the connection test from the dialog and shows the outcome', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await user.click((await screen.findAllByRole('button', { name: 'Bearbeiten' }))[1])
    const dialog = await screen.findByRole('dialog')

    await user.click(within(dialog).getByRole('button', { name: 'Verbindung testen' }))

    expect(await within(dialog).findByText(/Anbieter erreichbar/)).toBeInTheDocument()
  })

  it('disables, re-enables and deletes a non-default provider with a consequence hint', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    const partner = (await screen.findAllByRole('article'))[1]

    await user.click(within(partner).getByRole('button', { name: 'Deaktivieren' }))
    expect(confirm).toHaveBeenLastCalledWith(expect.stringMatching(/nicht mehr anmelden/))
    expect(await within(partner).findByText('Deaktiviert')).toBeInTheDocument()
    await user.click(within(partner).getByRole('button', { name: 'Aktivieren' }))
    expect(await within(partner).findByRole('button', { name: 'Deaktivieren' })).toBeInTheDocument()

    await user.click(within(partner).getByRole('button', { name: 'Löschen' }))
    expect(confirm).toHaveBeenLastCalledWith(expect.stringMatching(/Konten bleiben erhalten/))
    await waitFor(() => {
      expect(screen.queryByText('Partnerportal')).not.toBeInTheDocument()
    })
  })

  it('moves a provider up in the sign-in order', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await screen.findAllByRole('article')

    await user.click(screen.getByRole('button', { name: '„Partnerportal“ nach oben verschieben' }))

    await waitFor(() => {
      const cards = screen.getAllByRole('article')
      expect(within(cards[0]).getByRole('heading', { level: 2 })).toHaveTextContent('Partnerportal')
    })
    expect(mockOidcProviders.find((p) => p.displayName === 'Partnerportal')?.sortOrder).toBe(0)
  })

  it('shows the API message when an issuer change is refused for a provider with accounts', async () => {
    signInAs('SYSTEM_ADMIN')
    const user = userEvent.setup()
    renderWithProviders(<OidcProviderManagementPage />, { withRouter: true })
    await user.click((await screen.findAllByRole('button', { name: 'Bearbeiten' }))[0])
    const dialog = await screen.findByRole('dialog')
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
    await screen.findAllByRole('article')

    expect(screen.getByTestId('oidc-redirect-uri')).toHaveTextContent(
      `${window.location.origin}/auth/callback`,
    )
    expect(screen.getByTestId('oidc-origin')).toHaveTextContent(window.location.origin)
    expect(screen.getByText(/OPAA_CSP_CONNECT_SRC_EXTRA/)).toBeInTheDocument()
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
})
