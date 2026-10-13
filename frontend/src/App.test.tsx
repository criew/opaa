import { render, screen, waitFor, within } from '@testing-library/react'
import { beforeEach, describe, expect, it } from 'vitest'
import App from './App'
import { useAuthStore } from './stores/authStore'
import { setMockFeatures } from './mocks/authFixtures'

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
      features: {},
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
    // #2504: one entry for the connections area instead of three.
    const navigation = screen.getByRole('navigation', { name: 'Administration' })
    expect(within(navigation).getByRole('link', { name: 'Anbindungen' })).toHaveAttribute(
      'href',
      '/admin/connections',
    )
    expect(within(navigation).queryByRole('link', { name: /Fremdzug/ })).not.toBeInTheDocument()
    window.history.pushState({}, '', '/')
  })

  it('renders navigation links', async () => {
    render(<App />)
    await waitFor(() => {
      expect(screen.getByText('Chats')).toBeInTheDocument()
      // Ein Einstieg für alle Asset-Typen (ADR-0039, Entscheidung 1).
      expect(screen.getByText('Katalog')).toBeInTheDocument()
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

  // ADR-0039: the former type overviews lead into the catalog, narrowed to their type.
  it.each([
    ['/libraries', '?type=knowledge'],
    ['/prompts', '?type=prompts'],
  ])('sends a bookmark of %s to the catalog', async (path, search) => {
    window.history.pushState({}, '', path)
    render(<App />)
    await waitFor(() => {
      expect(window.location.pathname).toBe('/catalog')
    })
    expect(window.location.search).toBe(search)
    window.history.pushState({}, '', '/')
  })

  // #2588: switched off, no prompt page exists; every former address leads to the bare catalog.
  it.each(['/prompts', '/prompts/new', '/prompts/prompt-library-referat-50/freigaben'])(
    'sends %s to the catalog while prompt libraries are switched off',
    async (path) => {
      // As after sign-in: the flags are known before the first protected route renders.
      useAuthStore.setState({ features: { promptLibraries: false } })
      setMockFeatures({ promptLibraries: false })
      window.history.pushState({}, '', path)
      render(<App />)
      await waitFor(() => {
        expect(window.location.pathname).toBe('/catalog')
      })
      expect(window.location.search).toBe('')
      window.history.pushState({}, '', '/')
    },
  )

  // #2504: Zugänge, Fremdzugänge und Fremdzugangsfreigaben sind ein Bereich „Anbindungen“. Ein
  // Lesezeichen auf eine frühere Seite landet am selben Inhalt.
  it.each([
    ['/admin/connection-profiles', '/admin/connections/access', '', ''],
    ['/admin/connections', '/admin/connections/access', '', ''],
    ['/admin/external-access', '/admin/connections/external-tools', '?panel=settings', ''],
    ['/admin/external-access/channel', '/admin/connections/external-tools', '?panel=settings', ''],
    ['/admin/external-access/tokens', '/admin/connections/external-tools', '', '#tokens'],
    ['/admin/external-access/unbekannt', '/admin/connections/external-tools', '', ''],
    ['/admin/library-releases', '/admin/connections/external-tools', '', '#libraries'],
  ])('sends a bookmark of %s into the connections area', async (path, pathname, search, hash) => {
    useAuthStore.setState({
      user: {
        id: 'admin-1',
        displayName: 'Dev Admin',
        email: 'admin@opaa.local',
        systemRole: 'SYSTEM_ADMIN',
      },
    })
    window.history.pushState({}, '', path)
    render(<App />)
    // One check of the whole address: the auth bootstrap of this test file may send the page on
    // to the login afterwards, which says nothing about the redirect.
    await waitFor(() => {
      expect(`${window.location.pathname}${window.location.search}${window.location.hash}`).toBe(
        `${pathname}${search}${hash}`,
      )
    })
    window.history.pushState({}, '', '/')
  })
})
