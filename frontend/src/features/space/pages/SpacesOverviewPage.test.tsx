import { describe, expect, it, beforeEach } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { renderWithProviders } from '@/test/test-utils'
import SpacesOverviewPage from './SpacesOverviewPage'
import { useSpaceStore } from '../stores/spaceStore'
import { SPACE_SEARCH_HANDOVER } from '../utils/spaceSearchHandover'

const personal = {
  id: 'space-personal',
  name: 'Mein Space',
  description: 'Eigener Denkraum ohne Mitleser.',
  isDefault: true,
  archived: false,
  memberCount: 1,
  memberships: { groupCount: 0, userCount: 1 },
  libraryCount: 3,
  chatCount: 12,
  userRole: 'ADMIN' as const,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const team = {
  id: 'space-team',
  name: 'Widerspruchsstelle',
  description: 'Bearbeitung laufender Widersprüche.',
  isDefault: false,
  archived: true,
  memberCount: 9,
  memberships: { groupCount: 2, userCount: 7 },
  libraryCount: 1,
  chatCount: 1,
  userRole: 'CURATOR' as const,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

describe('SpacesOverviewPage (#593, Mockup 1c)', () => {
  beforeEach(() => {
    window.localStorage.clear()
    useSpaceStore.setState({ spaces: [personal, team], isLoadingList: false, error: null })
    server.use(http.get('/api/v1/spaces', () => HttpResponse.json([personal, team])))
  })

  // regression guard for #2377: a chat created, deleted or archived elsewhere changes chatCount,
  // so the overview must re-read the list on every visit instead of trusting the cached store.
  it('re-reads the space list on mount even when spaces are already cached (#2377)', async () => {
    useSpaceStore.setState({ spaces: [{ ...personal, chatCount: 1 }, team] })
    server.use(
      http.get('/api/v1/spaces', () => HttpResponse.json([{ ...personal, chatCount: 2 }, team])),
    )

    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const personalCard = screen.getByRole('link', { name: /Mein Space/ })
    expect(personalCard).toHaveTextContent('1 Chat')
    await waitFor(() => expect(personalCard).toHaveTextContent('2 Chats'))
  })

  // Guidelines 5.13: the sidebar's search button opens the overview as the space search, with the
  // cursor in its field - like the chat search.
  it('puts the cursor into the search field when opened as the space search', async () => {
    renderWithProviders(
      <MemoryRouter initialEntries={[{ pathname: '/spaces', state: SPACE_SEARCH_HANDOVER }]}>
        <Routes>
          <Route path="/spaces" element={<SpacesOverviewPage />} />
        </Routes>
      </MemoryRouter>,
    )

    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Suchen' })).toHaveFocus())
  })

  it('leaves the focus alone when opened without the handover', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true, initialRoute: '/spaces' })

    expect(screen.getByRole('textbox', { name: 'Suchen' })).not.toHaveFocus()
  })

  it('heads the overview like the catalog: "Spaces" with the quiet entry count (#3263)', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Spaces' })).toBeInTheDocument()
    expect(screen.getByText('2 Einträge')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neuer Space' })).toBeInTheDocument()
  })

  it('renders one linked card per space with name, description and chat count (#3263)', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const personalCard = screen.getByRole('link', { name: /Mein Space/ })
    expect(personalCard).toHaveAttribute('href', '/spaces/space-personal/chats/new')
    expect(personalCard).toHaveTextContent(/^Mein SpaceEigener Denkraum ohne Mitleser\.12 Chats$/)

    const teamCard = screen.getByRole('link', { name: /Widerspruchsstelle/ })
    // An archived space rejects new chats server-side (ChatService), so its card leads to the
    // space overview rather than to a draft whose first message would fail.
    expect(teamCard).toHaveAttribute('href', '/spaces/space-team')
    expect(teamCard).toHaveTextContent('1 Chat')
    expect(teamCard).toHaveTextContent('Archiviert')
  })

  // Role and members belong to the space settings, not to the card.
  it('shows neither the own role nor the members on the card (#3263)', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    for (const card of screen.getAllByRole('link')) {
      expect(card).not.toHaveTextContent(/Administrator|Kurator|Mitglied/)
      expect(card).not.toHaveTextContent(/nur Sie|Gruppe|Person/)
    }
  })

  it('drops the type marking and the source figure from the card (#1914)', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    expect(screen.queryByText('Persönlich')).not.toBeInTheDocument()
    expect(screen.queryByText('Team')).not.toBeInTheDocument()
    expect(screen.queryByText(/Quelle/)).not.toBeInTheDocument()
  })

  it('leaves the chat figure out when the list API carries none (#682)', () => {
    const withoutFigures = { ...team, chatCount: undefined }
    useSpaceStore.setState({ spaces: [withoutFigures], isLoadingList: false, error: null })
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const teamCard = screen.getByRole('link', { name: /Widerspruchsstelle/ })
    expect(teamCard).toHaveTextContent('Bearbeitung laufender Widersprüche.')
    expect(teamCard).not.toHaveTextContent('Chat')
  })

  // ADR-0039, Entscheidung 1: like the catalog, the Spaces overview shows only cards.
  it('shows only cards, without a table view', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    expect(screen.getAllByRole('link')).toHaveLength(2)
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Tabelle' })).not.toBeInTheDocument()
  })

  it('filters the cards by name and description (#1913)', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    await user.type(screen.getByRole('textbox', { name: 'Suchen' }), 'Mitleser')

    expect(screen.getByRole('link', { name: /Mein Space/ })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: /Widerspruchsstelle/ })).not.toBeInTheDocument()
  })

  it('navigates to the create wizard from the header button (#1913)', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/" element={<SpacesOverviewPage />} />
        <Route path="/spaces/new" element={<div data-testid="create-wizard-route" />} />
      </Routes>,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: 'Neuer Space' }))

    expect(await screen.findByTestId('create-wizard-route')).toBeInTheDocument()
  })

  it('shows the empty state without the former create tile (#1913)', () => {
    // Stub loadSpaces - the page's auto-load would otherwise immediately load mock spaces
    // and hide the empty state.
    useSpaceStore.setState({
      spaces: [],
      isLoadingList: false,
      error: null,
      loadSpaces: async () => {},
    })
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    expect(screen.getByText(/Noch kein Space/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Neuen Space anlegen' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neuer Space' })).toBeInTheDocument()
  })
})
