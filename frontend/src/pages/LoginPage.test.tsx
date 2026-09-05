import { describe, it, expect, beforeEach, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'
import LoginPage from './LoginPage'

describe('LoginPage', () => {
  beforeEach(() => {
    useBrandingStore.setState({ branding: OPAA_BRANDING })
    useAuthStore.setState({
      mode: null,
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
      providers: [],
      userManager: null,
      activeProviderId: null,
    })
    localStorage.clear()
  })

  const verzeichnisdienst = {
    id: 'p-opaa',
    displayName: 'Verzeichnisdienst',
    issuerUri: 'https://idp.example.test/realms/opaa',
    clientId: 'opaa-frontend',
    isDefault: true,
    sortOrder: 0,
  }
  const partner = {
    id: 'p-partner',
    displayName: 'Partnerportal',
    issuerUri: 'https://partner.example.test/realms/extern',
    clientId: 'opaa-partner',
    isDefault: false,
    sortOrder: 1,
  }

  it('renders the product name as the page heading', () => {
    useAuthStore.setState({ mode: 'oidc' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.getByText('OPAA')).toBeInTheDocument()
  })

  /**
   * #583's reason for opening the read endpoint to unauthenticated callers (#582): the sign-in
   * page renders before there is a session and is the first thing anyone sees, so it has to carry
   * the operator's own mark rather than the OPAA standard.
   */
  it('carries the operator branding, logo included', () => {
    useAuthStore.setState({ mode: 'oidc' })
    useBrandingStore.setState({
      branding: {
        productName: 'Landesamt-Assistent',
        claim: 'Kurz und klar',
        primaryColor: '#0B6FBC',
        defaultColorScheme: 'LIGHT',
        logoUrl: '/api/v1/branding/logo?v=abc123',
      },
    })

    const { container } = renderWithProviders(<LoginPage />, { withRouter: true })

    expect(screen.getByText('Landesamt-Assistent')).toBeInTheDocument()
    expect(screen.getByText('Kurz und klar')).toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute('src', '/api/v1/branding/logo?v=abc123')
    expect(screen.queryByText('OPAA')).not.toBeInTheDocument()
  })

  it('falls back to the OPAA standard when nothing is configured', () => {
    useAuthStore.setState({ mode: 'oidc' })
    useBrandingStore.setState({ branding: OPAA_BRANDING })

    const { container } = renderWithProviders(<LoginPage />, { withRouter: true })

    expect(screen.getByText(OPAA_BRANDING.productName)).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  it('renders the directory-service sign-in as the primary action (mockup 1f)', () => {
    useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst] })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(
      screen.getByRole('button', { name: /anmelden über verzeichnisdienst/i }),
    ).toBeInTheDocument()
    expect(screen.getByText('Fragen. Belegen. Entscheiden.')).toBeInTheDocument()
  })

  it('offers no credential form — there is no password-based mode', () => {
    useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst] })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.queryByLabelText(/benutzername/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/passwort/i)).not.toBeInTheDocument()
  })

  it('displays a designed error state with a title', () => {
    useAuthStore.setState({
      mode: 'oidc',
      error: 'Die Authentifizierungskonfiguration konnte nicht geladen werden.',
    })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.getByText('Anmeldung fehlgeschlagen')).toBeInTheDocument()
    expect(
      screen.getByText('Die Authentifizierungskonfiguration konnte nicht geladen werden.'),
    ).toBeInTheDocument()
  })

  it('redirects away from login when already authenticated', () => {
    useAuthStore.setState({ mode: 'oidc', isAuthenticated: true })
    renderWithProviders(<LoginPage />, { withRouter: true, initialRoute: '/login' })
    expect(screen.queryByRole('button', { name: /mit sso anmelden/i })).not.toBeInTheDocument()
  })

  // ADR-0025, Entscheidung 5 / #1332: one button per enabled provider in the configured order;
  // the proposed one (last used, else the default) is the single primary action of the page.
  describe('several providers (#1332)', () => {
    it('with exactly one provider the page behaves as before', () => {
      useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst] })
      renderWithProviders(<LoginPage />, { withRouter: true })
      expect(screen.getAllByRole('button')).toHaveLength(2)
      expect(
        screen.getByRole('button', { name: /anmelden über verzeichnisdienst/i }),
      ).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Mit anderem Konto anmelden' })).toBeInTheDocument()
      expect(screen.queryByText('Zuletzt verwendet')).not.toBeInTheDocument()
    })

    it('shows both providers in order and starts the flow at the chosen one', async () => {
      const loginOidc = vi.fn().mockResolvedValue(undefined)
      useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst, partner], loginOidc })
      renderWithProviders(<LoginPage />, { withRouter: true })

      const buttons = screen.getAllByRole('button', { name: /anmelden über/i })
      expect(buttons.map((b) => b.textContent)).toEqual([
        'Anmelden über Verzeichnisdienst',
        'Anmelden über Partnerportal',
      ])
      // the default is the one primary (contained) button
      expect(buttons[0].className).toMatch(/MuiButton-contained/)
      expect(buttons[1].className).toMatch(/MuiButton-outlined/)

      await userEvent.click(buttons[1])
      expect(loginOidc).toHaveBeenCalledWith('p-partner')
    })

    it('proposes the provider used last and marks it', async () => {
      localStorage.setItem('opaa.oidc.lastProvider', 'p-partner')
      const loginOidc = vi.fn().mockResolvedValue(undefined)
      useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst, partner], loginOidc })
      renderWithProviders(<LoginPage />, { withRouter: true })

      const partnerButton = screen.getByRole('button', { name: /anmelden über partnerportal/i })
      expect(partnerButton.className).toMatch(/MuiButton-contained/)
      expect(screen.getByText('Zuletzt verwendet')).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: /anmelden über verzeichnisdienst/i }).className,
      ).toMatch(/MuiButton-outlined/)

      await userEvent.click(
        screen.getByRole('button', { name: 'Mit anderem Konto bei Partnerportal anmelden' }),
      )
      expect(loginOidc).toHaveBeenCalledWith('p-partner', { switchAccount: true })
    })

    it('shows no sign-in button while no provider is available', () => {
      useAuthStore.setState({
        mode: 'oidc',
        providers: [],
        error: 'Es ist kein Identitätsanbieter für die Anmeldung verfügbar.',
      })
      renderWithProviders(<LoginPage />, { withRouter: true })
      expect(screen.queryByRole('button', { name: /anmelden über/i })).not.toBeInTheDocument()
      expect(screen.getByText(/kein Identitätsanbieter/)).toBeInTheDocument()
    })
  })
})
