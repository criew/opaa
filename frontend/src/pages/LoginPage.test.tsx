import { describe, it, expect, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
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
    useAuthStore.setState({ mode: 'basic' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.getByText('OPAA')).toBeInTheDocument()
  })

  it('renders login form for basic mode', () => {
    useAuthStore.setState({ mode: 'basic' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument()
  })

  it('renders SSO button for oidc mode', () => {
    useAuthStore.setState({ mode: 'oidc' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.getByRole('button', { name: /sign in with sso/i })).toBeInTheDocument()
  })

  it('displays error message', () => {
    useAuthStore.setState({ mode: 'basic', error: 'Invalid credentials' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    expect(screen.getByText('Invalid credentials')).toBeInTheDocument()
  })

  it('submits basic login form', async () => {
    useAuthStore.setState({ mode: 'basic' })
    renderWithProviders(<LoginPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.type(screen.getByLabelText(/username/i), 'admin')
    await user.type(screen.getByLabelText(/password/i), 'admin')
    await user.click(screen.getByRole('button', { name: /sign in/i }))

    // After successful login via MSW, user should be authenticated
    const state = useAuthStore.getState()
    expect(state.isAuthenticated).toBe(true)
  })

  it('redirects away from login when already authenticated', () => {
    useAuthStore.setState({ mode: 'basic', isAuthenticated: true })
    renderWithProviders(<LoginPage />, { withRouter: true, initialRoute: '/login' })
    expect(screen.queryByRole('button', { name: /sign in/i })).not.toBeInTheDocument()
  })
})
