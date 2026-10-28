import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../../test/test-utils'
import AssetGrantsSection from './AssetGrantsSection'
import { useAuthStore } from '@/shared/session/authStore'
import { assetKey, useGrantStore } from '../stores/grantStore'
import type {
  AssetGrantRequest,
  AssetGrantResponse,
  SelectableGroupResponse,
  UserSummary,
} from '@/shared/api/types'

const {
  mockGetAssetGrants,
  mockUpsertAssetGrant,
  mockRevokeAssetGrant,
  mockSearchSelectableGroups,
  mockResolveSelectableGroup,
  mockGetUserSummaries,
  mockGetGrantedGroupMembers,
} = vi.hoisted(() => ({
  mockGetAssetGrants: vi.fn(async (assetType: string, assetId: string) => {
    return useGrantStore.getState().grantsByAsset[`${assetType}:${assetId}`] ?? []
  }),
  mockUpsertAssetGrant: vi.fn(),
  mockRevokeAssetGrant: vi.fn(async () => undefined),
  mockSearchSelectableGroups: vi.fn(async () => [] as SelectableGroupResponse[]),
  mockResolveSelectableGroup: vi.fn(),
  mockGetUserSummaries: vi.fn(async () => [] as UserSummary[]),
  mockGetGrantedGroupMembers: vi.fn(async () => ({
    groupId: 'group-referat-50',
    name: 'Referat 50',
    protectedGroup: false,
    activeMemberCount: 1,
    members: [{ userId: 'user-anna', displayName: 'Anna Bauer' }],
    responsible: [] as string[],
  })),
}))

vi.mock('../api/assetApi', async () => {
  const actual = await vi.importActual<typeof import('../api/assetApi')>('../api/assetApi')
  return {
    ...actual,
    getAssetGrants: mockGetAssetGrants,
    upsertAssetGrant: mockUpsertAssetGrant,
    revokeAssetGrant: mockRevokeAssetGrant,
    getGrantedGroupMembers: mockGetGrantedGroupMembers,
  }
})

vi.mock('@/features/principal/api/groupLookupApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/principal/api/groupLookupApi')>(
    '@/features/principal/api/groupLookupApi',
  )
  return {
    ...actual,
    searchSelectableGroups: mockSearchSelectableGroups,
    resolveSelectableGroup: mockResolveSelectableGroup,
  }
})

vi.mock('@/features/principal/api/userApi', async () => {
  const actual = await vi.importActual<typeof import('@/features/principal/api/userApi')>(
    '@/features/principal/api/userApi',
  )
  return {
    ...actual,
    getUserSummaries: mockGetUserSummaries,
  }
})

const library = { id: 'library-team', name: 'Rechtsquellen Soziales' }

const group: SelectableGroupResponse = {
  id: 'group-referat-50',
  name: 'Referat 50',
  origin: 'PROVIDER',
  provider: {
    id: 'oidc-provider-beschaeftigte',
    displayName: 'Verzeichnis Haus A',
    external: false,
    enabled: true,
    groupMechanism: 'DIRECTORY',
  },
  sourcePath: '/Haus/Abteilung 5/Referat 50',
  activeMemberCount: 23,
  smallGroup: false,
  emptyGroup: false,
  protectedGroup: false,
  selectable: true,
  dissolved: false,
  deactivated: false,
  providerDisabled: false,
  unmaintained: false,
}

const user: UserSummary = {
  id: 'user-alice',
  email: 'alice@opaa.local',
  displayName: 'Alice',
}

function setGrants(libraryId: string, grants: AssetGrantResponse[]) {
  useGrantStore.setState({
    grantsByAsset: { [assetKey('KNOWLEDGE_LIBRARY', libraryId)]: grants },
    isLoading: false,
    error: null,
  })
}

function setManager() {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: {
      id: 'manager-1',
      email: 'manager@opaa.local',
      displayName: 'Manager',
      systemRole: 'USER',
    },
    token: null,
    error: null,
    userManager: null,
  })
}

function setSystemAdmin() {
  useAuthStore.setState({
    mode: 'dev',
    isAuthenticated: true,
    isLoading: false,
    user: {
      id: 'admin-1',
      email: 'admin@opaa.local',
      displayName: 'Admin',
      systemRole: 'SYSTEM_ADMIN',
    },
    token: null,
    error: null,
    userManager: null,
  })
}

describe('AssetGrantsSection', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockSearchSelectableGroups.mockResolvedValue([group])
    mockGetUserSummaries.mockResolvedValue([user])
    useGrantStore.setState({ grantsByAsset: {}, isLoading: false, error: null })
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
  })

  it('shows existing grants with the resolved subject name, not the raw id', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-1',
        subjectType: 'USER',
        subjectId: 'user-alice',
        subjectDisplayName: 'Alice',
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'admin-1',
        grantedByDisplayName: 'Admin',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)

    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.queryByText('user-alice')).not.toBeInTheDocument()
  })

  it('shows the ceiling the caller passes in its slot, below the empty list', async () => {
    setSystemAdmin()
    setGrants(library.id, [])
    renderWithProviders(
      <AssetGrantsSection
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        capControl={<p>Obergrenze der Systemverwaltung</p>}
      />,
    )

    expect(await screen.findByText('Obergrenze der Systemverwaltung')).toBeInTheDocument()
    expect(screen.getByText('Noch keine Freigaben.')).toBeInTheDocument()
    expect(mockGetAssetGrants).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id)
  })

  it('marks an expired grant as expired instead of hiding it', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-expired',
        subjectType: 'USER',
        subjectId: 'user-alice',
        subjectDisplayName: 'Alice',
        role: 'VIEWER',
        expiresAt: '2020-01-01T00:00:00.000Z',
        grantedByUserId: 'admin-1',
        grantedByDisplayName: 'Admin',
        createdAt: '2019-01-01T10:00:00Z',
        updatedAt: '2019-01-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)

    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.getByText(/abgelaufen/)).toBeInTheDocument()
  })

  it('shows an expiry date for a time-limited grant', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-future',
        subjectType: 'USER',
        subjectId: 'user-alice',
        subjectDisplayName: 'Alice',
        role: 'VIEWER',
        expiresAt: '2099-12-31T12:00:00.000Z',
        grantedByUserId: 'admin-1',
        grantedByDisplayName: 'Admin',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)

    expect(await screen.findByText(/bis 31\.12\.2099/)).toBeInTheDocument()
  })

  it('grants a person VIEWER access and shows it without a reload', async () => {
    setSystemAdmin()
    mockUpsertAssetGrant.mockImplementationOnce(
      async (_libraryId: string, request: AssetGrantRequest) => {
        const created: AssetGrantResponse = {
          id: 'grant-new',
          subjectType: request.subjectType,
          subjectId: request.subjectId,
          // The real backend resolves this server-side (AssetGrantService#toResponses) and
          // returns it on the very same upsert response - the mock mirrors that here.
          subjectDisplayName: 'Alice',
          role: request.role,
          expiresAt: request.expiresAt ?? null,
          grantedByUserId: 'admin-1',
          grantedByDisplayName: 'Admin',
          createdAt: '2026-03-05T10:00:00Z',
          updatedAt: '2026-03-05T10:00:00Z',
        }
        return created
      },
    )
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    // The picker does not preload the whole organization - a query
    // (min. 2 characters) has to be typed before GET /v1/users is even attempted.
    await userEventInstance.type(await screen.findByLabelText(/^person oder gruppe suchen$/i), 'al')
    await userEventInstance.click(await screen.findByRole('option', { name: /Alice/ }))
    await userEventInstance.click(
      screen.getAllByRole('button', { name: /^freigeben$/i })[
        screen.getAllByRole('button', { name: /^freigeben$/i }).length - 1
      ],
    )

    await waitFor(() => {
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, {
        subjectType: 'USER',
        subjectId: 'user-alice',
        role: 'VIEWER',
        expiresAt: null,
      })
    })
    expect(await screen.findByText('Alice')).toBeInTheDocument()
  })

  it('grants a group access', async () => {
    setSystemAdmin()
    mockUpsertAssetGrant.mockResolvedValueOnce({
      id: 'grant-group',
      subjectType: 'GROUP',
      subjectId: group.id,
      role: 'VIEWER',
      expiresAt: null,
      grantedByUserId: 'admin-1',
      createdAt: '2026-03-05T10:00:00Z',
      updatedAt: '2026-03-05T10:00:00Z',
    } satisfies AssetGrantResponse)
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.type(
      await screen.findByLabelText(/^person oder gruppe suchen$/i),
      'Referat',
    )
    await userEventInstance.click(await screen.findByRole('option', { name: /Referat 50/ }))
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    await waitFor(() => {
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, {
        subjectType: 'GROUP',
        subjectId: group.id,
        role: 'VIEWER',
        expiresAt: null,
      })
    })
  })

  // The third recipient - it names nobody, and it is the one that asks back.
  it('grants every account of the organization after an explicit confirmation', async () => {
    setSystemAdmin()
    mockUpsertAssetGrant.mockResolvedValueOnce({
      id: 'grant-all',
      subjectType: 'ALL_ACCOUNTS',
      subjectDisplayName: 'Alle Konten',
      role: 'VIEWER',
      expiresAt: null,
      grantedByUserId: 'admin-1',
      createdAt: '2026-03-05T10:00:00Z',
      updatedAt: '2026-03-05T10:00:00Z',
    } satisfies AssetGrantResponse)
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByLabelText(/^person oder gruppe suchen$/i))
    await userEventInstance.click(await screen.findByRole('option', { name: /Alle Konten/ }))
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    await answerConfirm(userEventInstance, 'An alle Konten freigeben?', 'An alle freigeben')

    await waitFor(() => {
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, {
        subjectType: 'ALL_ACCOUNTS',
        role: 'VIEWER',
        expiresAt: null,
      })
    })
    const list = await screen.findByRole('list', { name: 'Freigaben' })
    expect(within(list).getAllByText('Alle Konten').length).toBeGreaterThan(0)
  })

  // The follow-up question depends on the reach, not on the form: a role change on the
  // existing row „Alle Konten" enlarges it as well and is therefore asked just the same.
  it('asks back before raising the role of an existing all-accounts grant, and drops it on no', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-all',
        subjectType: 'ALL_ACCOUNTS',
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'admin-1',
        grantedByDisplayName: 'Admin',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(
      await screen.findByRole('combobox', { name: /^rolle für alle konten$/i }),
    )
    await userEventInstance.click(await screen.findByRole('option', { name: 'Bearbeiter' }))
    await answerConfirm(userEventInstance, 'An alle Konten freigeben?', 'Abbrechen')

    await waitFor(() => expect(mockUpsertAssetGrant).not.toHaveBeenCalled())

    await userEventInstance.click(
      await screen.findByRole('combobox', { name: /^rolle für alle konten$/i }),
    )
    await userEventInstance.click(await screen.findByRole('option', { name: 'Bearbeiter' }))
    await answerConfirm(userEventInstance, 'An alle Konten freigeben?', 'An alle freigeben')

    await waitFor(() =>
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, {
        subjectType: 'ALL_ACCOUNTS',
        subjectId: undefined,
        role: 'EDITOR',
        expiresAt: null,
      }),
    )
  }, 15000)

  it('grants nothing when the confirmation for all accounts is declined', async () => {
    setSystemAdmin()
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByLabelText(/^person oder gruppe suchen$/i))
    await userEventInstance.click(await screen.findByRole('option', { name: /Alle Konten/ }))
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    await answerConfirm(userEventInstance, 'An alle Konten freigeben?', 'Abbrechen')

    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  it('rejects an expiry date in the past before calling the API', async () => {
    setSystemAdmin()
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.type(await screen.findByLabelText(/^person oder gruppe suchen$/i), 'al')
    await userEventInstance.click(await screen.findByRole('option', { name: /Alice/ }))
    const dateField = screen.getByLabelText(/befristung/i)
    fireEvent.change(dateField, { target: { value: '2020-01-01' } })
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(
      await screen.findByText(/ablaufdatum darf nicht in der vergangenheit liegen/i),
    ).toBeInTheDocument()
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  it('changes the role of an existing grant without creating a second entry', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-1',
        subjectType: 'USER',
        subjectId: 'user-alice',
        subjectDisplayName: 'Alice',
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'admin-1',
        grantedByDisplayName: 'Admin',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    mockUpsertAssetGrant.mockResolvedValueOnce({
      id: 'grant-1',
      subjectType: 'USER',
      subjectId: 'user-alice',
      role: 'EDITOR',
      expiresAt: null,
      grantedByUserId: 'admin-1',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-06T10:00:00Z',
    } satisfies AssetGrantResponse)
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    // The row-level role select's accessible name names its subject
    // ("Rolle für Alice"), not a "Rolle" shared by every row.
    await userEventInstance.click(
      await screen.findByRole('combobox', { name: /^rolle für alice$/i }),
    )
    await userEventInstance.click(await screen.findByRole('option', { name: 'Bearbeiter' }))

    await waitFor(() => {
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, {
        subjectType: 'USER',
        subjectId: 'user-alice',
        role: 'EDITOR',
        expiresAt: null,
      })
    })
    expect(
      useGrantStore.getState().grantsByAsset[assetKey('KNOWLEDGE_LIBRARY', library.id)],
    ).toHaveLength(1)
  })

  it('revokes a grant after confirmation and removes it from the list', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-1',
        subjectType: 'USER',
        subjectId: 'user-alice',
        subjectDisplayName: 'Alice',
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'admin-1',
        grantedByDisplayName: 'Admin',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(
      await screen.findByRole('button', { name: 'Weitere Aktionen für „Alice“' }),
    )
    await userEventInstance.click(await screen.findByRole('menuitem', { name: 'Entziehen' }))
    await answerConfirm(userEventInstance, 'Freigabe für "Alice" entziehen?', 'Entziehen')

    await waitFor(() => {
      expect(mockRevokeAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, 'grant-1')
    })
    expect(screen.queryByText('Alice')).not.toBeInTheDocument()
  })

  it('shows a German 403 message instead of failing silently', async () => {
    setSystemAdmin()
    mockUpsertAssetGrant.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Bibliothek'))
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.type(await screen.findByLabelText(/^person oder gruppe suchen$/i), 'al')
    await userEventInstance.click(await screen.findByRole('option', { name: /Alice/ }))
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(await screen.findByText('Kein Zugriff auf diese Bibliothek')).toBeInTheDocument()
  })

  it('names a failed person search and offers the id field as the way out', async () => {
    // GET /v1/users is reachable for every authenticated caller now, not just SYSTEM_ADMIN.
    // The fallback to the identifier is chosen explicitly instead of switching itself
    // on - a failed search says so in the field, and the path next to it is
    // the same as for a group.
    setManager()
    mockGetUserSummaries.mockRejectedValueOnce(new Error('Netzwerkfehler'))
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.type(await screen.findByLabelText(/^person oder gruppe suchen$/i), 'al')

    expect(await screen.findByText(/Netzwerkfehler/)).toBeInTheDocument()
    await userEventInstance.click(screen.getByRole('button', { name: /kennung eingeben/i }))
    expect(await screen.findByLabelText(/nutzer-id/i)).toBeInTheDocument()
    expect(mockGetUserSummaries).toHaveBeenCalledWith('al')
  })

  it('submits a manually entered, valid user id', async () => {
    setManager()
    mockUpsertAssetGrant.mockResolvedValueOnce({
      id: 'grant-manual',
      subjectType: 'USER',
      subjectId: '11111111-2222-4333-8444-555555555555',
      subjectDisplayName: null,
      role: 'VIEWER',
      expiresAt: null,
      grantedByUserId: 'manager-1',
      grantedByDisplayName: 'Manager',
      createdAt: '2026-03-05T10:00:00Z',
      updatedAt: '2026-03-05T10:00:00Z',
    } satisfies AssetGrantResponse)
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('button', { name: /kennung eingeben/i }))
    await userEventInstance.type(
      await screen.findByLabelText(/nutzer-id/i),
      '11111111-2222-4333-8444-555555555555',
    )
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    await waitFor(() => {
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, {
        subjectType: 'USER',
        subjectId: '11111111-2222-4333-8444-555555555555',
        role: 'VIEWER',
        expiresAt: null,
      })
    })
  })

  it('rejects a manually entered user id that is not a valid UUID before calling the API', async () => {
    setManager()
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('button', { name: /kennung eingeben/i }))
    await userEventInstance.type(await screen.findByLabelText(/nutzer-id/i), 'anna.beispiel')
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(await screen.findByText(/nutzer-id muss eine gültige uuid sein/i)).toBeInTheDocument()
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  it('shows resolved subject and granter names for a MANAGER without a system role', async () => {
    // These names come from the grant response itself (subjectDisplayName/grantedByDisplayName),
    // never looked up client-side.
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-1',
        subjectType: 'USER',
        subjectId: 'user-alice',
        subjectDisplayName: 'Alice',
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
      {
        id: 'grant-2',
        subjectType: 'GROUP',
        subjectId: group.id,
        subjectDisplayName: 'Referat 50',
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)

    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.getByText('Referat 50')).toBeInTheDocument()
    // who conferred the role stands on the row, on request
    const rows = within(screen.getByRole('list', { name: 'Freigaben' })).getAllByRole('listitem')
    expect(rows.map((row) => row.getAttribute('title'))).toEqual([
      'Rolle vergeben von Manager',
      'Rolle vergeben von Manager',
    ])
    expect(screen.queryByText('user-alice')).not.toBeInTheDocument()
  })

  it('#1052: names the granter without pairing it with the creation date', async () => {
    // grantedByUserId names whoever conferred the current role. Pairing that name with createdAt
    // would show a granter/date combination that never existed once a later role change moved the
    // name on.
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-1',
        subjectType: 'USER',
        subjectId: 'user-alice',
        subjectDisplayName: 'Alice',
        role: 'EDITOR',
        expiresAt: null,
        grantedByUserId: 'admin-2',
        grantedByDisplayName: 'Admin B',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-08-20T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)

    const row = (await screen.findByText('Alice')).closest('li')
    expect(row).toHaveAttribute('title', 'Rolle vergeben von Admin B')
    expect(screen.queryByText(/1\.3\.2026|01\.03\.2026/)).not.toBeInTheDocument()
  })

  it('#777: offers the searchable user picker for a MANAGER without a system role', async () => {
    // Before this fix, the user picker was only ever attempted for SYSTEM_ADMIN callers (GET
    // /v1/admin/users) - every other MANAGER went straight to the free-text UUID field, even
    // though the user list loaded successfully via GET /v1/users.
    setManager()
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))

    const personField = await screen.findByLabelText(/^person oder gruppe suchen$/i)
    expect(screen.queryByLabelText(/^nutzer-id$/i)).not.toBeInTheDocument()

    // No preload on mount - the search only runs once queried.
    await userEventInstance.type(personField, 'al')
    await waitFor(() => expect(mockGetUserSummaries).toHaveBeenCalledWith('al'))
    expect(await screen.findByRole('option', { name: /Alice/ })).toBeInTheDocument()
  })

  it('offers a manual group id as an alternative to the search', async () => {
    mockResolveSelectableGroup.mockResolvedValue({
      ...group,
      id: '22222222-3333-4444-8555-666666666666',
    })
    // Entry by identifier is subject to the same enforcement as the search - the service
    // answers not found there as well when the group is not shared.
    setManager()
    mockUpsertAssetGrant.mockResolvedValueOnce({
      id: 'grant-other-group',
      subjectType: 'GROUP',
      subjectId: '22222222-3333-4444-8555-666666666666',
      subjectDisplayName: null,
      role: 'VIEWER',
      expiresAt: null,
      grantedByUserId: 'manager-1',
      grantedByDisplayName: 'Manager',
      createdAt: '2026-03-05T10:00:00Z',
      updatedAt: '2026-03-05T10:00:00Z',
    } satisfies AssetGrantResponse)
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('button', { name: /kennung eingeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: 'Gruppe' }))
    await userEventInstance.type(
      await screen.findByLabelText(/gruppen-id/i),
      '22222222-3333-4444-8555-666666666666',
    )
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])
    await answerConfirm(userEventInstance, /Recht an .Referat 50. erteilen\?/, 'Weiter')

    await waitFor(() => {
      expect(mockUpsertAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, {
        subjectType: 'GROUP',
        subjectId: '22222222-3333-4444-8555-666666666666',
        role: 'VIEWER',
        expiresAt: null,
      })
    })
  })

  it("warns specifically about self-lockout when revoking one's own grant", async () => {
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-self',
        subjectType: 'USER',
        subjectId: 'manager-1',
        subjectDisplayName: 'Manager',
        role: 'MANAGER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(
      await screen.findByRole('button', { name: 'Weitere Aktionen für „Manager“' }),
    )
    await userEventInstance.click(await screen.findByRole('menuitem', { name: 'Entziehen' }))

    // The overlay lies over the sharing dialog; the question makes it unambiguous.
    const question = 'Freigabe für "Manager" entziehen?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /eigene freigabe/i,
    )
    await answerConfirm(userEventInstance, question, 'Abbrechen')

    expect(mockRevokeAssetGrant).not.toHaveBeenCalled()
  })

  /** ADR-0036, Entscheidung 2: granting to an external group requires a confirmation question. */
  it('asks back before granting to a group of an external provider', async () => {
    setSystemAdmin()
    mockSearchSelectableGroups.mockResolvedValue([
      {
        ...group,
        id: 'group-referat-50-partner',
        provider: {
          id: 'oidc-provider-partner',
          displayName: 'Verzeichnis Partner',
          external: true,
          enabled: true,
          groupMechanism: 'TOKEN',
        },
        sourcePath: null,
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.type(
      await screen.findByLabelText(/^person oder gruppe suchen$/i),
      'Referat',
    )
    await userEventInstance.click(await screen.findByRole('option', { name: /Referat 50/ }))
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    const question = 'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Verzeichnis Partner/,
    )
    await answerConfirm(userEventInstance, question, 'Abbrechen')

    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  /** A group grant names today's member count, no growth signal. */
  it('shows the current member count of a group grant', async () => {
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-group',
        subjectType: 'GROUP',
        subjectId: group.id,
        subjectDisplayName: 'Referat 50',
        protectedGroup: false,
        activeMemberCount: 41,
        emptyGroup: false,
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)

    // the member count is itself the way to the members
    expect(await screen.findByRole('button', { name: /^41 Mitglieder/ })).toBeInTheDocument()
    expect(screen.queryByText(/bei Erteilung/)).not.toBeInTheDocument()
  })

  /**
   * ADR-0036 Entscheidung 9: whoever granted the group a right here sees to whom —
   * and the list is only built on explicit request, not when the dialog opens.
   */
  it('offers the member list of a granted group and loads it only on request', async () => {
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-group',
        subjectType: 'GROUP',
        subjectId: group.id,
        subjectDisplayName: 'Referat 50',
        protectedGroup: false,
        activeMemberCount: 41,
        emptyGroup: false,
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const trigger = await screen.findByRole('button', {
      name: /^41 Mitglieder\s?der Gruppe „Referat 50“ anzeigen$/,
    })
    expect(mockGetGrantedGroupMembers).not.toHaveBeenCalled()

    await userEvent.click(trigger)

    expect(await screen.findByText('Anna Bauer')).toBeInTheDocument()
    expect(mockGetGrantedGroupMembers).toHaveBeenCalledWith(
      'KNOWLEDGE_LIBRARY',
      library.id,
      group.id,
      0,
      50,
    )
  })

  /** A group below the minimum group size shows its number as well. */
  it('shows the figure of a small group too', async () => {
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-small',
        subjectType: 'GROUP',
        subjectId: group.id,
        subjectDisplayName: 'Referat 50',
        protectedGroup: false,
        activeMemberCount: 2,
        emptyGroup: false,
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)

    expect(await screen.findByRole('button', { name: /^2 Mitglieder/ })).toBeInTheDocument()
    expect(screen.queryByText(/kleine Gruppe/)).not.toBeInTheDocument()
  })

  /** ADR-0036, Entscheidung 9: nameless row, no signal - and still revocable. */
  it('shows a protected group as a nameless row without any figure', async () => {
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-protected',
        subjectType: 'GROUP',
        subjectId: 'group-personalrat',
        subjectDisplayName: null,
        protectedGroup: true,
        activeMemberCount: null,
        emptyGroup: null,
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)

    expect(await screen.findByText('Geschützte Gruppe')).toBeInTheDocument()
    expect(screen.queryByText('group-personalrat')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Mitglied/ })).not.toBeInTheDocument()
    await userEvent.click(
      screen.getByRole('button', { name: 'Weitere Aktionen für „Geschützte Gruppe“' }),
    )
    expect(await screen.findByRole('menuitem', { name: 'Entziehen' })).toBeInTheDocument()
  })

  /**
   * ADR-0036, Entscheidung 2: the identifier path shows neither origin nor symbol - so it
   * resolves the group before granting and asks the same confirmation question. Without that, the
   * question for external providers could be bypassed by typing the identifier.
   */
  it('asks back on the id path before granting to a group of an external provider', async () => {
    setManager()
    mockResolveSelectableGroup.mockResolvedValue({
      ...group,
      id: '22222222-3333-4444-8555-666666666666',
      provider: {
        id: 'oidc-provider-partner',
        displayName: 'Verzeichnis Partner',
        external: true,
        enabled: true,
        groupMechanism: 'TOKEN',
      },
      sourcePath: null,
    })
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('button', { name: /kennung eingeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: 'Gruppe' }))
    await userEventInstance.type(
      await screen.findByLabelText(/gruppen-id/i),
      '22222222-3333-4444-8555-666666666666',
    )
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    // First the resolved origin, then the external provider's confirmation question.
    await answerConfirm(userEventInstance, /Recht an .Referat 50. erteilen\?/, 'Weiter')
    const question = 'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Verzeichnis Partner/,
    )
    await answerConfirm(userEventInstance, question, 'Abbrechen')

    expect(mockResolveSelectableGroup).toHaveBeenCalledWith('22222222-3333-4444-8555-666666666666')
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  /** Whatever cannot be resolved for this caller is not granted. */
  it('grants nothing when the typed id does not resolve for this caller', async () => {
    setManager()
    mockResolveSelectableGroup.mockRejectedValue(new Error('Gruppe nicht gefunden'))
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('button', { name: /kennung eingeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: 'Gruppe' }))
    await userEventInstance.type(
      await screen.findByLabelText(/gruppen-id/i),
      '22222222-3333-4444-8555-666666666666',
    )
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(await screen.findByText('Gruppe nicht gefunden')).toBeInTheDocument()
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  /** An identifier belongs to exactly one kind of recipient. */
  it('clears a typed id when the subject type changes', async () => {
    setManager()
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('button', { name: /kennung eingeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: 'Gruppe' }))
    await userEventInstance.type(
      await screen.findByLabelText(/gruppen-id/i),
      '22222222-3333-4444-8555-666666666666',
    )
    await userEventInstance.click(screen.getByRole('radio', { name: 'Person' }))

    expect(await screen.findByLabelText(/nutzer-id/i)).toHaveValue('')
  })

  it('explains every grantable role under the role of the form', async () => {
    setSystemAdmin()
    renderWithProviders(<AssetGrantsSection assetType="KNOWLEDGE_LIBRARY" assetId={library.id} />)
    const userEventInstance = userEvent.setup()
    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))

    expect(
      screen.getByText(/darf die bibliothek benutzen und ihren inhalt einsehen/i),
    ).toBeInTheDocument()
    const explanations: Array<[string, RegExp]> = [
      ['Bearbeiter', /darf zusätzlich dokumente ändern, hochladen und entfernen/i],
      ['Verwalter', /darf zusätzlich rechte vergeben und die sichtbarkeit der bibliothek ändern/i],
      ['Eigentümer', /darf zusätzlich die bibliothek löschen und das eigentum übertragen/i],
    ]
    for (const [label, explanation] of explanations) {
      await userEventInstance.click(screen.getByRole('combobox', { name: 'Rolle' }))
      await userEventInstance.click(await screen.findByRole('option', { name: label }))
      expect(await screen.findByText(explanation)).toBeInTheDocument()
    }
  })
})
