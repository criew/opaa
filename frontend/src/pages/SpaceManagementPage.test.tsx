import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import SpaceManagementPage from './SpaceManagementPage'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import type { SpaceResponse } from '../types/api'

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ spaceId: 'space-team' }),
    useNavigate: () => vi.fn(),
  }
})

const {
  mockUpdateSpaceDetails,
  mockUpdateSpaceMemberRole,
  mockRemoveSpaceMember,
  mockTransferSpaceOwnership,
  mockAddSpaceMember,
  mockDeleteSpace,
  mockArchiveSpace,
} = vi.hoisted(() => ({
  mockUpdateSpaceDetails: vi.fn(async () => ({}) as SpaceResponse),
  mockUpdateSpaceMemberRole: vi.fn(async () => ({})),
  mockRemoveSpaceMember: vi.fn(async () => undefined),
  mockTransferSpaceOwnership: vi.fn(async () => undefined),
  mockAddSpaceMember: vi.fn(async () => ({})),
  mockDeleteSpace: vi.fn(async () => undefined),
  mockArchiveSpace: vi.fn(async () => ({}) as SpaceResponse),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getUsers: vi.fn(async () => []),
    getSpaces: vi.fn(async () => []),
    getSpace: vi.fn(
      async (spaceId: string) => useSpaceStore.getState().selectedSpace ?? { id: spaceId },
    ),
    updateSpaceDetails: mockUpdateSpaceDetails,
    updateSpaceMemberRole: mockUpdateSpaceMemberRole,
    removeSpaceMember: mockRemoveSpaceMember,
    transferSpaceOwnership: mockTransferSpaceOwnership,
    addSpaceMember: mockAddSpaceMember,
    deleteSpace: mockDeleteSpace,
    archiveSpace: mockArchiveSpace,
  }
})

const personalSpace: SpaceResponse = {
  id: 'space-personal',
  name: 'Meine Dokumente',
  description: 'Private docs',
  isDefault: true,
  archived: false,
  visibility: 'PRIVATE',
  ownerId: 'u1',
  memberCount: 1,
  userRole: 'ADMIN',
  roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
  members: [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const teamSpace: SpaceResponse = {
  id: 'space-team',
  name: 'Team',
  description: 'Team docs',
  isDefault: false,
  archived: false,
  visibility: 'PRIVATE',
  ownerId: 'u1',
  memberCount: 2,
  userRole: 'ADMIN',
  roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 2 },
  members: [
    { userId: 'u1', displayName: 'Owner', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' },
    { userId: 'u2', displayName: 'Colleague', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' },
  ],
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

function setSpaceState(space: SpaceResponse) {
  useSpaceStore.setState({
    spaces: [],
    selectedSpaceId: space.id,
    selectedSpace: space,
    isLoadingList: false,
    isLoadingDetails: false,
    error: null,
  })
}

describe('SpaceManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      mode: 'dev',
      isAuthenticated: true,
      isLoading: false,
      user: { id: 'u1', email: 'owner@opaa.local', displayName: 'Owner', systemRole: 'USER' },
      token: null,
      error: null,
      userManager: null,
    })
  })

  it('hints that the default space is worked in alone, without blocking member management', () => {
    // #333: the default space is an ordinary space. The hint explains the empty member list; it
    // no longer means members are forbidden.
    setSpaceState(personalSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    expect(screen.getByText(/standard-space/i)).toBeInTheDocument()
  })

  it('marks the owner and hides remove/transfer actions for their own row', () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    expect(screen.getByText(/Owner · Eigentümer/)).toBeInTheDocument()
    // The owner's own row must not offer "Entfernen" or "Zum Eigentümer machen" for themselves.
    expect(screen.getByText('Colleague')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /entfernen/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /zum eigentümer machen/i })).toBeInTheDocument()
  })

  it('shows the delete button only for the owner of a non-personal space', () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    expect(screen.getByRole('button', { name: /space löschen/i })).toBeInTheDocument()
  })

  it('hides the delete button for a non-owner admin', () => {
    setSpaceState({ ...teamSpace, ownerId: 'someone-else' })
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    expect(screen.queryByRole('button', { name: /space löschen/i })).not.toBeInTheDocument()
  })

  it('saves settings by calling updateSpaceDetails with name and description only', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.clear(screen.getByLabelText(/name des space/i))
    await user.type(screen.getByLabelText(/name des space/i), 'Team Renamed')
    await user.click(screen.getByRole('button', { name: /einstellungen speichern/i }))

    await waitFor(() => {
      expect(mockUpdateSpaceDetails).toHaveBeenCalledWith('space-team', 'Team Renamed', 'Team docs')
    })
  })

  // #543: Space mit fremden privaten Chats ist dauerhaft unlöschbar - Archivieren ist der Ausweg.

  it('shows the archive button for the owner of a non-personal, non-archived space', () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    expect(screen.getByRole('button', { name: /space archivieren/i })).toBeInTheDocument()
  })

  it('hides the archive button once the space is already archived and shows the badge', () => {
    setSpaceState({ ...teamSpace, archived: true })
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    expect(screen.queryByRole('button', { name: /space archivieren/i })).not.toBeInTheDocument()
    expect(screen.getByText('Archiviert')).toBeInTheDocument()
  })

  it('archives the space via the store when the owner confirms', async () => {
    setSpaceState(teamSpace)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /space archivieren/i }))

    await waitFor(() => {
      expect(mockArchiveSpace).toHaveBeenCalledWith('space-team')
    })
    expect(screen.getByText('Space archiviert')).toBeInTheDocument()
  })

  it('offers to archive directly when deleteSpace is rejected because chats remain', async () => {
    mockDeleteSpace.mockRejectedValueOnce(
      new Error(
        'Der Space enthält noch Chats und kann deshalb nicht gelöscht werden. Archivieren Sie' +
          ' den Space stattdessen.',
      ),
    )
    setSpaceState(teamSpace)
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /^space löschen$/i }))

    const alertRegion = await screen.findByRole('alert')
    const archiveAction = within(alertRegion).getByRole('button', { name: /space archivieren/i })
    await user.click(archiveAction)

    await waitFor(() => {
      expect(mockArchiveSpace).toHaveBeenCalledWith('space-team')
    })
    expect(screen.getByText('Space archiviert')).toBeInTheDocument()
  })
})
