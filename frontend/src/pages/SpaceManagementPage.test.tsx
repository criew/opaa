import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { HttpResponse, http } from 'msw'
import { server } from '../mocks/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
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
  mockGetLibraries,
  mockSearchSelectableGroups,
  mockGetSpaceAccessDerivation,
  mockGetSpaceGroupMembers,
  membersBySpaceId,
} = vi.hoisted(() => {
  const membersBySpaceId: Record<
    string,
    Array<{
      id: string
      subjectType: 'USER' | 'GROUP'
      subjectId: string
      displayName?: string
      role: 'MEMBER' | 'CURATOR' | 'ADMIN'
      memberCountAtGrant?: number | null
      memberCountNow?: number | null
      smallGroup?: boolean
      emptyGroup?: boolean
      protectedGroup?: boolean
      createdAt: string
    }>
  > = {
    'space-personal': [
      {
        id: 'm-personal-u1',
        subjectType: 'USER',
        subjectId: 'u1',
        role: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
      },
    ],
    'space-team': [
      {
        id: 'm-team-u1',
        subjectType: 'USER',
        subjectId: 'u1',
        displayName: 'Owner',
        role: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
      },
      {
        id: 'm-team-u2',
        subjectType: 'USER',
        subjectId: 'u2',
        displayName: 'Colleague',
        role: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
      },
      // #1815: a group as a member, with the growth signal of ADR-0036, Entscheidung 9.
      {
        id: 'm-team-referat-50',
        subjectType: 'GROUP',
        subjectId: 'g1',
        displayName: 'Referat 50',
        role: 'MEMBER',
        memberCountAtGrant: 23,
        memberCountNow: 41,
        smallGroup: false,
        emptyGroup: false,
        createdAt: '2026-03-01T10:00:00Z',
      },
      // #1820: eine geschuetzte Gruppe - ohne Namen und ohne jede Zahl, aber mit ihrer Zeile.
      {
        id: 'm-team-personalrat',
        subjectType: 'GROUP',
        subjectId: 'g2',
        role: 'MEMBER',
        protectedGroup: true,
        memberCountAtGrant: null,
        memberCountNow: null,
        createdAt: '2026-03-01T10:00:00Z',
      },
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
    mockGetLibraries: vi.fn(async () => [] as unknown[]),
    mockGetSpaceLibraryAssociations: vi.fn(
      async (spaceId: string): Promise<SpaceLibraryAssociationListResponse> => {
        void spaceId
        return { hasAssociations: false, items: [] }
      },
    ),
    // #1820: die Subjekt-Auswahl sucht serverseitig; welche Gruppen erscheinen, entscheidet der
    // Dienst.
    mockSearchSelectableGroups: vi.fn(async () => [
      {
        id: 'group-phoenix',
        name: 'Projektbeteiligte Phoenix',
        origin: 'INTERNAL' as const,
        provider: null,
        sourcePath: null,
        activeMemberCount: 6,
        smallGroup: false,
        emptyGroup: false,
        protectedGroup: false,
        selectable: true,
        dissolved: false,
        providerDisabled: false,
        unmaintained: false,
      },
    ]),
    mockGetSpaceAccessDerivation: vi.fn(async (spaceId: string, userId?: string) => ({
      spaceId,
      userId: userId ?? 'u1',
      effectiveRole: 'MEMBER' as const,
      pathsWithheld: false,
      paths: [
        {
          basis: 'GROUP_MEMBERSHIP' as const,
          spaceRole: 'MEMBER' as const,
          since: '2026-03-01T10:00:00Z',
          group: {
            id: 'g1',
            name: 'Referat 50',
            origin: 'PROVIDER' as const,
            mechanism: 'DIRECTORY' as const,
            providerName: 'Verzeichnis Haus A',
          },
        },
      ],
    })),
    mockGetSpaceGroupMembers: vi.fn(async () => ({
      groupId: 'g1',
      name: 'Referat 50',
      protectedGroup: false,
      activeMemberCount: 1,
      members: [{ userId: 'user-anna', displayName: 'Anna Bauer' }],
      responsible: [] as string[],
    })),
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
    getLibraries: mockGetLibraries,
    // #1820: the subject picker searches groups server-side.
    searchSelectableGroups: mockSearchSelectableGroups,
    getSpaceAccessDerivation: mockGetSpaceAccessDerivation,
    getSpaceGroupMembers: mockGetSpaceGroupMembers,
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
    expect(await screen.findByPlaceholderText('Person suchen …')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^hinzufügen$/i })).toBeInTheDocument()
  })

  it('marks the owner and hides remove/transfer actions for their own row', async () => {
    // #144: the page's own selectSpace effect clears the store's members synchronously before
    // the mocked listSpaceMembers response repopulates it - findByText waits for that repopulation
    // instead of racing it, matching the real (also async) endpoint this now goes through.
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    expect(await screen.findByText(/Owner · Eigentümer/)).toBeInTheDocument()
    // The owner's own row must not offer "Entfernen" or "Zum Eigentümer machen" for themselves -
    // the colleague's and both group rows do offer "Entfernen", neither group offers the handover
    // (a space owner is always a natural person, #1815); the protected group's row exists for
    // exactly this reason (#1820).
    expect(screen.getByText('Colleague')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /entfernen/i })).toHaveLength(3)
    expect(screen.getAllByRole('button', { name: /zum eigentümer machen/i })).toHaveLength(1)
  })

  // #1815: a group row names the group, marks it as one, and carries the growth signal of
  // ADR-0036, Entscheidung 9 - but never the handover, which only a natural person may receive.
  it('renders a group member with its name, its marker and its growth signal', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    const groupRow = await screen.findByText(/Referat 50 · Gruppe · 23 bei Aufnahme, heute 41/)
    const row = groupRow.closest('div')
    expect(row).not.toBeNull()
    expect(
      within(row as HTMLElement).queryByRole('button', { name: /zum eigentümer machen/i }),
    ).not.toBeInTheDocument()
  })

  it('withholds both figures for a small group instead of showing one of them', async () => {
    mockListSpaceMembers.mockResolvedValueOnce([
      {
        id: 'm-team-u1',
        subjectType: 'USER',
        subjectId: 'u1',
        displayName: 'Owner',
        role: 'ADMIN',
        createdAt: '2026-03-01T10:00:00Z',
      },
      {
        id: 'm-team-klein',
        subjectType: 'GROUP',
        subjectId: 'g2',
        displayName: 'Kleine Runde',
        role: 'MEMBER',
        memberCountAtGrant: null,
        memberCountNow: null,
        smallGroup: true,
        emptyGroup: false,
        createdAt: '2026-03-01T10:00:00Z',
      },
    ])
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    expect(await screen.findByText(/Kleine Runde · Gruppe · kleine Gruppe/)).toBeInTheDocument()
  })

  /**
   * #1880, ADR-0036 Entscheidung 9: Wer die Gruppe hier aufgenommen hat, sieht ihre Mitglieder —
   * und erst, wenn er danach fragt.
   */
  it('offers the member list of a group member and loads it only on request', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const trigger = await screen.findByRole('button', {
      name: 'Mitglieder von Referat 50 anzeigen',
    })
    expect(mockGetSpaceGroupMembers).not.toHaveBeenCalled()

    await userEvent.click(trigger)

    expect(await screen.findByText('Anna Bauer')).toBeInTheDocument()
    expect(mockGetSpaceGroupMembers).toHaveBeenCalledWith('space-team', 'g1', 0, 50)
  })

  it('adds a group as a member through the group search', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('radio', { name: 'Gruppe' }))
    await user.type(screen.getByLabelText('Gruppe suchen'), 'Projekt')
    await user.click(await screen.findByRole('option', { name: /Projektbeteiligte Phoenix/ }))
    await user.click(screen.getByRole('button', { name: /^hinzufügen$/i }))

    await waitFor(() => {
      expect(mockAddSpaceMember).toHaveBeenCalledWith(
        'space-team',
        'GROUP',
        'group-phoenix',
        'MEMBER',
      )
    })
  })

  /**
   * #1815, ADR-0036 Entscheidung 6: the handover is no longer the owner's alone - every capable
   * ADMIN member that is a natural person may perform it. Driven here with an ADMIN caller who is
   * not the owner, which before this change saw no button at all.
   */
  it('offers the handover to an ADMIN member who is not the owner', async () => {
    setSpaceState({ ...teamSpace, ownerId: 'someone-else', userRole: 'ADMIN' })
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await screen.findByText('Colleague')
    const handoverButtons = screen.getAllByRole('button', { name: /zum eigentümer machen/i })
    // Both person rows, never the group row - a space owner is always a natural person.
    expect(handoverButtons).toHaveLength(2)

    await user.click(handoverButtons[0])
    await user.click(await screen.findByRole('button', { name: /übertragen/i }))

    await waitFor(() => {
      expect(mockTransferSpaceOwnership).toHaveBeenCalledWith('space-team', 'u1')
    })
  })

  it('names a failed group search instead of showing an empty picker', async () => {
    mockSearchSelectableGroups.mockRejectedValueOnce(new Error('offline'))
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('radio', { name: 'Gruppe' }))
    await user.type(screen.getByLabelText('Gruppe suchen'), 'Projekt')

    expect(await screen.findByText(/offline/)).toBeInTheDocument()
  })

  // #1815, ADR-0036 Entscheidung 6: state and addressee, without a date and without the previous
  // owner - the space stays usable.
  it('names the derived state "Nachfolge offen" without a date or a previous owner', () => {
    setSpaceState({ ...teamSpace, successionOpen: true })
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    expect(screen.getByText(/Nachfolge offen — zuständig: Systemverwaltung/)).toBeInTheDocument()
  })

  it('#777: renders the owner role as a static badge instead of an editable dropdown', async () => {
    // Before this fix, the owner's row rendered the same editable role Select as any other
    // member - changing it always failed against the backend's "Die Rolle des Eigentümers kann
    // nicht geändert werden" rejection.
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    const ownerName = await screen.findByText(/Owner · Eigentümer/)
    // Seit #1820 trägt die Zeile unter dem Namen noch den Aufruf der Herleitung - die Rolle liegt
    // eine Ebene höher, im Zeilencontainer.
    const ownerRow = ownerName.closest('div')?.parentElement
    expect(ownerRow).not.toBeNull()
    expect(within(ownerRow as HTMLElement).queryByRole('combobox')).not.toBeInTheDocument()
    expect(within(ownerRow as HTMLElement).getByText('Administrator')).toBeInTheDocument()

    // The (non-owner) colleague's row keeps its editable role Select.
    const colleagueName = screen.getByText('Colleague')
    const colleagueRow = colleagueName.closest('div')?.parentElement
    expect(colleagueRow).not.toBeNull()
    expect(within(colleagueRow as HTMLElement).getByRole('combobox')).toBeInTheDocument()
  })

  /**
   * #1820, ADR-0036 Entscheidung 9: Eine geschuetzte Gruppe erscheint namenlos und ohne Zahl - die
   * Zeile bleibt, sonst koennte ein ADMIN eine Mitgliedschaft nicht beenden, die er nicht sieht.
   */
  it('renders a protected group as a nameless row that can still be removed', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })

    const protectedRow = await screen.findByText(/Geschützte Gruppe/)
    expect(screen.queryByText(/g2/)).not.toBeInTheDocument()
    expect(protectedRow.textContent).not.toMatch(/bei Aufnahme/)
    const row = protectedRow.closest('div')?.parentElement
    expect(
      within(row as HTMLElement).getByRole('button', { name: /entfernen/i }),
    ).toBeInTheDocument()
  })

  /** #1822: ob eine Rolle direkt oder ueber eine Gruppe kommt, beantwortet die Herleitung. */
  it('opens the derivation of a person on request and never for a group row', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Herleitung für Colleague' }))

    expect(await screen.findByText(/Wirksame Rolle/)).toBeInTheDocument()
    expect(mockGetSpaceAccessDerivation).toHaveBeenCalledWith('space-team', 'u2')
    expect(
      screen.queryByRole('button', { name: /Herleitung für Geschützte Gruppe/ }),
    ).not.toBeInTheDocument()
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
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /space archivieren/i }))
    await answerConfirm(user, 'Diesen Space archivieren?', 'Archivieren')

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
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /^space löschen$/i }))
    await answerConfirm(user, 'Diesen Space löschen?', 'Löschen')

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

  it('names the takeover when an open succession refuses a new association', async () => {
    mockGetLibraries.mockResolvedValueOnce([
      {
        id: 'lib-frei',
        name: 'Freie Bibliothek',
        ownerType: 'USER',
        ownerId: 'u1',
        visibility: 'PRIVATE',
        listed: false,
        myRole: 'OWNER',
        documentCount: 0,
        sourceType: 'UPLOAD',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    server.use(
      http.post('/api/v1/spaces/:spaceId/libraries', () =>
        HttpResponse.json(
          {
            error:
              'Für dieses Objekt ist die Nachfolge offen: eine neue Bereitstellung ist deshalb' +
              ' nicht möglich. Bestehende Rechte bleiben unverändert, und nichts wird gelöscht.' +
              ' Zuständig: die Systemverwaltung',
            code: 'SUCCESSION_OPEN',
          },
          { status: 409 },
        ),
      ),
    )
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const field = await screen.findByPlaceholderText('Bibliothek suchen …')
    await user.click(field)
    await user.click(await screen.findByRole('option', { name: 'Freie Bibliothek' }))
    await user.click(screen.getByRole('button', { name: 'Zuordnen' }))

    expect(await screen.findByText(/Nachfolge offen/)).toBeInTheDocument()
    expect(screen.getByText(/Übernahme/)).toBeInTheDocument()
  })

  // #784: without an explicit noOptionsText, MUI's Autocomplete falls back to the English
  // default "No options" - the project language requires German for every visible UI text.
  it('shows a German text when the library autocomplete has no options to offer', async () => {
    setSpaceState(teamSpace)
    renderWithProviders(<SpaceManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    const field = await screen.findByPlaceholderText('Bibliothek suchen …')
    await user.click(field)

    expect(await screen.findByText('Keine Treffer')).toBeInTheDocument()
    expect(screen.queryByText('No options')).not.toBeInTheDocument()
  })
})
