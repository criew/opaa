import { render, screen, waitFor } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import App from './App'
import { useAuthStore } from './stores/authStore'

describe('App', () => {
  beforeEach(() => {
    useAuthStore.setState({
      mode: 'mock',
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

  it('redirects to chat page by default', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getByText('How can I help you today?')).toBeInTheDocument()
    })
  })

  it('renders navigation links', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getByText('Workspaces')).toBeInTheDocument()
      expect(screen.getByText('Chats')).toBeInTheDocument()
      expect(screen.getByText('Settings')).toBeInTheDocument()
    })
  })
})
