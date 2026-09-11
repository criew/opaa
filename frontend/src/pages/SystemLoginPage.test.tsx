import { describe, it, expect, beforeEach, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Route, Routes } from 'react-router'
import { server } from '../mocks/server'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import { OPAA_BRANDING, useBrandingStore } from '../stores/brandingStore'
import { LOCAL_ACCOUNTS_DISABLED } from '../types/auth'
import SystemLoginPage from './SystemLoginPage'

/**
 * #1368 / ADR-0033, Entscheidung 4 and 5: the system administrators' sign-in is reachable at all
 * times - it is the way back into an installation whose last identity provider is misconfigured -
 * and steps aside as soon as the regular page carries the mask itself.
 */
describe('SystemLoginPage', () => {
  const localEnabled = {
    enabled: true,
    selfRegistrationEnabled: true,
    passwordResetEnabled: true,
    passwordMinLength: 12,
  }

  // The store actions are real again for every test; a spy from the previous one would silently
  // make the next assertion about nothing.
  const { loginLocal } = useAuthStore.getState()

  beforeEach(() => {
    useBrandingStore.setState({ branding: OPAA_BRANDING })
    useAuthStore.setState({
      mode: 'oidc',
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: false,
      isSigningIn: false,
      error: null,
      providers: [],
      userManager: null,
      activeProviderId: null,
      localAccounts: LOCAL_ACCOUNTS_DISABLED,
      sessionKind: null,
      passwordChangeRequired: false,
      passwordChangeReason: null,
      loginLocal,
    })
  })

  it('offers the mask while the local account management is switched off', () => {
    renderWithProviders(<SystemLoginPage />, { withRouter: true })

    expect(screen.getByLabelText('E-Mail-Adresse')).toBeInTheDocument()
    expect(screen.getByLabelText('Passwort')).toBeInTheDocument()
    expect(screen.getByText(/Nur für lokale Systemverwalter-Konten/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Zur regulären Anmeldung' })).toHaveAttribute(
      'href',
      '/login',
    )
  })

  it('offers no self-service links', () => {
    renderWithProviders(<SystemLoginPage />, { withRouter: true })

    expect(screen.queryByRole('link', { name: 'Passwort vergessen?' })).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Konto registrieren' })).not.toBeInTheDocument()
  })

  it('signs in with the entered credentials', async () => {
    const loginLocal = vi.fn().mockResolvedValue(true)
    useAuthStore.setState({ loginLocal })
    renderWithProviders(<SystemLoginPage />, { withRouter: true })

    await userEvent.type(screen.getByLabelText('E-Mail-Adresse'), 'admin@opaa.local')
    await userEvent.type(screen.getByLabelText('Passwort'), 'notfall')
    await userEvent.click(screen.getByRole('button', { name: 'Anmelden' }))

    expect(loginLocal).toHaveBeenCalledWith('admin@opaa.local', 'notfall')
  })

  // ADR-0033, Entscheidung 9: a regular local account refused here reads exactly like a wrong
  // password - the page must not betray which of the two it was.
  it('shows the same sentence a wrong password gets when a regular account signs in here', async () => {
    server.use(http.post('/api/v1/auth/local/login', () => new HttpResponse(null, { status: 401 })))
    renderWithProviders(<SystemLoginPage />, { withRouter: true })

    await userEvent.type(screen.getByLabelText('E-Mail-Adresse'), 'erika.muster@stadt.example')
    await userEvent.type(screen.getByLabelText('Passwort'), 'richtig-aber-kein-admin')
    await userEvent.click(screen.getByRole('button', { name: 'Anmelden' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort.',
    )
  })

  it('waits for the configuration before showing the mask', () => {
    useAuthStore.setState({ isLoading: true })
    renderWithProviders(<SystemLoginPage />, { withRouter: true })

    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(screen.queryByLabelText('E-Mail-Adresse')).not.toBeInTheDocument()
  })

  it('sends the visitor to the regular sign-in once the management is switched on', () => {
    useAuthStore.setState({ localAccounts: localEnabled })
    renderWithProviders(
      <Routes>
        <Route path="/login/system" element={<SystemLoginPage />} />
        <Route path="/login" element={<div>Reguläre Anmeldung</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/login/system' },
    )

    expect(screen.getByText('Reguläre Anmeldung')).toBeInTheDocument()
  })

  it('leaves an established session alone', () => {
    useAuthStore.setState({ isAuthenticated: true })
    renderWithProviders(
      <Routes>
        <Route path="/login/system" element={<SystemLoginPage />} />
        <Route path="/chat" element={<div>Chat</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/login/system' },
    )

    expect(screen.getByText('Chat')).toBeInTheDocument()
  })
})
