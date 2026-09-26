import { describe, expect, it, beforeEach } from 'vitest'
import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router'
import { renderWithProviders } from '../test/test-utils'
import SpacesOverviewPage from './SpacesOverviewPage'
import { useSpaceStore } from '../stores/spaceStore'

const personal = {
  id: 'space-personal',
  name: 'Mein Space',
  description: 'Eigener Denkraum ohne Mitleser.',
  isDefault: true,
  archived: false,
  visibility: 'PRIVATE' as const,
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
  visibility: 'PRIVATE' as const,
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
  })

  it('heads the overview with the space count (#1914)', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    expect(screen.getByRole('heading', { level: 1, name: '2 Spaces' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neuer Space' })).toBeInTheDocument()
  })

  it('renders one linked card per space with figures, role and member badge (#1914, #1970)', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const personalCard = screen.getByRole('link', { name: /Mein Space/ })
    expect(personalCard).toHaveAttribute('href', '/spaces/space-personal/chats/new')
    expect(personalCard).toHaveTextContent('Eigener Denkraum ohne Mitleser.')
    expect(personalCard).toHaveTextContent('12 Chats')
    expect(within(personalCard).getByText('nur Sie')).toBeInTheDocument()
    expect(personalCard).toHaveTextContent('Administrator')

    const teamCard = screen.getByRole('link', { name: /Widerspruchsstelle/ })
    // An archived space rejects new chats server-side (ChatService), so its card leads to the
    // space overview rather than to a draft whose first message would fail.
    expect(teamCard).toHaveAttribute('href', '/spaces/space-team')
    expect(teamCard).toHaveTextContent('1 Chat')
    expect(within(teamCard).getByText('2 Gruppen, 7 Personen')).toBeInTheDocument()
    expect(teamCard).toHaveTextContent('Archiviert')
  })

  it('drops the type marking and the source figure from the card (#1914)', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    expect(screen.queryByText('Persönlich')).not.toBeInTheDocument()
    expect(screen.queryByText('Team')).not.toBeInTheDocument()
    expect(screen.queryByText(/Quelle/)).not.toBeInTheDocument()
  })

  it('reads "nur Sie" in a non-default space whose only member is the caller (#1970)', () => {
    const alone = {
      ...team,
      archived: false,
      memberCount: 1,
      memberships: { groupCount: 0, userCount: 1 },
    }
    useSpaceStore.setState({ spaces: [alone], isLoadingList: false, error: null })
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const card = screen.getByRole('link', { name: /Widerspruchsstelle/ })
    expect(within(card).getByText('nur Sie')).toBeInTheDocument()
  })

  it('names a single group membership as a group, never as "nur Sie" (#1815)', () => {
    const groupOnly = {
      ...team,
      archived: false,
      memberCount: 1,
      memberships: { groupCount: 1, userCount: 0 },
    }
    useSpaceStore.setState({ spaces: [groupOnly], isLoadingList: false, error: null })
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const card = screen.getByRole('link', { name: /Widerspruchsstelle/ })
    expect(within(card).getByText('1 Gruppe')).toBeInTheDocument()
    expect(card).not.toHaveTextContent('nur Sie')
  })

  it('leaves the chat figure out when the list API carries none (#682)', () => {
    const withoutFigures = { ...team, chatCount: undefined }
    useSpaceStore.setState({ spaces: [withoutFigures], isLoadingList: false, error: null })
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const teamCard = screen.getByRole('link', { name: /Widerspruchsstelle/ })
    expect(within(teamCard).getByText('2 Gruppen, 7 Personen')).toBeInTheDocument()
    expect(teamCard).not.toHaveTextContent('Chat')
  })

  it('offers a table view with one row per space (#1913)', async () => {
    const user = userEvent.setup()
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    await user.click(screen.getByRole('button', { name: 'Tabelle' }))

    for (const head of ['Name', 'Chats', 'Mitglieder', 'Ihre Rolle', 'Zustand']) {
      expect(screen.getByRole('columnheader', { name: head })).toBeInTheDocument()
    }
    expect(screen.getByRole('cell', { name: 'nur Sie' })).toBeInTheDocument()
    expect(screen.getByRole('cell', { name: '2 Gruppen, 7 Personen' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Widerspruchsstelle' })).toHaveAttribute(
      'href',
      '/spaces/space-team',
    )
    expect(screen.getByText('Archiviert')).toBeInTheDocument()
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
    // loadSpaces stubben - der Auto-Load der Seite würde sonst sofort Mock-Spaces nachladen
    // und den Leerzustand verdecken.
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
