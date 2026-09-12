import { describe, it, expect, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import { useAuthStore } from '../stores/authStore'
import ProtectedRoute from './ProtectedRoute'

describe('ProtectedRoute', () => {
  beforeEach(() => {
    useAuthStore.setState({
      mode: null,
      user: null,
      token: null,
      isAuthenticated: false,
      isLoading: false,
      error: null,
      userManager: null,
      sessionKind: null,
      passwordChangeRequired: false,
      passwordChangeReason: null,
    })
  })

  it('renders children when authenticated in dev mode', () => {
    useAuthStore.setState({ mode: 'dev', isAuthenticated: true, isLoading: false })
    renderWithProviders(
      <ProtectedRoute>
        <div>Protected Content</div>
      </ProtectedRoute>,
      { withRouter: true },
    )
    expect(screen.getByText('Protected Content')).toBeInTheDocument()
  })

  it('renders children when authenticated', () => {
    useAuthStore.setState({ mode: 'oidc', isAuthenticated: true, isLoading: false })
    renderWithProviders(
      <ProtectedRoute>
        <div>Protected Content</div>
      </ProtectedRoute>,
      { withRouter: true },
    )
    expect(screen.getByText('Protected Content')).toBeInTheDocument()
  })

  it('shows loading spinner while loading', () => {
    useAuthStore.setState({ mode: 'oidc', isAuthenticated: false, isLoading: true })
    renderWithProviders(
      <ProtectedRoute>
        <div>Protected Content</div>
      </ProtectedRoute>,
      { withRouter: true },
    )
    expect(screen.getByRole('progressbar')).toBeInTheDocument()
    expect(screen.queryByText('Protected Content')).not.toBeInTheDocument()
  })

  it('redirects to login when not authenticated', () => {
    useAuthStore.setState({ mode: 'oidc', isAuthenticated: false, isLoading: false })
    renderWithProviders(
      <Routes>
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <div>Protected Content</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<div>Login Screen</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/' },
    )
    expect(screen.queryByText('Protected Content')).not.toBeInTheDocument()
    expect(screen.getByText('Login Screen')).toBeInTheDocument()
  })
  // ADR-0033, Entscheidung 8: while the account owes a new password, the backend answers every
  // other route with 403 - the shell would be a frame around nothing but errors.
  it('sends an account that owes a new password to the password page', () => {
    useAuthStore.setState({
      mode: 'oidc',
      sessionKind: 'local',
      isAuthenticated: true,
      isLoading: false,
      passwordChangeRequired: true,
    })
    renderWithProviders(
      <Routes>
        <Route
          path="/"
          element={
            <ProtectedRoute>
              <div>Protected Content</div>
            </ProtectedRoute>
          }
        />
        <Route path="/account/password" element={<div>Passwort ändern</div>} />
      </Routes>,
      { withRouter: true, initialRoute: '/' },
    )
    expect(screen.queryByText('Protected Content')).not.toBeInTheDocument()
    expect(screen.getByText('Passwort ändern')).toBeInTheDocument()
  })

  it('lets the session through again once the password stands', () => {
    useAuthStore.setState({
      mode: 'oidc',
      sessionKind: 'local',
      isAuthenticated: true,
      isLoading: false,
      passwordChangeRequired: false,
    })
    renderWithProviders(
      <ProtectedRoute>
        <div>Protected Content</div>
      </ProtectedRoute>,
      { withRouter: true },
    )
    expect(screen.getByText('Protected Content')).toBeInTheDocument()
  })
})
