import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import SpaceManagementPage from './SpaceManagementPage'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import type { SpaceLibraryAssociationListResponse, SpaceResponse } from '../types/api'

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ spaceId: 'space-team' }),
    useNavigate: () => vi.fn(),
  }
})

// #144: membersBySpaceId lives here too - vi.hoisted's factory runs before the imports below, so
// mockListSpaceMembers cannot close over a module-level const declared after it.
const {
  mockUpdateSpaceDetails,
  mockUpdateSpaceMemberRole,
  mockRemoveSpaceMember,
  mockTransferSpaceOwnership,
  mockAddSpaceMember,
  mockDeleteSpace,
  mockArchiveSpace,
  mockListSpaceMembers,
  mockGetSpaceLibraryAssociations,
  membersBySpaceId,
} = vi.hoisted(() => {
  const membersBySpaceId: Record<
    string,
    Array<{
      userId: string
      displayName?: string
      role: 'MEMBER' | 'CURATOR' | 'ADMIN'
      createdAt: string
    }>
  > = {
    'space-personal': [{ userId: 'u1', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' }],
    'space-team': [
      { userId: 'u1', displayName: 'Owner', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' },
      { userId: 'u2', displayName: 'Colleague', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' },
    ],
  }
  return {
    mockUpdateSpaceDetails: vi.fn(async () => ({}) as SpaceResponse),
    mockUpdateSpaceMemberRole: vi.fn(async () => ({})),
    mockRemoveSpaceMember: vi.fn(async () => undefined),
    mockTransferSpaceOwnership: vi.fn(async () => undefined),
    mockAddSpaceMember: vi.fn(async () => ({})),
    mockDeleteSpace: vi.fn(async () => undefined),
    mockArchiveSpace: vi.fn(async () => ({}) as SpaceResponse),
    mockListSpaceMembers: vi.fn(async (spaceId: string) => membersBySpaceId[spaceId] ?? []),
    mockGetSpaceLibraryAssociations: vi.fn(
      async (spaceId: string): Promise<SpaceLibraryAssociationListResponse> => {
        void spaceId
        return { hasAssociations: false, items: [] }
      },
    ),
    membersBySpaceId,
  }
})

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getUsers: vi.fn(async () => []),
    getUserSummaries: vi.fn(async () => []),
    getSpaces: vi.fn(async () => []),
    getLibraries: vi.fn(async () => []),
    getSpace: vi.fn(
      async (spaceId: string) => useSpaceStore.getState().selectedSpace ?? { id: spaceId },
    ),
    listSpaceMembers: mockListSpaceMembers,
    getSpaceLibraryAssociations: mockGetSpaceLibraryAssociations,
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
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

// #674 review, nit e: a MEMBER who is neither ADMIN nor owner, reached this page directly by URL
// (not via SpacePage's "Space verwalten" button, which is hidden from them). Reuses 'space-team's
// id - the mocked useParams above is hardcoded to it - and the test below overrides
// mockListSpaceMembers for a single call to return [], mirroring listSpaceMembers's
// silent-empty-list handling of the backend's 403 for this caller.
const nonAdminSpace: SpaceResponse = {
  id: 'space-team',
  name: 'Fremdverwaltet',
  description: 'Team docs',
  isDefault: false,
  archived: false,
  visibility: 'PRIVATE',
  ownerId: 'someone-else',
  memberCount: 2,
  userRole: 'MEMBER',
  roleCounts: { MEMBER: 1, CURATOR: 0, ADMIN: 1 },
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
    members: membersBySpaceId[space.id] ?? [],
    isLoadingMembers: false,
  })
}

describe('SpaceManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetSpaceLibraryAssociations.mockResolvedValue({ hasAssociations: false, items: [] })
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

  it('#777: keeps the add-member form visible next to the default-space hint instead of hiding it', async () => {
    // The hint used to replace the whole members section, including "Mitglied hinzufügen" - the
    // default space is "ein Space wie jeder andere", so adding members must still work here.
    setSpaceState(personalSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    expect(screen.getByText(/standard-space/i)).toBeInTheDocument()
    expect(await screen.findByPlaceholderText('Benutzer suchen …')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /mitglied hinzufügen/i })).toBeInTheDocument()
  })

  it('marks the owner and hides remove/transfer actions for their own row', async () => {
    // #144: the page's own selectSpace effect clears the store's members synchronously before
    // the mocked listSpaceMembers response repopulates it - findByText waits for that repopulation
    // instead of racing it, matching the real (also async) endpoint this now goes through.
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    expect(await screen.findByText(/Owner · Eigentümer/)).toBeInTheDocument()
    // The owner's own row must not offer "Entfernen" or "Zum Eigentümer machen" for themselves.
    expect(screen.getByText('Colleague')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /entfernen/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /zum eigentümer machen/i })).toBeInTheDocument()
  })

  it('#777: renders the owner role as a static badge instead of an editable dropdown', async () => {
    // Before this fix, the owner's row rendered the same editable role Select as any other
    // member - changing it always failed against the backend's "Die Rolle des Eigentümers kann
    // nicht geändert werden" rejection.
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    const ownerName = await screen.findByText(/Owner · Eigentümer/)
    const ownerRow = ownerName.closest('div')
    expect(ownerRow).not.toBeNull()
    expect(within(ownerRow as HTMLElement).queryByRole('combobox')).not.toBeInTheDocument()
    expect(within(ownerRow as HTMLElement).getByText('Administrator')).toBeInTheDocument()

    // The (non-owner) colleague's row keeps its editable role Select.
    const colleagueName = screen.getByText('Colleague')
    const colleagueRow = colleagueName.closest('div')
    expect(colleagueRow).not.toBeNull()
    expect(within(colleagueRow as HTMLElement).getByRole('combobox')).toBeInTheDocument()
  })

  it('explains the empty member list instead of showing nothing for a non-admin, non-owner viewer', async () => {
    mockListSpaceMembers.mockResolvedValueOnce([])
    setSpaceState(nonAdminSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    expect(await screen.findByText(/nicht die erforderliche rolle/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /entfernen/i })).not.toBeInTheDocument()
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

  it('saves settings by calling updateSpaceDetails with name, description and the unchanged visibility', async () => {
    // #671 review: OPEN here (not PRIVATE, which is both the draft's initial value and the
    // fallback for a missing space.visibility) - only this way can the test actually catch a page
    // that fails to read the space's own visibility and silently sends PRIVATE instead, which
    // would downgrade an OPEN space on a plain rename.
    setSpaceState({ ...teamSpace, visibility: 'OPEN' })
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.clear(screen.getByLabelText(/name des space/i))
    await user.type(screen.getByLabelText(/name des space/i), 'Team Renamed')
    await user.click(screen.getByRole('button', { name: /einstellungen speichern/i }))

    await waitFor(() => {
      expect(mockUpdateSpaceDetails).toHaveBeenCalledWith(
        'space-team',
        'Team Renamed',
        'Team docs',
        'OPEN',
      )
    })
  })

  // #272: the visibility axis (docs/features/spaces-and-assets.md#space-sichtbarkeit) must be
  // changeable in space management, not just at creation time.
  it('saves the chosen visibility when it is changed', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('combobox', { name: /sichtbarkeit/i }))
    await user.click(await screen.findByRole('option', { name: /^offen$/i }))
    await user.click(screen.getByRole('button', { name: /einstellungen speichern/i }))

    await waitFor(() => {
      expect(mockUpdateSpaceDetails).toHaveBeenCalledWith('space-team', 'Team', 'Team docs', 'OPEN')
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

  // #706 review, finding 5: an ADMIN must see (and be able to detach) an association they cannot
  // themselves read - the store's unfiltered items list carries readableByCaller=false and no
  // libraryName for such an entry.
  it('shows an unreadable association without its name and still offers to detach it', async () => {
    mockGetSpaceLibraryAssociations.mockResolvedValue({
      hasAssociations: true,
      items: [
        {
          libraryId: 'lib-hidden',
          readableByCaller: false,
          createdByUserId: 'u2',
          createdAt: '2026-03-01T10:00:00Z',
        },
      ],
    })
    setSpaceState(teamSpace)

    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    expect(await screen.findByText('Bibliothek ohne eigenen Zugriff')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^lösen$/i })).toBeInTheDocument()
  })

  // #784: without an explicit noOptionsText, MUI's Autocomplete falls back to the English
  // default "No options" - the project language requires German for every visible UI text.
  it('shows a German text when the library autocomplete has no options to offer', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const field = await screen.findByPlaceholderText('Bibliothek suchen …')
    await user.click(field)

    expect(await screen.findByText('Keine zuordenbaren Bibliotheken')).toBeInTheDocument()
    expect(screen.queryByText('No options')).not.toBeInTheDocument()
  })
})
