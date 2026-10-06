import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useLocation } from 'react-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import SpaceSettingsPage from './SpaceSettingsPage'
import { useAuthStore } from '../stores/authStore'
import { useSpaceStore } from '../stores/spaceStore'
import { useNotificationStore } from '../stores/notificationStore'
import { getSpace } from '../services/spaceApi'
import type { SpaceAssetAssociationListResponse, SpaceResponse } from '../types/api'

// Der sichtbare Reiter ist eine Route (#1917); der Test setzt ihn wie die Adresszeile.
const { routeParams, mockNavigate } = vi.hoisted(() => ({
  routeParams: { spaceId: 'space-team', tab: 'general' as string },
  mockNavigate: vi.fn(),
}))

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => routeParams,
    useNavigate: () => mockNavigate,
  }
})

/** Shows the router's current path, to observe a redirect. */
function LocationProbe() {
  return <span data-testid="location">{useLocation().pathname}</span>
}

/** Rendert die Einstellungsseite auf einem bestimmten Reiter. */
function renderTab(tab: 'general' | 'members' | 'content') {
  routeParams.tab = tab
  return renderWithProviders(<SpaceSettingsPage />, { withRouter: true })
}

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
  mockGetSpaceAssetAssociations,
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
      activeMemberCount?: number | null
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
      // #1815: a group as a member, with its current size (ADR-0036, Entscheidung 9).
      {
        id: 'm-team-referat-50',
        subjectType: 'GROUP',
        subjectId: 'g1',
        displayName: 'Referat 50',
        role: 'MEMBER',
        activeMemberCount: 41,
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
        activeMemberCount: null,
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
    mockGetSpaceAssetAssociations: vi.fn(
      async (spaceId: string): Promise<SpaceAssetAssociationListResponse> => {
        void spaceId
        return {
          hasAssociations: false,
          hasUnreadableAssociations: false,
          hasKnowledge: false,
          hasReadableKnowledge: false,
          items: [],
        }
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

vi.mock('../services/userApi', async () => {
  const actual = await vi.importActual<typeof import('../services/userApi')>('../services/userApi')
  return {
    ...actual,
    getUsers: vi.fn(async () => []),
    getUserSummaries: vi.fn(async () => []),
  }
})

vi.mock('../services/spaceApi', async () => {
  const actual =
    await vi.importActual<typeof import('../services/spaceApi')>('../services/spaceApi')
  return {
    ...actual,
    getSpaces: vi.fn(async () => []),
    getSpaceAccessDerivation: mockGetSpaceAccessDerivation,
    getSpaceGroupMembers: mockGetSpaceGroupMembers,
    getSpace: vi.fn(
      async (spaceId: string) => useSpaceStore.getState().selectedSpace ?? { id: spaceId },
    ),
    listSpaceMembers: mockListSpaceMembers,
    updateSpaceDetails: mockUpdateSpaceDetails,
    updateSpaceMemberRole: mockUpdateSpaceMemberRole,
    removeSpaceMember: mockRemoveSpaceMember,
    transferSpaceOwnership: mockTransferSpaceOwnership,
    addSpaceMember: mockAddSpaceMember,
    deleteSpace: mockDeleteSpace,
    archiveSpace: mockArchiveSpace,
  }
})

vi.mock('../services/libraryApi', async () => {
  const actual =
    await vi.importActual<typeof import('../services/libraryApi')>('../services/libraryApi')
  return {
    ...actual,
    getLibraries: mockGetLibraries,
  }
})

vi.mock('../services/groupApi', async () => {
  const actual =
    await vi.importActual<typeof import('../services/groupApi')>('../services/groupApi')
  return {
    ...actual,
    // #1820: the subject picker searches groups server-side.
    searchSelectableGroups: mockSearchSelectableGroups,
  }
})

vi.mock('../services/assetApi', async () => {
  const actual =
    await vi.importActual<typeof import('../services/assetApi')>('../services/assetApi')
  return {
    ...actual,
    getSpaceAssetAssociations: mockGetSpaceAssetAssociations,
  }
})

const personalSpace: SpaceResponse = {
  id: 'space-personal',
  name: 'Privater Bereich',
  description: 'Private docs',
  isDefault: true,
  archived: false,
  ownerId: 'u1',
  memberCount: 1,
  memberships: { groupCount: 0, userCount: 1 },
  userRole: 'ADMIN',
  roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 1 },
  chatAutoCleanup: { enabled: false, archiveAfterDays: 90, deleteAfterDays: 365 },
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const teamSpace: SpaceResponse = {
  id: 'space-team',
  name: 'Team',
  description: 'Team docs',
  isDefault: false,
  archived: false,
  ownerId: 'u1',
  memberCount: 2,
  memberships: { groupCount: 0, userCount: 2 },
  userRole: 'ADMIN',
  roleCounts: { MEMBER: 0, CURATOR: 0, ADMIN: 2 },
  chatAutoCleanup: { enabled: false, archiveAfterDays: 90, deleteAfterDays: 365 },
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
  ownerId: 'someone-else',
  memberCount: 2,
  memberships: { groupCount: 0, userCount: 2 },
  userRole: 'MEMBER',
  roleCounts: { MEMBER: 1, CURATOR: 0, ADMIN: 1 },
  chatAutoCleanup: { enabled: false, archiveAfterDays: 90, deleteAfterDays: 365 },
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

/** Die Zeile eines Mitglieds in der Liste des Reiters „Mitglieder“ (#2134). */
function memberRow(name: string | RegExp): HTMLElement {
  const row = screen.getByText(name).closest('[data-testid="space-member-row"]')
  expect(row).not.toBeNull()
  return row as HTMLElement
}

/** Öffnet das „⋯“-Menü einer Mitgliedszeile. */
async function openMemberMenu(user: ReturnType<typeof userEvent.setup>, name: string) {
  await user.click(await screen.findByRole('button', { name: `Weitere Aktionen für „${name}“` }))
  return screen.findByRole('menu')
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

describe('SpaceSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetSpaceAssetAssociations.mockResolvedValue({
      hasAssociations: false,
      hasUnreadableAssociations: false,
      hasKnowledge: false,
      hasReadableKnowledge: false,
      items: [],
    })
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

  /**
   * #1917: eine Einstellungsseite je Space, gegliedert in Reiter; alle Asset-Typen teilen sich
   * den Reiter „Inhalte".
   */
  it('gliedert die Einstellungen in Stammdaten, Mitglieder und Inhalte', () => {
    setSpaceState(teamSpace)
    renderTab('general')

    const tablist = screen.getByRole('tablist', { name: 'Bereiche der Space-Einstellungen' })
    expect(
      within(tablist)
        .getAllByRole('tab')
        .map((tab) => tab.textContent),
    ).toEqual(['Stammdaten', 'Mitglieder', 'Inhalte'])
    expect(screen.getByRole('heading', { level: 1, name: 'Einstellungen' })).toBeInTheDocument()
    // #2207: Keine Überschrift wiederholt den Reiternamen; der Gefahrenbereich ist deshalb die
    // h2 des Panels, ohne Sprung in der Gliederung (docs/design/accessibility.md 2.3).
    expect(screen.queryByRole('heading', { name: 'Stammdaten' })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: 'Gefahrenbereich' })).toBeInTheDocument()
    expect(within(tablist).getByRole('tab', { name: 'Mitglieder' })).toHaveAttribute(
      'href',
      '/spaces/space-team/settings/members',
    )
  })

  /**
   * #1917: Jeder Abschnitt lädt in seinem eigenen Effekt, und `AreaTabs` rendert nur das aktive
   * Panel — die Mitgliederliste wird also erst auf ihrem Reiter geholt. Ein Rückbau auf
   * Seitenebene würde das unbemerkt wieder einsammeln.
   */
  it('lädt nur, was der sichtbare Reiter braucht', async () => {
    setSpaceState(teamSpace)
    renderTab('general')

    await screen.findByRole('button', { name: /einstellungen speichern/i })
    expect(mockListSpaceMembers).not.toHaveBeenCalled()
    expect(mockGetSpaceAssetAssociations).not.toHaveBeenCalled()
  })

  // #1917: Die Einstellungen führen keine Chatliste mehr - sie war der Grund, warum die alte
  // Datenquellen-Seite wie eine zweite Chat-Übersicht wirkte.
  it('zeigt in den Einstellungen weder Chatliste noch „Neuer Chat“', () => {
    setSpaceState(teamSpace)
    renderTab('general')

    expect(screen.queryByRole('button', { name: /neuer chat/i })).not.toBeInTheDocument()
    expect(screen.queryByText('Chats')).not.toBeInTheDocument()
  })

  /** #1917, Muster GitLab: die beiden folgenreichen Handlungen stehen abgesetzt und benannt. */
  it('stellt Archivieren und Löschen in einen eigenen Gefahrenbereich der Stammdaten', () => {
    setSpaceState(teamSpace)
    renderTab('general')

    const dangerZone = screen.getByRole('heading', { name: 'Gefahrenbereich' })
      .parentElement as HTMLElement
    expect(
      within(dangerZone).getByRole('button', { name: /space archivieren/i }),
    ).toBeInTheDocument()
    expect(within(dangerZone).getByRole('button', { name: /^space löschen$/i })).toBeInTheDocument()
  })

  it('zeigt den Gefahrenbereich niemandem, der den Space nicht löschen darf', () => {
    setSpaceState({ ...teamSpace, ownerId: 'someone-else' })
    renderTab('general')

    expect(screen.queryByText('Gefahrenbereich')).not.toBeInTheDocument()
  })

  it('hints that the default space is worked in alone, without blocking member management', () => {
    // #333: the default space is an ordinary space. The hint explains the empty member list; it
    // no longer means members are forbidden.
    setSpaceState(personalSpace)
    renderTab('members')
    expect(screen.getByText(/standard-space/i)).toBeInTheDocument()
  })

  it('#777: keeps the add-member form visible next to the default-space hint instead of hiding it', async () => {
    // The hint used to replace the whole members section, including "Mitglied hinzufügen" - the
    // default space is "ein Space wie jeder andere", so adding members must still work here.
    setSpaceState(personalSpace)
    renderTab('members')

    expect(screen.getByText(/standard-space/i)).toBeInTheDocument()
    expect(await screen.findByPlaceholderText('Person oder Gruppe suchen …')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /^hinzufügen$/i })).toBeInTheDocument()
  })

  /** #2131, #2205: Das Formular ist die Überschrift des Panels, ohne Hinweistext darunter. */
  it('heads the members tab with the add form only, without a hint on groups', async () => {
    setSpaceState(teamSpace)
    renderTab('members')

    expect(
      await screen.findByRole('heading', { level: 2, name: 'Mitglied hinzufügen' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Mitglieder' })).not.toBeInTheDocument()
    expect(screen.queryByText(/Gruppen geben ihre Rolle/)).not.toBeInTheDocument()
  })

  it('marks the owner and hides remove/transfer actions for their own row', async () => {
    // #144: the page's own selectSpace effect clears the store's members synchronously before
    // the mocked listSpaceMembers response repopulates it - findByText waits for that repopulation
    // instead of racing it, matching the real (also async) endpoint this now goes through.
    setSpaceState(teamSpace)
    renderTab('members')

    expect(await screen.findByText('Owner')).toBeInTheDocument()
    expect(within(memberRow('Owner')).getByText('Eigentümer')).toBeInTheDocument()
    // #2134: the owner's row carries no menu; the colleague and both group rows do - the
    // protected group's row exists for exactly this reason (#1820).
    expect(
      screen.queryByRole('button', { name: 'Weitere Aktionen für „Owner“' }),
    ).not.toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: /^Weitere Aktionen für/ })).toHaveLength(3)
  })

  it('#1923: never offers the handover in a personal space', async () => {
    setSpaceState({ ...teamSpace, isDefault: true })
    renderTab('members')
    const user = userEvent.setup()

    const menu = await openMemberMenu(user, 'Colleague')

    expect(within(menu).queryByText('Zum Eigentümer machen')).not.toBeInTheDocument()
  })

  // #1815, #2134: a group row names the group, marks it as one and carries its current size -
  // but never the handover, which only a natural person may receive.
  it('renders a group member with its name, its marker and its current size', async () => {
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()

    expect(await screen.findByText('Gruppe · 41 Mitglieder')).toBeInTheDocument()
    expect(screen.queryByText(/bei Aufnahme/)).not.toBeInTheDocument()
    const menu = await openMemberMenu(user, 'Referat 50')
    expect(within(menu).queryByText('Zum Eigentümer machen')).not.toBeInTheDocument()
  })

  it('shows the figure of a small group too', async () => {
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
        activeMemberCount: 2,
        emptyGroup: false,
        createdAt: '2026-03-01T10:00:00Z',
      },
    ])
    setSpaceState(teamSpace)
    renderTab('members')

    expect(await screen.findByText('Gruppe · 2 Mitglieder')).toBeInTheDocument()
    expect(screen.queryByText(/kleine Gruppe/)).not.toBeInTheDocument()
  })

  /**
   * #1880, ADR-0036 Entscheidung 9: Wer die Gruppe hier aufgenommen hat, sieht ihre Mitglieder —
   * und erst, wenn er danach fragt.
   */
  it('offers the member list of a group member and loads it only on request', async () => {
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()
    const menu = await openMemberMenu(user, 'Referat 50')
    expect(mockGetSpaceGroupMembers).not.toHaveBeenCalled()

    await user.click(within(menu).getByRole('menuitem', { name: 'Mitglieder der Gruppe anzeigen' }))

    expect(await screen.findByText('Anna Bauer')).toBeInTheDocument()
    expect(mockGetSpaceGroupMembers).toHaveBeenCalledWith('space-team', 'g1', 0, 50)
  })

  it('adds a group as a member through the group search', async () => {
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('Person oder Gruppe suchen'), 'Projekt')
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
   * #2205: Jede Aktion meldet sich als Popup (Leitlinie 5.9); in der Seite bleibt keine
   * Erfolgsmeldung stehen — auch nicht nach einer weiteren Aktion.
   */
  it('reports adding and removing as popups and leaves no success message in the page', async () => {
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()
    const panel = screen.getByRole('tabpanel')

    await user.type(await screen.findByLabelText('Person oder Gruppe suchen'), 'Projekt')
    await user.click(await screen.findByRole('option', { name: /Projektbeteiligte Phoenix/ }))
    await user.click(screen.getByRole('button', { name: /^hinzufügen$/i }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Gruppe Projektbeteiligte Phoenix hinzugefügt',
    )

    const menu = await openMemberMenu(user, 'Colleague')
    await user.click(within(menu).getByRole('menuitem', { name: 'Aus Space entfernen' }))
    await answerConfirm(user, 'Colleague aus diesem Space entfernen?', 'Entfernen')

    await waitFor(() =>
      expect(useNotificationStore.getState().queue.map((n) => n.message)).toEqual([
        'Gruppe Projektbeteiligte Phoenix hinzugefügt',
        'Colleague entfernt',
      ]),
    )
    expect(within(panel).queryByText(/hinzugefügt|entfernt/)).not.toBeInTheDocument()
    expect(within(panel).queryByRole('alert')).not.toBeInTheDocument()
  })

  /**
   * #2205: Ein Rollenwechsel aktualisiert die Liste still — kein „wird geladen“, keine neu
   * aufgebaute Zeile, der Fokus bleibt auf der Rollenauswahl — und meldet sich als Popup.
   */
  it('changes a role without a loading flicker, keeps the focus and reports it as a popup', async () => {
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()
    await screen.findByText('Colleague')
    await waitFor(() => expect(mockListSpaceMembers).toHaveBeenCalledTimes(1))
    let releaseRefresh: () => void = () => {}
    mockListSpaceMembers.mockImplementationOnce(
      (spaceId: string) =>
        new Promise((resolve) => {
          releaseRefresh = () => resolve(membersBySpaceId[spaceId] ?? [])
        }),
    )

    const roleSelect = within(memberRow('Colleague')).getByRole('combobox')
    await user.click(roleSelect)
    await user.click(await screen.findByRole('option', { name: 'Kurator' }))

    await waitFor(() => expect(mockListSpaceMembers).toHaveBeenCalledTimes(2))
    expect(screen.queryByText(/wird geladen/)).not.toBeInTheDocument()
    expect(within(memberRow('Colleague')).getByRole('combobox')).toBe(roleSelect)

    releaseRefresh()

    expect(await screen.findByRole('alert')).toHaveTextContent('Rolle von Colleague: Kurator')
    expect(mockUpdateSpaceMemberRole).toHaveBeenCalledWith('space-team', 'm-team-u2', 'CURATOR')
    expect(within(memberRow('Colleague')).getByRole('combobox')).toBe(roleSelect)
    expect(roleSelect).toHaveFocus()
    expect(screen.queryByText(/wird geladen/)).not.toBeInTheDocument()
  })

  /**
   * #2205: Wer sich selbst entfernt und damit den Zugang verliert, sieht keine Verwaltungsansicht
   * mehr, sondern landet in der Space-Übersicht und erfährt, warum.
   */
  it('leaves the settings for the overview when removing oneself cost the access', async () => {
    setSpaceState({ ...teamSpace, ownerId: 'someone-else' })
    renderTab('members')
    const user = userEvent.setup()
    await screen.findByText('Owner')
    vi.mocked(getSpace).mockRejectedValueOnce(
      new Error('HTTP 403', { cause: { response: { status: 403 } } }),
    )

    const menu = await openMemberMenu(user, 'Owner')
    await user.click(within(menu).getByRole('menuitem', { name: 'Aus Space entfernen' }))
    await answerConfirm(user, 'Owner aus diesem Space entfernen?', 'Entfernen')

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/spaces', { replace: true }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Owner entfernt. Sie haben keinen Zugang mehr zu „Team“.',
    )
    expect(useSpaceStore.getState().selectedSpace).toBeNull()
  })

  /** #2205: Ein Fehler bleibt in der Seite stehen, bis er geschlossen wird. */
  it('keeps a failure on screen until it is closed', async () => {
    mockUpdateSpaceMemberRole.mockRejectedValueOnce(
      new Error('Die Rolle konnte nicht geändert werden'),
    )
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()
    await screen.findByText('Colleague')

    await user.click(within(memberRow('Colleague')).getByRole('combobox'))
    await user.click(await screen.findByRole('option', { name: 'Kurator' }))

    const failure = await screen.findByText('Die Rolle konnte nicht geändert werden')
    expect(useNotificationStore.getState().queue).toEqual([])
    await user.click(
      within(failure.closest('[role="alert"]') as HTMLElement).getByRole('button', {
        name: /schließen/i,
      }),
    )
    expect(screen.queryByText('Die Rolle konnte nicht geändert werden')).not.toBeInTheDocument()
  })

  /**
   * #1815, ADR-0036 Entscheidung 6: the handover is no longer the owner's alone - every capable
   * ADMIN member that is a natural person may perform it. Driven here with an ADMIN caller who is
   * not the owner, which before this change saw no button at all.
   */
  it('offers the handover to an ADMIN member who is not the owner', async () => {
    setSpaceState({ ...teamSpace, ownerId: 'someone-else', userRole: 'ADMIN' })
    renderTab('members')
    const user = userEvent.setup()

    // Both person rows, never the group row - a space owner is always a natural person.
    for (const name of ['Owner', 'Colleague']) {
      const menu = await openMemberMenu(user, name)
      expect(within(menu).getByRole('menuitem', { name: 'Zum Eigentümer machen' })).toBeVisible()
      await user.keyboard('{Escape}')
    }
    const groupMenu = await openMemberMenu(user, 'Referat 50')
    expect(within(groupMenu).queryByText('Zum Eigentümer machen')).not.toBeInTheDocument()
    await user.keyboard('{Escape}')

    const menu = await openMemberMenu(user, 'Owner')
    await user.click(within(menu).getByRole('menuitem', { name: 'Zum Eigentümer machen' }))
    await answerConfirm(user, 'Verantwortung an Owner übertragen?', 'Übertragen')

    await waitFor(() => {
      expect(mockTransferSpaceOwnership).toHaveBeenCalledWith('space-team', 'u1')
    })
    // #2205: die Übertragung meldet sich wie jede Aktion als Popup.
    expect(await screen.findByRole('alert')).toHaveTextContent('Verantwortung an Owner übertragen')
    expect(screen.getByRole('tabpanel')).not.toHaveTextContent('Verantwortung übertragen')
  })

  it('names a failed group search instead of showing an empty picker', async () => {
    mockSearchSelectableGroups.mockRejectedValueOnce(new Error('offline'))
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText('Person oder Gruppe suchen'), 'Projekt')

    expect(await screen.findByText(/offline/)).toBeInTheDocument()
  })

  // #1815, ADR-0036 Entscheidung 6: state and addressee, without a date and without the previous
  // owner - the space stays usable.
  it('names the derived state "Nachfolge offen" without a date or a previous owner', () => {
    setSpaceState({ ...teamSpace, successionOpen: true })
    renderTab('general')

    expect(screen.getByText(/Nachfolge offen — zuständig: Systemverwaltung/)).toBeInTheDocument()
  })

  it('#777: renders the owner role as a read-only choice instead of an editable dropdown', async () => {
    // An editable Select on the owner's row always failed against the backend's "Die Rolle des
    // Eigentümers kann nicht geändert werden"; #2207 keeps its look but makes it read-only.
    setSpaceState(teamSpace)
    renderTab('members')

    await screen.findByText('Owner')
    const ownerRow = memberRow('Owner')
    const ownerRole = within(ownerRow).getByRole('combobox', { name: 'Rolle von „Owner“' })
    expect(ownerRole).toHaveAttribute('aria-readonly', 'true')
    // #2134: the owner carries "Eigentümer" alone, without the role label beside it.
    expect(ownerRole).toHaveTextContent('Eigentümer')
    expect(within(ownerRow).queryByText('Administrator')).not.toBeInTheDocument()

    // The (non-owner) colleague's row keeps its editable role Select.
    expect(within(memberRow('Colleague')).getByRole('combobox')).toBeInTheDocument()
  })

  /**
   * #1820, ADR-0036 Entscheidung 9: Eine geschuetzte Gruppe erscheint namenlos und ohne Zahl - die
   * Zeile bleibt, sonst koennte ein ADMIN eine Mitgliedschaft nicht beenden, die er nicht sieht.
   */
  it('renders a protected group as a nameless row that can still be removed', async () => {
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()

    await screen.findByText('Geschützte Gruppe')
    expect(screen.queryByText(/g2/)).not.toBeInTheDocument()
    expect(memberRow('Geschützte Gruppe').textContent).not.toMatch(/\d+ Mitglied/)
    const menu = await openMemberMenu(user, 'Geschützte Gruppe')
    await user.click(within(menu).getByRole('menuitem', { name: 'Aus Space entfernen' }))
    await answerConfirm(user, 'Geschützte Gruppe aus diesem Space entfernen?', 'Entfernen')

    await waitFor(() => {
      expect(mockRemoveSpaceMember).toHaveBeenCalledWith('space-team', 'm-team-personalrat')
    })
  })

  /** #1822: ob eine Rolle direkt oder ueber eine Gruppe kommt, beantwortet die Herleitung. */
  it('opens the derivation of a person on request and never for a group row', async () => {
    setSpaceState(teamSpace)
    renderTab('members')
    const user = userEvent.setup()

    const menu = await openMemberMenu(user, 'Colleague')
    await user.click(within(menu).getByRole('menuitem', { name: 'Warum hat Colleague Zugriff?' }))

    expect(
      await screen.findByText(
        'Colleague ist Mitglied in diesem Space – über die Gruppe Referat 50.',
      ),
    ).toBeInTheDocument()
    expect(mockGetSpaceAccessDerivation).toHaveBeenCalledWith('space-team', 'u2')
    const groupMenu = await openMemberMenu(user, 'Geschützte Gruppe')
    expect(within(groupMenu).queryByText(/Warum hat/)).not.toBeInTheDocument()
  })

  /**
   * #2207: Ein Mitglied sieht, wie viele Personen und Gruppen dem Space angehören und wie sich
   * die Rollen verteilen, aber keinen Namen - die Mitgliederliste bleibt Administratoren,
   * Eigentümer und Systemverwaltung vorbehalten.
   */
  it('shows a plain member persons, groups and roles as counts and no name', async () => {
    mockListSpaceMembers.mockResolvedValueOnce([])
    setSpaceState({
      ...nonAdminSpace,
      memberCount: 12,
      memberships: { groupCount: 2, userCount: 10 },
      roleCounts: { MEMBER: 9, CURATOR: 1, ADMIN: 2 },
    })
    renderTab('members')

    expect(await screen.findByText('10 Personen und 2 Gruppen')).toBeInTheDocument()
    expect(
      screen.getByText('Rollen: 2 Administratoren, 1 Kurator, 9 Mitglieder'),
    ).toBeInTheDocument()
    expect(screen.queryByText(/nicht die erforderliche rolle/i)).not.toBeInTheDocument()
    expect(screen.queryByTestId('space-member-row')).not.toBeInTheDocument()
    expect(screen.queryByText('Owner')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Mitglied hinzufügen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /entfernen/i })).not.toBeInTheDocument()
  })

  it('names a single person without groups and leaves out roles nobody holds', async () => {
    mockListSpaceMembers.mockResolvedValueOnce([])
    setSpaceState({
      ...nonAdminSpace,
      memberCount: 1,
      memberships: { groupCount: 0, userCount: 1 },
      roleCounts: { MEMBER: 1, CURATOR: 0, ADMIN: 0 },
    })
    renderTab('members')

    expect(await screen.findByText('1 Person')).toBeInTheDocument()
    expect(screen.getByText('Rollen: 1 Mitglied')).toBeInTheDocument()
  })

  /** #2207: Stammdaten sind für ein Mitglied lesbar, aber nicht änderbar, ohne Gefahrenbereich. */
  it('shows a plain member the general data read-only', () => {
    setSpaceState(nonAdminSpace)
    renderTab('general')

    expect(screen.getByLabelText('Name des Space')).toHaveValue('Fremdverwaltet')
    expect(screen.getByLabelText('Name des Space')).toHaveAttribute('readonly')
    expect(screen.getByLabelText('Beschreibung')).toHaveAttribute('readonly')
    expect(
      screen.queryByRole('button', { name: /einstellungen speichern/i }),
    ).not.toBeInTheDocument()
    expect(screen.queryByText('Gefahrenbereich')).not.toBeInTheDocument()
  })

  it('shows the delete button only for the owner of a non-personal space', () => {
    setSpaceState(teamSpace)
    renderTab('general')
    expect(screen.getByRole('button', { name: /space löschen/i })).toBeInTheDocument()
  })

  it('hides the delete button for a non-owner admin', () => {
    setSpaceState({ ...teamSpace, ownerId: 'someone-else' })
    renderTab('general')
    expect(screen.queryByRole('button', { name: /space löschen/i })).not.toBeInTheDocument()
  })

  it('saves settings by calling updateSpaceDetails with name and description only', async () => {
    setSpaceState(teamSpace)
    renderTab('general')
    const user = userEvent.setup()

    await user.clear(screen.getByLabelText(/name des space/i))
    await user.type(screen.getByLabelText(/name des space/i), 'Team Renamed')
    await user.click(screen.getByRole('button', { name: /einstellungen speichern/i }))

    await waitFor(() => {
      expect(mockUpdateSpaceDetails).toHaveBeenCalledWith(
        'space-team',
        'Team Renamed',
        'Team docs',
        undefined,
      )
    })
  })

  /** #2131: Ohne Space-Verzeichnis bewirkt eine Sichtbarkeit nichts - jeder Space ist privat. */
  it('offers no visibility setting', () => {
    setSpaceState(teamSpace)
    renderTab('general')

    expect(screen.queryByText(/sichtbarkeit/i)).not.toBeInTheDocument()
  })

  it('#1923: an admin switches the chat cleanup on, with the periods of the installation', async () => {
    setSpaceState(teamSpace)
    renderTab('general')
    const user = userEvent.setup()

    expect(screen.getByText('Angeheftete Chats sind ausgenommen.')).toBeVisible()
    await user.click(
      screen.getByRole('switch', {
        name: 'Inaktive Chats nach 90 Tagen archivieren und nach weiteren 365 Tagen löschen',
      }),
    )
    await user.click(screen.getByRole('button', { name: /einstellungen speichern/i }))

    await waitFor(() => {
      expect(mockUpdateSpaceDetails).toHaveBeenCalledWith('space-team', 'Team', 'Team docs', true)
    })
  })

  it("#1923: in somebody else's personal space the switch stays locked, even for an admin", () => {
    setSpaceState({ ...personalSpace, ownerId: 'someone-else' })
    renderTab('general')

    expect(
      screen.getByRole('switch', {
        name: 'Inaktive Chats nach 90 Tagen archivieren und nach weiteren 365 Tagen löschen',
      }),
    ).toBeDisabled()
    expect(screen.getByText(/legt nur die Person selbst fest/)).toBeInTheDocument()
  })

  it('#1923: a plain member sees the switch state but cannot change it', () => {
    setSpaceState({
      ...nonAdminSpace,
      chatAutoCleanup: { enabled: true, archiveAfterDays: 90, deleteAfterDays: 365 },
    })
    renderTab('general')

    const cleanup = screen.getByRole('switch', {
      name: 'Inaktive Chats nach 90 Tagen archivieren und nach weiteren 365 Tagen löschen',
    })
    expect(cleanup).toBeChecked()
    expect(cleanup).toBeDisabled()
  })

  // #543: Space mit fremden privaten Chats ist dauerhaft unlöschbar - Archivieren ist der Ausweg.

  it('shows the archive button for the owner of a non-personal, non-archived space', () => {
    setSpaceState(teamSpace)
    renderTab('general')
    expect(screen.getByRole('button', { name: /space archivieren/i })).toBeInTheDocument()
  })

  it('hides the archive button once the space is already archived and shows the badge', () => {
    setSpaceState({ ...teamSpace, archived: true })
    renderTab('general')
    expect(screen.queryByRole('button', { name: /space archivieren/i })).not.toBeInTheDocument()
    expect(screen.getByText('Archiviert')).toBeInTheDocument()
  })

  it('archives the space via the store when the owner confirms', async () => {
    setSpaceState(teamSpace)
    renderTab('general')
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
    renderTab('general')
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

  /**
   * Ein Reiter „Inhalte" für alle Asset-Typen; die Zuordnung selbst prüft
   * SpaceContentSection.test.tsx. Hier: welche Rolle anhaken darf.
   */
  it('lets a curator check contents on the tab "Inhalte"', async () => {
    setSpaceState({ ...nonAdminSpace, userRole: 'CURATOR' })
    renderTab('content')

    expect(await screen.findByRole('button', { name: 'Nur zugeordnete' })).toBeInTheDocument()
    expect(mockGetSpaceAssetAssociations).toHaveBeenCalledWith('space-team')
  })

  it('shows a plain member the contents of the tab "Inhalte" read-only', async () => {
    mockGetSpaceAssetAssociations.mockResolvedValue({
      hasAssociations: true,
      hasUnreadableAssociations: false,
      hasKnowledge: true,
      hasReadableKnowledge: true,
      items: [
        {
          assetType: 'KNOWLEDGE_LIBRARY',
          assetId: 'library-referat-50',
          name: 'Rechtsquellen Soziales',
          createdByUserId: 'u1',
          createdAt: '2026-03-01T10:00:00Z',
        },
      ],
    })
    setSpaceState(nonAdminSpace)
    renderTab('content')

    expect(
      await screen.findByRole('checkbox', { name: /^Rechtsquellen Soziales/ }),
    ).toHaveAttribute('aria-readonly', 'true')
    expect(screen.queryByRole('button', { name: 'Nur zugeordnete' })).not.toBeInTheDocument()
  })

  // The former tabs "Wissen" and "Prompts" live on in bookmarks and links.
  it.each(['knowledge', 'prompts'])('forwards the former tab "%s" to "Inhalte"', async (tab) => {
    setSpaceState(teamSpace)
    routeParams.tab = tab
    renderWithProviders(
      <>
        <SpaceSettingsPage />
        <LocationProbe />
      </>,
      { withRouter: true, initialRoute: `/spaces/space-team/settings/${tab}` },
    )

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        '/spaces/space-team/settings/content',
      ),
    )
  })
})
