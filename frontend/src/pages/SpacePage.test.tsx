import { screen } from '@testing-library/react'
import { describe, expect, it, vi, beforeEach } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import SpacePage from './SpacePage'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { useChatListStore } from '../stores/chatListStore'
import type { SpaceLibraryAssociationResponse, SpaceMemberResponse } from '../types/api'

const currentSpaceId = 'space-personal'
const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ spaceId: currentSpaceId }),
    useNavigate: () => mockNavigate,
  }
})

// #674 re-review: listSpaceMembers and getSpace are mocked (rather than left to MSW's shared
// 'space-personal' fixture) so the owner-without-ADMIN test below controls its own response
// instead of depending on mocks/fixtures.ts staying in sync with this test's ad-hoc space/member
// data - getSpace in particular would otherwise silently overwrite the test's selectedSpace (set
// directly via useSpaceStore.setState below) with the shared fixture's ADMIN/mock-user-id space
// once SpacePage's own selectSpace effect resolves. vi.hoisted because vi.mock's factory below is
// itself hoisted above this module's regular top-level statements.
const { mockListSpaceMembers, mockGetSpaceLibraryAssociations } = vi.hoisted(() => ({
  mockListSpaceMembers: vi.fn(async (): Promise<SpaceMemberResponse[]> => []),
  mockGetSpaceLibraryAssociations: vi.fn(
    async (): Promise<SpaceLibraryAssociationResponse[]> => [],
  ),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getSpace: vi.fn(
      async (spaceId: string) => useSpaceStore.getState().selectedSpace ?? { id: spaceId },
    ),
    listSpaceMembers: mockListSpaceMembers,
    getSpaceLibraryAssociations: mockGetSpaceLibraryAssociations,
  }
})

describe('SpacePage', () => {
  beforeEach(() => {
    mockListSpaceMembers.mockClear()
    mockGetSpaceLibraryAssociations.mockClear()
    mockGetSpaceLibraryAssociations.mockResolvedValue([])
    useChatListStore.setState({ chatsBySpaceId: {}, isLoading: false, error: null })
    useAuthStore.setState({
      mode: 'dev',
      isAuthenticated: true,
      isLoading: false,
      user: null,
      token: null,
      error: null,
      userManager: null,
    })
    useSpaceStore.setState({
      spaces: [
        {
          id: 'space-personal',
          name: 'Meine Dokumente',
          description: 'Private Dokumente',
          isDefault: true,
          archived: false,
          visibility: 'PRIVATE',
          memberCount: 1,
          userRole: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
          updatedAt: '2026-03-01T10:00:00Z',
        },
      ],
      selectedSpaceId: 'space-personal',
      selectedSpace: {
        id: 'space-personal',
        name: 'Meine Dokumente',
        description: 'Private Dokumente',
        isDefault: true,
        archived: false,
        visibility: 'PRIVATE',
        ownerId: 'mock-user-id',
        memberCount: 1,
        userRole: 'ADMIN',
        roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
      isLoadingList: false,
      isLoadingDetails: false,
      error: null,
      members: [
        {
          userId: 'mock-user-id',
          displayName: 'Admin',
          role: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
        },
      ],
      isLoadingMembers: false,
    })
  })

  it('lists the space chats in the Chats section', async () => {
    renderWithProviders(<SpacePage />, { withRouter: true })

    expect(await screen.findByText('Architektur des Projekts')).toBeInTheDocument()
    expect(await screen.findByText('Deployment-Fragen')).toBeInTheDocument()
  })

  // #203 acceptance criteria: the associated-libraries list and its once-shown explanation for why
  // it can differ per member.
  it('shows the space’s associated libraries and a dismissible explanatory hint', async () => {
    window.localStorage.removeItem('opaa.space-library-hint-dismissed')
    mockGetSpaceLibraryAssociations.mockResolvedValue([
      {
        libraryId: 'lib-1',
        libraryName: 'Rechtsquellen Soziales',
        createdByUserId: 'mock-user-id',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ])

    renderWithProviders(<SpacePage />, { withRouter: true })

    expect(await screen.findByText('Rechtsquellen Soziales')).toBeInTheDocument()
    expect(screen.getByText(/andere Mitglieder können deshalb/)).toBeInTheDocument()
  })

  it('shows a fallback message when the space has no library associations', async () => {
    mockGetSpaceLibraryAssociations.mockResolvedValue([])

    renderWithProviders(<SpacePage />, { withRouter: true })

    expect(
      await screen.findByText(/Diesem Space sind keine Bibliotheken zugeordnet/),
    ).toBeInTheDocument()
  })

  // #674 review, nit e: the non-admin path - a MEMBER must see only the aggregated roleCounts,
  // never an identity or display name, and must not even trigger the members request (#144).
  it('shows only the aggregated role counts, not names, for a non-admin member', () => {
    useSpaceStore.setState({
      selectedSpace: {
        id: 'space-personal',
        name: 'Meine Dokumente',
        description: 'Private Dokumente',
        isDefault: true,
        archived: false,
        visibility: 'PRIVATE',
        ownerId: 'owner-1',
        memberCount: 2,
        userRole: 'MEMBER',
        roleCounts: { MEMBER: 1, CURATOR: 0, ADMIN: 1 },
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
      members: [],
    })

    renderWithProviders(<SpacePage />, { withRouter: true })

    expect(screen.getByText('Mitglied: 1')).toBeInTheDocument()
    expect(screen.getByText('Administrator: 1')).toBeInTheDocument()
    expect(screen.queryByText('Admin')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /space verwalten/i })).not.toBeInTheDocument()
  })

  // #674 re-review: transferOwnership never changes the new owner's own membership role (see
  // SpaceService#requireMemberListViewer) - an owner whose own role is MEMBER must still see the
  // full member list and the "Space verwalten" entry point, not just the aggregated counts.
  it('shows the full member list and the manage button for a non-ADMIN owner', async () => {
    mockListSpaceMembers.mockResolvedValueOnce([
      {
        userId: 'owner-1',
        displayName: 'Owner',
        role: 'MEMBER',
        createdAt: '2026-03-01T10:00:00Z',
      },
      { userId: 'admin-1', displayName: 'Admin', role: 'ADMIN', createdAt: '2026-03-01T10:00:00Z' },
    ])
    useAuthStore.setState({
      mode: 'dev',
      isAuthenticated: true,
      isLoading: false,
      user: { id: 'owner-1', email: 'owner@opaa.local', displayName: 'Owner', systemRole: 'USER' },
      token: null,
      error: null,
      userManager: null,
    })
    useSpaceStore.setState({
      selectedSpace: {
        id: 'space-personal',
        name: 'Meine Dokumente',
        description: 'Private Dokumente',
        isDefault: true,
        archived: false,
        visibility: 'PRIVATE',
        ownerId: 'owner-1',
        memberCount: 2,
        userRole: 'MEMBER',
        roleCounts: { MEMBER: 1, CURATOR: 0, ADMIN: 1 },
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
      members: [
        {
          userId: 'owner-1',
          displayName: 'Owner',
          role: 'MEMBER',
          createdAt: '2026-03-01T10:00:00Z',
        },
        {
          userId: 'admin-1',
          displayName: 'Admin',
          role: 'ADMIN',
          createdAt: '2026-03-01T10:00:00Z',
        },
      ],
    })

    renderWithProviders(<SpacePage />, { withRouter: true })

    // findByText (rather than getByText) lets the effect-triggered loadMembers() call settle
    // before asserting, avoiding an act() warning from its state update landing after the test
    // body returns.
    expect(await screen.findByText('Owner')).toBeInTheDocument()
    expect(screen.getByText('Admin')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /space verwalten/i })).toBeInTheDocument()
  })
})
