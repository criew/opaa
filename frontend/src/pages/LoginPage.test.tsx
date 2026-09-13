import { describe, it, expect, beforeEach, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'
import LoginPage from './LoginPage'

describe('LoginPage', () => {
  // The store actions are real again for every test; a spy from the previous one would silently
  // make the next assertion about nothing.
  const { loginLocal, loginOidc } = useAuthStore.getState()

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
      localAccounts: LOCAL_ACCOUNTS_DISABLED,
      sessionKind: null,
      passwordChangeRequired: false,
      passwordChangeReason: null,
      loginLocal,
      loginOidc,
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

  /**
   * Die Überschrift benennt die Seite, die Marke steht daneben. Vorher war der Produktname selbst
   * das `h1` — ein Screenreader sagte damit „OPAA" statt zu sagen, wo man ist.
   */
  it('names the page in its heading and shows the mark beside it', () => {
    useAuthStore.setState({ mode: 'oidc' })
    renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

    expect(screen.getByRole('heading', { level: 1, name: 'Anmelden' })).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    // Die Marke ist eine Angabe, keine Überschriftenebene.
    expect(screen.getByText('OPAA')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'OPAA' })).toBeNull()
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

    const { container } = renderWithProviders(<LoginPage />, {
      withRouter: true,
      withNotificationHost: false,
    })

    expect(screen.getByText('Landesamt-Assistent')).toBeInTheDocument()
    expect(screen.getByText('Kurz und klar')).toBeInTheDocument()
    expect(container.querySelector('img')).toHaveAttribute('src', '/api/v1/branding/logo?v=abc123')
    expect(screen.queryByText('OPAA')).not.toBeInTheDocument()
  })

  it('falls back to the OPAA standard when nothing is configured', () => {
    useAuthStore.setState({ mode: 'oidc' })
    useBrandingStore.setState({ branding: OPAA_BRANDING })

    const { container } = renderWithProviders(<LoginPage />, {
      withRouter: true,
      withNotificationHost: false,
    })

    expect(screen.getByText(OPAA_BRANDING.productName)).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  it('renders the directory-service sign-in as the primary action (mockup 1f)', () => {
    useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst] })
    renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })
    expect(
      screen.getByRole('button', { name: /anmelden bei verzeichnisdienst/i }),
    ).toBeInTheDocument()
    expect(screen.getByText('Fragen. Belegen. Entscheiden.')).toBeInTheDocument()
    // the choice is one named group; the trust line explains where the password goes (#1369)
    expect(screen.getByRole('group', { name: 'Anmeldung' })).toBeInTheDocument()
    expect(screen.getByText(/OPAA erhält kein Kennwort/)).toBeInTheDocument()
  })

  it('offers no credential form — there is no password-based mode', () => {
    useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst] })
    renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })
    expect(screen.queryByLabelText(/benutzername/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/passwort/i)).not.toBeInTheDocument()
  })

  it('displays a designed error state with a title', () => {
    useAuthStore.setState({
      mode: 'oidc',
      error: 'Die Authentifizierungskonfiguration konnte nicht geladen werden.',
    })
    renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })
    expect(screen.getByText('Anmeldung fehlgeschlagen')).toBeInTheDocument()
    expect(
      screen.getByText('Die Authentifizierungskonfiguration konnte nicht geladen werden.'),
    ).toBeInTheDocument()
  })

  it('redirects away from login when already authenticated', () => {
    useAuthStore.setState({ mode: 'oidc', isAuthenticated: true })
    renderWithProviders(<LoginPage />, {
      withRouter: true,
      initialRoute: '/login',
      withNotificationHost: false,
    })
    expect(screen.queryByRole('button', { name: /mit sso anmelden/i })).not.toBeInTheDocument()
  })

  // ADR-0025, Entscheidung 5 / #1332: one button per enabled provider in the configured order;
  // the proposed one (last used, else the default) is the single primary action of the page.
  describe('several providers (#1332)', () => {
    it('with exactly one provider the page behaves as before', () => {
      useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst] })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })
      expect(screen.getAllByRole('button')).toHaveLength(2)
      expect(
        screen.getByRole('button', { name: /anmelden bei verzeichnisdienst/i }),
      ).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Mit anderem Konto anmelden' })).toBeInTheDocument()
      expect(screen.queryByText('Zuletzt verwendet')).not.toBeInTheDocument()
    })

    it('shows both providers in order and starts the flow at the chosen one', async () => {
      const loginOidc = vi.fn().mockResolvedValue(undefined)
      useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst, partner], loginOidc })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      const buttons = screen.getAllByRole('button', { name: /anmelden bei/i })
      expect(buttons).toHaveLength(2)
      expect(buttons[0]).toHaveAccessibleName('Anmelden bei Verzeichnisdienst')
      expect(buttons[1]).toHaveAccessibleName('Anmelden bei Partnerportal')
      // the host of the issuer is the line a person recognises their provider by
      expect(screen.getByText('idp.example.test')).toBeInTheDocument()
      expect(screen.getByText('partner.example.test')).toBeInTheDocument()
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
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      const partnerButton = screen.getByRole('button', { name: /anmelden bei partnerportal/i })
      expect(partnerButton.className).toMatch(/MuiButton-contained/)
      expect(screen.getByText('Zuletzt verwendet')).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: /anmelden bei verzeichnisdienst/i }).className,
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
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })
      expect(screen.queryByRole('button', { name: /anmelden bei/i })).not.toBeInTheDocument()
      expect(screen.getByText(/kein Identitätsanbieter/)).toBeInTheDocument()
    })
  })
  // ADR-0033, Entscheidung 4 / #1368: the mask appears only while the local account management is
  // switched on; the identity providers keep their place above it.
  describe('accounts of this installation (#1539)', () => {
    const localEnabled = {
      enabled: true,
      selfRegistrationEnabled: false,
      passwordResetEnabled: false,
      passwordMinLength: 12,
    }

    it('shows no mask while the management is switched off', () => {
      useAuthStore.setState({ mode: 'oidc', providers: [verzeichnisdienst] })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })
      expect(screen.queryByLabelText('Passwort')).not.toBeInTheDocument()
      expect(
        screen.getByRole('link', { name: 'Anmeldung für die Systemverwaltung' }),
      ).toHaveAttribute('href', '/login/system')
    })

    it('shows both sections, providers first, when both ways exist', () => {
      useAuthStore.setState({
        mode: 'oidc',
        providers: [verzeichnisdienst],
        localAccounts: localEnabled,
      })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
      expect(headings).toEqual(['Mit Identitätsanbieter', 'Mit Konto dieser Installation'])
      expect(screen.getByLabelText('E-Mail-Adresse')).toBeInTheDocument()
      expect(screen.getByLabelText('Passwort')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Anmelden' })).toBeInTheDocument()
      // the quiet link is pointless once the regular page carries the mask
      expect(
        screen.queryByRole('link', { name: 'Anmeldung für die Systemverwaltung' }),
      ).not.toBeInTheDocument()
      expect(screen.getByText(/erhält OPAA kein Kennwort/)).toBeInTheDocument()
      expect(screen.getByText(/nicht umkehrbaren Prüfwert/)).toBeInTheDocument()
    })

    it('shows the mask alone when there is no provider', () => {
      useAuthStore.setState({ mode: 'oidc', providers: [], localAccounts: localEnabled })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      expect(screen.getByRole('heading', { level: 2 })).toHaveTextContent('Anmeldung')
      expect(screen.getByLabelText('E-Mail-Adresse')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /anmelden bei/i })).not.toBeInTheDocument()
    })

    it('offers the self-service links only where the configuration allows them', () => {
      useAuthStore.setState({
        mode: 'oidc',
        providers: [],
        localAccounts: {
          ...localEnabled,
          passwordResetEnabled: true,
          selfRegistrationEnabled: true,
        },
      })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      expect(screen.getByRole('link', { name: 'Passwort vergessen?' })).toHaveAttribute(
        'href',
        '/forgot-password',
      )
      expect(screen.getByRole('link', { name: 'Konto registrieren' })).toHaveAttribute(
        'href',
        '/register',
      )
    })

    it('hides the self-service links while the flows are unavailable', () => {
      useAuthStore.setState({ mode: 'oidc', providers: [], localAccounts: localEnabled })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      expect(screen.queryByRole('link', { name: 'Passwort vergessen?' })).not.toBeInTheDocument()
      expect(screen.queryByRole('link', { name: 'Konto registrieren' })).not.toBeInTheDocument()
    })

    it('signs in with the entered credentials', async () => {
      const loginLocal = vi.fn().mockResolvedValue(true)
      useAuthStore.setState({
        mode: 'oidc',
        providers: [],
        localAccounts: localEnabled,
        loginLocal,
      })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      await userEvent.type(screen.getByLabelText('E-Mail-Adresse'), '  erika@stadt.example  ')
      await userEvent.type(screen.getByLabelText('Passwort'), 'geheim')
      await userEvent.click(screen.getByRole('button', { name: 'Anmelden' }))

      expect(loginLocal).toHaveBeenCalledWith('erika@stadt.example', 'geheim')
    })

    it('shows the password on demand and hides it again', async () => {
      useAuthStore.setState({ mode: 'oidc', providers: [], localAccounts: localEnabled })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      expect(screen.getByLabelText('Passwort')).toHaveAttribute('type', 'password')
      await userEvent.click(screen.getByRole('button', { name: 'Passwort anzeigen' }))
      expect(screen.getByLabelText('Passwort')).toHaveAttribute('type', 'text')
      await userEvent.click(screen.getByRole('button', { name: 'Passwort verbergen' }))
      expect(screen.getByLabelText('Passwort')).toHaveAttribute('type', 'password')
    })

    it('puts the focus back into the first field after a refusal', async () => {
      const loginLocal = vi.fn().mockImplementation(async () => {
        useAuthStore.setState({
          error: 'Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort.',
        })
        return false
      })
      useAuthStore.setState({
        mode: 'oidc',
        providers: [],
        localAccounts: localEnabled,
        loginLocal,
      })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      await userEvent.type(screen.getByLabelText('E-Mail-Adresse'), 'erika@stadt.example')
      await userEvent.type(screen.getByLabelText('Passwort'), 'falsch')
      await userEvent.click(screen.getByRole('button', { name: 'Anmelden' }))

      expect(screen.getByRole('alert')).toHaveTextContent(
        'Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort.',
      )
      expect(screen.getByLabelText('E-Mail-Adresse')).toHaveFocus()
      expect(screen.getByLabelText('Passwort')).toHaveValue('')
    })

    it('points at the system administrators sign-in when nothing else is offered', () => {
      useAuthStore.setState({ mode: 'oidc', providers: [] })
      renderWithProviders(<LoginPage />, { withRouter: true, withNotificationHost: false })

      expect(screen.getByText(/keine Anmeldung eingerichtet/)).toBeInTheDocument()
      expect(
        screen.getByRole('link', { name: 'Anmeldung für die Systemverwaltung' }),
      ).toBeInTheDocument()
    })
  })
})
