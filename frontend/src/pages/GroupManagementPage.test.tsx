import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import GroupManagementPage from './GroupManagementPage'
import { useGroupStore } from '../stores/groupStore'
import type { GroupListResponse, GroupResponse } from '../types/api'

const {
  mockCreateGroup,
  mockUpdateGroup,
  mockDeleteGroup,
  mockAddGroupMember,
  mockRemoveGroupMember,
} = vi.hoisted(() => ({
  mockCreateGroup: vi.fn(async () => ({}) as GroupResponse),
  mockUpdateGroup: vi.fn(async () => ({}) as GroupResponse),
  mockDeleteGroup: vi.fn(async () => undefined),
  mockAddGroupMember: vi.fn(async () => ({})),
  mockRemoveGroupMember: vi.fn(async () => undefined),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getUsers: vi.fn(async () => []),
    getGroups: vi.fn(async () => useGroupStore.getState().groups),
    getGroup: vi.fn(async (groupId: string) => useGroupStore.getState().groupDetails[groupId]),
    createGroup: mockCreateGroup,
    updateGroup: mockUpdateGroup,
    deleteGroup: mockDeleteGroup,
    addGroupMember: mockAddGroupMember,
    removeGroupMember: mockRemoveGroupMember,
  }
})

const adHocGroup: GroupListResponse = {
  id: 'group-phoenix',
  name: 'Projektbeteiligte Phoenix',
  description: 'Ad hoc',
  kind: 'AD_HOC',
  externalId: null,
  parentGroupId: null,
  memberCount: 1,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const orgUnitGroup: GroupListResponse = {
  id: 'group-referat-50',
  name: 'Referat 50',
  description: 'Directory-synced',
  kind: 'ORG_UNIT',
  externalId: 'directory-guid',
  parentGroupId: null,
  memberCount: 1,
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

    expect(await screen.findByText(/noch keine gruppen/i)).toBeInTheDocument()
  })

  it('expands an ad-hoc group and allows renaming and deleting', async () => {
    setGroupState([adHocGroup], { 'group-phoenix': adHocDetails })
    renderWithProviders(<GroupManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByText('Projektbeteiligte Phoenix'))

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

    expect(await screen.findByText('Bob')).toBeInTheDocument()
    expect(screen.getByText(/aus dem verzeichnis synchronisiert/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /gruppe löschen/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^entfernen$/i })).not.toBeInTheDocument()
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
})
