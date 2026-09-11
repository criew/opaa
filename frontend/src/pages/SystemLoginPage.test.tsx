import { describe, it, expect, beforeEach, vi } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
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

  it('shows the same sentence a wrong password gets when a regular account signs in here', () => {
    useAuthStore.setState({
      error: 'Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort.',
    })
    renderWithProviders(<SystemLoginPage />, { withRouter: true })

    expect(screen.getByRole('alert')).toHaveTextContent(
      'Anmeldung nicht möglich. Prüfen Sie E-Mail-Adresse und Passwort.',
    )
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
