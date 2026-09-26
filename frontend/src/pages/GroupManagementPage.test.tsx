import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../test/test-utils'
import GroupManagementPage from './GroupManagementPage'
import { useGroupStore } from '../stores/groupStore'
import type { GroupListResponse, GroupResponse } from '../types/api'

const {
  mockGetGroup,
  mockFetchedDetails,
  mockCreateGroup,
  mockUpdateGroup,
  mockDeleteGroup,
  mockAddGroupMember,
  mockRemoveGroupMember,
  mockAppointGroupContact,
  mockDismissGroupContact,
  mockListGroupMembers,
} = vi.hoisted(() => ({
  mockGetGroup: vi.fn(),
  mockListGroupMembers: vi.fn(),
  /** Was `getGroup` liefert, wenn der Store die Details noch nicht kennt. */
  mockFetchedDetails: {} as Record<string, GroupResponse>,
  mockCreateGroup: vi.fn(async () => ({}) as GroupResponse),
  mockUpdateGroup: vi.fn(async () => ({}) as GroupResponse),
  mockDeleteGroup: vi.fn(async () => undefined),
  mockAddGroupMember: vi.fn(async () => ({})),
  mockRemoveGroupMember: vi.fn(async () => undefined),
  mockAppointGroupContact: vi.fn(async () => ({
    userId: 'u2',
    displayName: 'Bob',
    appointedAt: '2026-09-01T10:00:00Z',
  })),
  mockDismissGroupContact: vi.fn(async () => undefined),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getUsers: vi.fn(async () => []),
    getGroups: vi.fn(async () => useGroupStore.getState().groups),
    getGroup: vi.fn(async (groupId: string) => {
      mockGetGroup(groupId)
      return useGroupStore.getState().groupDetails[groupId] ?? mockFetchedDetails[groupId]
    }),
    listGroupMembers: vi.fn(async (groupId: string) => {
      mockListGroupMembers(groupId)
      return mockFetchedDetails[groupId]?.members ?? []
    }),
    createGroup: mockCreateGroup,
    updateGroup: mockUpdateGroup,
    deleteGroup: mockDeleteGroup,
    addGroupMember: mockAddGroupMember,
    removeGroupMember: mockRemoveGroupMember,
    appointGroupContact: mockAppointGroupContact,
    dismissGroupContact: mockDismissGroupContact,
  }
})

const adHocGroup: GroupListResponse = {
  id: 'group-phoenix',
  name: 'Projektbeteiligte Phoenix',
  description: 'Ad hoc',
  kind: 'AD_HOC',
  externalId: null,
  origin: 'INTERNAL',
  provider: null,
  sourcePath: null,
  parentGroupId: null,
  memberCount: 1,
  dissolved: false,
  releasedForUse: false,
  protectedGroup: false,
  stewards: [{ userId: 'mock-user-id', displayName: 'Admin', appointedAt: '2026-03-01T10:00:00Z' }],
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const orgUnitGroup: GroupListResponse = {
  id: 'group-referat-50',
  name: 'Referat 50',
  description: 'Directory-synced',
  kind: 'ORG_UNIT',
  externalId: 'directory-guid',
  origin: 'PROVIDER',
  provider: {
    id: 'oidc-provider-beschaeftigte',
    displayName: 'Verzeichnisdienst',
    external: false,
    enabled: true,
    groupMechanism: 'TOKEN',
  },
  sourcePath: '/Haus A/Referat 50',
  parentGroupId: null,
  memberCount: 1,
  dissolved: false,
  releasedForUse: true,
  protectedGroup: false,
  stewards: [],
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const adHocDetails: GroupResponse = {
  ...adHocGroup,
  members: [{ userId: 'u1', displayName: 'Alice', createdAt: '2026-03-01T10:00:00Z' }],
}

const orgUnitDetails: GroupResponse = {
  ...orgUnitGroup,
  members: [{ userId: 'u2', displayName: 'Bob', createdAt: '2026-03-01T10:00:00Z' }],
}

function setGroupState(groups: GroupListResponse[], details: Record<string, GroupResponse>) {
  useGroupStore.setState({
    groups,
    groupDetails: details,
    isLoading: false,
    error: null,
  })
}

describe('GroupManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists groups with their kind', async () => {
    setGroupState([adHocGroup, orgUnitGroup], {})
    renderWithProviders(<GroupManagementPage />, { withRouter: true })

    expect(await screen.findByText('Projektbeteiligte Phoenix')).toBeInTheDocument()
    expect(screen.getByText('Referat 50')).toBeInTheDocument()
    expect(screen.getByText('Ad-hoc-Gruppe')).toBeInTheDocument()
    expect(screen.getByText('Organisationseinheit')).toBeInTheDocument()
  })

  it('shows an empty state when there are no groups', async () => {
    setGroupState([], {})
    renderWithProviders(<GroupManagementPage />, { withRouter: true })

    expect(await screen.findByText(/keine gruppen dieser herkunft/i)).toBeInTheDocument()
  })

  it('expands an ad-hoc group and allows renaming and deleting', async () => {
    setGroupState([adHocGroup], { 'group-phoenix': adHocDetails })
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))

    await user.click(await screen.findByRole('button', { name: /mitglieder anzeigen/i }))
    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /gruppe löschen/i })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /speichern/i }))

    await waitFor(() => {
      expect(mockUpdateGroup).toHaveBeenCalledWith(
        'group-phoenix',
        'Projektbeteiligte Phoenix',
        'Ad hoc',
      )
    })
  })

  // ADR-0025, Entscheidung 4 (#1331): a token-derived group is read-only like an org unit, but
  // the explanation names its actual source
  it('explains a group from the identity provider and keeps it read-only', async () => {
    const tokenGroup: GroupListResponse = {
      ...orgUnitGroup,
      id: 'group-token-fachbereich',
      name: 'Fachbereich 3',
      kind: 'IDENTITY_PROVIDER',
      externalId: 'oidc:p-partner:Fachbereich 3',
    }
    setGroupState([tokenGroup], {
      'group-token-fachbereich': {
        ...tokenGroup,
        members: [{ userId: 'u3', displayName: 'Carla', createdAt: '2026-03-01T10:00:00Z' }],
      },
    })
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    expect(await screen.findByText('Gruppe aus dem Identitätsanbieter')).toBeInTheDocument()
    await user.click(screen.getByText('Fachbereich 3'))

    await user.click(await screen.findByRole('button', { name: /mitglieder anzeigen/i }))
    expect(await screen.findByText('Carla')).toBeInTheDocument()
    expect(screen.getByText(/stammt aus dem identitätsanbieter/i)).toBeInTheDocument()
    expect(screen.queryByText(/aus dem verzeichnis synchronisiert/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /gruppe löschen/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^entfernen$/i })).not.toBeInTheDocument()
  })

  it('disables editing and member management for an org-unit group', async () => {
    setGroupState([orgUnitGroup], { 'group-referat-50': orgUnitDetails })
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Referat 50'))

    await user.click(await screen.findByRole('button', { name: /mitglieder anzeigen/i }))
    expect(await screen.findByText('Bob')).toBeInTheDocument()
    expect(screen.getByText(/aus dem verzeichnis synchronisiert/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /gruppe löschen/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^entfernen$/i })).not.toBeInTheDocument()
  })

  it('deletes an ad-hoc group once the confirmation was answered', async () => {
    setGroupState([adHocGroup], { 'group-phoenix': adHocDetails })
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))
    await user.click(await screen.findByRole('button', { name: /gruppe löschen/i }))
    await answerConfirm(user, 'Gruppe "Projektbeteiligte Phoenix" löschen?', 'Löschen')

    await waitFor(() => {
      expect(mockDeleteGroup).toHaveBeenCalledWith('group-phoenix')
    })
  })

  it('creates a new group through the dialog', async () => {
    setGroupState([], {})
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue gruppe/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Neue Gruppe')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateGroup).toHaveBeenCalledWith('Neue Gruppe', '')
    })
  })

  // #1821: Die Herkunft steht ohne Aufklappen da, und der Filter trennt intern von Anbieter.
  it('shows origin without expanding and filters by it', async () => {
    setGroupState([adHocGroup, orgUnitGroup], {})
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    expect(await screen.findByText(/Herkunft: Verzeichnisdienst/)).toBeInTheDocument()
    expect(screen.getByText(/\/Haus A\/Referat 50/)).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: /herkunft/i }))
    await user.click(await screen.findByRole('option', { name: 'Intern' }))

    await waitFor(() => expect(screen.queryByText('Referat 50')).not.toBeInTheDocument())
    expect(screen.getByText('Projektbeteiligte Phoenix')).toBeInTheDocument()
  })

  // ADR-0036, Entscheidung 4/9: Der Abruf der Mitgliederliste ist das Audit-Ereignis - zugesichert
  // ist deshalb die ausbleibende ANFRAGE, nicht nur die ausbleibende Anzeige. Die Details werden
  // hier bewusst nicht vorbelegt: Sonst bliebe der Test auch dann grün, wenn jemand die Bedingung
  // im Effekt zurücknähme.
  it('does not load the member list until it is asked for', async () => {
    setGroupState([adHocGroup], {})
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))

    expect(await screen.findByText(/Audit-Ereignis/)).toBeInTheDocument()
    expect(mockListGroupMembers).not.toHaveBeenCalled()

    mockFetchedDetails['group-phoenix'] = adHocDetails
    await user.click(screen.getByRole('button', { name: /mitglieder anzeigen/i }))

    // #1989: only the recorded endpoint hands the administration the names.
    await waitFor(() => expect(mockListGroupMembers).toHaveBeenCalledWith('group-phoenix'))
    expect(mockGetGroup).not.toHaveBeenCalled()
    expect(await screen.findByText('Alice')).toBeInTheDocument()
  })

  // #1821: Eine aufgelöste Gruppe ist gekennzeichnet und nennt den Grund, warum sie nicht mehr
  // gewählt werden kann - ihre bestehenden Berechtigungen bleiben.
  it('marks a dissolved group and names the reason', async () => {
    const dissolved: GroupListResponse = { ...orgUnitGroup, dissolved: true }
    setGroupState([dissolved], {})
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    expect(await screen.findByText('aufgelöst')).toBeInTheDocument()
    await user.click(screen.getByText('Referat 50'))
    expect(await screen.findByText(/Aufgelöst — die Quelle meldet/)).toBeInTheDocument()
  })

  // #1875: Die Ansprechstelle einer Anbietergruppe benennen - benennbar ist nur ein Mitglied,
  // deshalb hängt die Auswahl am Abruf der Mitgliederliste.
  it('benennt ein Mitglied einer Anbietergruppe als Ansprechstelle', async () => {
    setGroupState([orgUnitGroup], { 'group-referat-50': orgUnitDetails })
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Referat 50'))
    expect(
      await screen.findByText(/Für diese Gruppe ist keine Ansprechstelle benannt/),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: /ansprechstelle/i }))
    await user.click(await screen.findByRole('option', { name: 'Bob' }))
    await user.click(screen.getByRole('button', { name: /als ansprechstelle benennen/i }))

    await waitFor(() =>
      expect(mockAppointGroupContact).toHaveBeenCalledWith('group-referat-50', 'u2'),
    )
  })

  it('entlässt eine Ansprechstelle nach Rückfrage', async () => {
    const withContact: GroupListResponse = {
      ...orgUnitGroup,
      contacts: [{ userId: 'u2', displayName: 'Bob', appointedAt: '2026-09-01T10:00:00Z' }],
    }
    setGroupState([withContact], {
      'group-referat-50': { ...orgUnitDetails, contacts: withContact.contacts },
    })
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Referat 50'))
    await user.click(await screen.findByRole('button', { name: /entlassen/i }))
    await answerConfirm(user, 'Bob als Ansprechstelle entlassen?', 'Entlassen')

    await waitFor(() =>
      expect(mockDismissGroupContact).toHaveBeenCalledWith('group-referat-50', 'u2'),
    )
  })

  /** Eine interne Gruppe hat Verantwortliche - dort steht der Abschnitt nicht. */
  it('zeigt an einer internen Gruppe keine Ansprechstelle', async () => {
    setGroupState([adHocGroup], { 'group-phoenix': adHocDetails })
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))

    expect(await screen.findByText('Verantwortlich')).toBeInTheDocument()
    expect(screen.queryByText(/Ansprechstellen sprechen für diese Gruppe/)).not.toBeInTheDocument()
  })
})
