import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import App from './App'
import { useAuthStore } from './stores/authStore'

describe('App', () => {
  beforeEach(() => {
    useAuthStore.setState({
      mode: 'dev',
      isAuthenticated: true,
      isLoading: false,
      user: null,
      token: null,
      error: null,
      userManager: null,
    })
  })

  it('renders the OPAA branding', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getAllByText('OPAA').length).toBeGreaterThan(0)
    })
  })

  it('redirects to the default space and its most recently used chat by default', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getByPlaceholderText('Stellen Sie eine Frage …')).toBeInTheDocument()
    })
  })

  it('renders navigation links', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getByText('Spaces')).toBeInTheDocument()
      expect(screen.getByText('Chats')).toBeInTheDocument()
      expect(screen.getByText('Einstellungen')).toBeInTheDocument()
    })
  })
})
