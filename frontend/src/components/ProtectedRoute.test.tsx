import { describe, it, expect, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
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
    })
  })

  it('renders children in mock mode', () => {
    useAuthStore.setState({ mode: 'mock', isAuthenticated: true, isLoading: false })
    renderWithProviders(
      <ProtectedRoute>
        <div>Protected Content</div>
      </ProtectedRoute>,
      { withRouter: true },
    )
    expect(screen.getByText('Protected Content')).toBeInTheDocument()
  })

  it('renders children when authenticated', () => {
    useAuthStore.setState({ mode: 'basic', isAuthenticated: true, isLoading: false })
    renderWithProviders(
      <ProtectedRoute>
        <div>Protected Content</div>
      </ProtectedRoute>,
      { withRouter: true },
    )
    expect(screen.getByText('Protected Content')).toBeInTheDocument()
  })

  it('shows loading spinner while loading', () => {
    useAuthStore.setState({ mode: 'basic', isAuthenticated: false, isLoading: true })
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
    useAuthStore.setState({ mode: 'basic', isAuthenticated: false, isLoading: false })
    renderWithProviders(
      <ProtectedRoute>
        <div>Protected Content</div>
      </ProtectedRoute>,
      { withRouter: true },
    )
    expect(screen.queryByText('Protected Content')).not.toBeInTheDocument()
  })
})
