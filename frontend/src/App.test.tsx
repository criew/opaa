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

  it('redirects to an empty chat in the default space by default', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getByPlaceholderText('Nachricht eingeben …')).toBeInTheDocument()
    })
    // Not merely "a chat page rendered": the route must be the not-yet-persisted draft, and the
    // draft's empty state must be what the user sees - no messages of an earlier conversation.
    expect(window.location.pathname).toMatch(/^\/spaces\/[^/]+\/chats\/new$/)
    expect(
      screen.getByRole('heading', { name: 'Womit kann ich Ihnen heute helfen?' }),
    ).toBeInTheDocument()
  })

  it('shows the administration column to a SYSTEM_ADMIN (#805)', async () => {
    // Positive half of llm-model-management.spec.ts Szenario 3's landmark absence: the same
    // role gate (AdminAreaLayout) must produce the named landmark for an admin.
    useAuthStore.setState({
      user: {
        id: 'admin-1',
        displayName: 'Dev Admin',
        email: 'admin@opaa.local',
        systemRole: 'SYSTEM_ADMIN',
      },
    })
    window.history.pushState({}, '', '/admin/groups')
    render(<App />)
    await waitFor(() => {
      expect(screen.getByRole('navigation', { name: 'Administration' })).toBeInTheDocument()
    })
    window.history.pushState({}, '', '/')
  })

  it('renders navigation links', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getByText('Chats')).toBeInTheDocument()
      // Der Bestand je Asset-Typ steht als eigener Punkt auf der globalen Leiste (#786, #1915).
      expect(screen.getByText('Wissen')).toBeInTheDocument()
    })
  })

  // #1917: Die Verwaltungsseite heißt jetzt „Einstellungen" und ist in Reiter gegliedert. Ein
  // Lesezeichen auf die alte Adresse darf deshalb nicht ins Leere laufen.
  it('sends a bookmark of the former management route to the first settings tab', async () => {
    window.history.pushState({}, '', '/spaces/space-1/manage')
    render(<App />)
    await waitFor(() => {
      expect(window.location.pathname).toBe('/spaces/space-1/settings/general')
    })
    window.history.pushState({}, '', '/')
  })
})
