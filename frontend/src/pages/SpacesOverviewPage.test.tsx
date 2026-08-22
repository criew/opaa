import { describe, expect, it, beforeEach } from 'vitest'
import { screen } from '@testing-library/react'
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
  libraryCount: 1,
  chatCount: 1,
  userRole: 'CURATOR' as const,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

describe('SpacesOverviewPage (#593, Mockup 1c)', () => {
  beforeEach(() => {
    useSpaceStore.setState({ spaces: [personal, team], isLoadingList: false, error: null })
  })

  it('renders the header with the membership count line', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    expect(screen.getByRole('heading', { level: 1, name: 'Spaces' })).toBeInTheDocument()
    expect(screen.getByText('2 Räume, in denen Sie Mitglied sind')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neuer Space' })).toBeInTheDocument()
  })

  it('renders one linked card per space with kind, description, figures and role', () => {
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const personalCard = screen.getByRole('link', { name: /Mein Space/ })
    expect(personalCard).toHaveAttribute('href', '/spaces/space-personal')
    expect(personalCard).toHaveTextContent('Persönlich')
    expect(personalCard).toHaveTextContent('Eigener Denkraum ohne Mitleser.')
    expect(personalCard).toHaveTextContent('3 Quellen · 12 Chats · nur Sie')

    const teamCard = screen.getByRole('link', { name: /Widerspruchsstelle/ })
    expect(teamCard).toHaveTextContent('Team')
    expect(teamCard).toHaveTextContent('1 Quelle · 1 Chat · 9 Mitglieder')
    expect(teamCard).toHaveTextContent('Archiviert')
  })

  it('falls back to the member count when the list API carries no source/chat figures (#682)', () => {
    const withoutFigures = { ...team, libraryCount: undefined, chatCount: undefined }
    useSpaceStore.setState({ spaces: [withoutFigures], isLoadingList: false, error: null })
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    const teamCard = screen.getByRole('link', { name: /Widerspruchsstelle/ })
    expect(teamCard).toHaveTextContent('9 Mitglieder')
    expect(teamCard).not.toHaveTextContent('Quelle')
  })

  it('navigates to the create wizard from the trailing card', async () => {
    const user = userEvent.setup()
    renderWithProviders(
      <Routes>
        <Route path="/" element={<SpacesOverviewPage />} />
        <Route path="/spaces/new" element={<div data-testid="create-wizard-route" />} />
      </Routes>,
      { withRouter: true },
    )

    await user.click(screen.getByRole('button', { name: 'Neuen Space anlegen' }))

    expect(await screen.findByTestId('create-wizard-route')).toBeInTheDocument()
  })

  it('shows a designed empty state without spaces', () => {
    // loadSpaces stubben - der Auto-Load der Seite würde sonst sofort Mock-Spaces nachladen
    // und den Leerzustand verdecken.
    useSpaceStore.setState({
      spaces: [],
      isLoadingList: false,
      error: null,
      loadSpaces: async () => {},
    })
    renderWithProviders(<SpacesOverviewPage />, { withRouter: true })

    expect(screen.getByText('0 Räume, in denen Sie Mitglied sind')).toBeInTheDocument()
    expect(screen.getByText(/Noch kein Space/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Neuen Space anlegen' })).toBeInTheDocument()
  })
})
