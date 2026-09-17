import { describe, it, expect, beforeEach } from 'vitest'
import type { UserManager } from 'oidc-client-ts'
import { act, screen } from '@testing-library/react'
import { Route, Routes, useLocation } from 'react-router'
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
      signedOut: false,
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
  /** Shows the return target the login page would receive. */
  function LoginShowingTarget() {
    const location = useLocation()
    const from = (location.state as { from?: string } | null)?.from
    return <div>Ziel: {from ?? 'keins'}</div>
  }

  function renderDenied(route: string) {
    renderWithProviders(
      <Routes>
        <Route
          path="/libraries"
          element={
            <ProtectedRoute>
              <div>Protected Content</div>
            </ProtectedRoute>
          }
        />
        <Route path="/login" element={<LoginShowingTarget />} />
      </Routes>,
      { withRouter: true, initialRoute: route },
    )
  }

  // #1685: the page a denied route was on becomes the return target of the next sign-in ...
  it('hands the denied route on as the return target when the session is simply missing', () => {
    useAuthStore.setState({ mode: 'oidc', isAuthenticated: false, isLoading: false })
    renderDenied('/libraries?tab=2')
    expect(screen.getByText('Ziel: /libraries?tab=2')).toBeInTheDocument()
  })

  // ... but not after a deliberate sign-out: the next person in this tab may be somebody else.
  it('hands on no return target after a deliberate sign-out', () => {
    useAuthStore.setState({
      mode: 'oidc',
      isAuthenticated: false,
      isLoading: false,
      signedOut: true,
    })
    renderDenied('/libraries')
    expect(screen.getByText('Ziel: keins')).toBeInTheDocument()
  })

  // The race behind the demo smoke failure: at a provider without end_session_endpoint, logout()
  // falls back to removeUser(), which ends the session synchronously (UserUnloaded) - the page
  // left behind must not become the return target in that very render.
  it('hands on no return target when the session ends inside logout() itself', async () => {
    const userManager = {
      signoutRedirect: () => Promise.reject(new Error('no end_session_endpoint')),
      // the session ends at once, the removal itself completes later - the render in between is
      // the one that used to record the return target
      removeUser: () => {
        useAuthStore.setState({ token: null, isAuthenticated: false })
        return new Promise<void>((resolve) => setTimeout(resolve, 20))
      },
    } as unknown as UserManager
    useAuthStore.setState({
      mode: 'oidc',
      isAuthenticated: true,
      isLoading: false,
      sessionKind: 'oidc',
      userManager,
    })
    renderDenied('/libraries')
    expect(screen.getByText('Protected Content')).toBeInTheDocument()

    const loggingOut = useAuthStore.getState().logout()
    expect(await screen.findByText(/^Ziel: /)).toHaveTextContent('Ziel: keins')
    await act(() => loggingOut)
    expect(screen.getByText('Ziel: keins')).toBeInTheDocument()
  })

  it('forgets the sign-out mark as soon as the next session begins', () => {
    useAuthStore.setState({ isAuthenticated: false, signedOut: true })
    useAuthStore.setState({ isAuthenticated: true })
    expect(useAuthStore.getState().signedOut).toBe(false)
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
