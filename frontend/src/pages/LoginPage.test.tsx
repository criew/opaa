import { describe, it, expect, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import LoginPage from './LoginPage'

describe('LoginPage', () => {
  beforeEach(() => {
    useAuthStore.setState({
      mode: null,
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
      userManager: null,
    })
  })

  it('renders OPAA title', () => {
    useAuthStore.setState({ mode: 'oidc' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.getByText('OPAA')).toBeInTheDocument()
  })

  it('renders SSO button for oidc mode', () => {
    useAuthStore.setState({ mode: 'oidc' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.getByRole('button', { name: /mit sso anmelden/i })).toBeInTheDocument()
  })

  it('offers no credential form — there is no password-based mode', () => {
    useAuthStore.setState({ mode: 'oidc' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.queryByLabelText(/benutzername/i)).not.toBeInTheDocument()
    expect(screen.queryByLabelText(/passwort/i)).not.toBeInTheDocument()
  })

  it('displays error message', () => {
    useAuthStore.setState({
      mode: 'oidc',
      error: 'Die Authentifizierungskonfiguration konnte nicht geladen werden.',
    })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(
      screen.getByText('Die Authentifizierungskonfiguration konnte nicht geladen werden.'),
    ).toBeInTheDocument()
  })

  it('redirects away from login when already authenticated', () => {
    useAuthStore.setState({ mode: 'oidc', isAuthenticated: true })
    renderWithProviders(<LoginPage />, { withRouter: true, initialRoute: '/login' })
    expect(screen.queryByRole('button', { name: /mit sso anmelden/i })).not.toBeInTheDocument()
  })
})
