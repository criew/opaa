import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '../../test/test-utils'
import AssetGrantsDialog from './AssetGrantsDialog'
import { useAuthStore } from '../../stores/authStore'
import { assetKey, useGrantStore } from '../../stores/grantStore'
import type {
  AssetGrantRequest,
  AssetGrantResponse,
  SelectableGroupResponse,
  UserSummary,
} from '../../types/api'

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
    smallGroup: false,
    activeMemberCount: 1,
    members: [{ userId: 'user-anna', displayName: 'Anna Bauer' }],
    responsible: [] as string[],
  })),
}))

vi.mock('../../services/api', async () => {
  const actual = await vi.importActual<typeof import('../../services/api')>('../../services/api')
  return {
    ...actual,
    getAssetGrants: mockGetAssetGrants,
    upsertAssetGrant: mockUpsertAssetGrant,
    revokeAssetGrant: mockRevokeAssetGrant,
    searchSelectableGroups: mockSearchSelectableGroups,
    resolveSelectableGroup: mockResolveSelectableGroup,
    getUserSummaries: mockGetUserSummaries,
    getGrantedGroupMembers: mockGetGrantedGroupMembers,
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

describe('AssetGrantsDialog', () => {
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.queryByText('user-alice')).not.toBeInTheDocument()
  })

  it('shows what only the asset type has in the slot the caller passes, and names the type', async () => {
    setSystemAdmin()
    setGrants(library.id, [])
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
        typeSection={<p>Typeigener Bereich</p>}
      />,
    )

    expect(await screen.findByText('Typeigener Bereich')).toBeInTheDocument()
    expect(
      screen.getByText('Es sind noch keine Freigaben für diese Bibliothek erteilt.'),
    ).toBeInTheDocument()
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.getByText('abgelaufen')).toBeInTheDocument()
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    // #778 review, finding 4: the picker no longer preloads the whole organization - a query
    // (min. 2 characters) has to be typed before GET /v1/users is even attempted.
    await userEventInstance.type(await screen.findByLabelText(/^person suchen$/i), 'al')
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: /gruppe/i }))
    await userEventInstance.type(await screen.findByLabelText(/^gruppe suchen$/i), 'Referat')
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

  it('rejects an expiry date in the past before calling the API', async () => {
    setSystemAdmin()
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.type(await screen.findByLabelText(/^person suchen$/i), 'al')
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    // #423 code review, nit 5: the row-level role select's accessible name now names its subject
    // ("Rolle für Alice"), not the shared "Rolle" every row used to carry.
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /entziehen/i }))
    await answerConfirm(userEventInstance, 'Freigabe für "Alice" entziehen?', 'Entziehen')

    await waitFor(() => {
      expect(mockRevokeAssetGrant).toHaveBeenCalledWith('KNOWLEDGE_LIBRARY', library.id, 'grant-1')
    })
    expect(screen.queryByText('Alice')).not.toBeInTheDocument()
  })

  it('shows a German 403 message instead of failing silently', async () => {
    setSystemAdmin()
    mockUpsertAssetGrant.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Bibliothek'))
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.type(await screen.findByLabelText(/^person suchen$/i), 'al')
    await userEventInstance.click(await screen.findByRole('option', { name: /Alice/ }))
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(await screen.findByText('Kein Zugriff auf diese Bibliothek')).toBeInTheDocument()
  })

  it('names a failed person search and offers the id field as the way out', async () => {
    // #777: GET /v1/users is reachable for every authenticated caller now, not just SYSTEM_ADMIN.
    // #1820: Der Rueckfall auf die Kennung wird ausdruecklich gewaehlt, statt sich selbst
    // einzuschalten - ein gescheiterter Suchlauf sagt das im Feld, und der Weg daneben ist
    // derselbe wie fuer eine Gruppe.
    setManager()
    mockGetUserSummaries.mockRejectedValueOnce(new Error('Netzwerkfehler'))
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.type(await screen.findByLabelText(/^person suchen$/i), 'al')

    expect(await screen.findByText(/Netzwerkfehler/)).toBeInTheDocument()
    await userEventInstance.click(screen.getByRole('button', { name: /nutzer-id eingeben/i }))
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(
      await screen.findByRole('button', { name: /nutzer-id eingeben/i }),
    )
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(
      await screen.findByRole('button', { name: /nutzer-id eingeben/i }),
    )
    await userEventInstance.type(await screen.findByLabelText(/nutzer-id/i), 'anna.beispiel')
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(await screen.findByText(/nutzer-id muss eine gültige uuid sein/i)).toBeInTheDocument()
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  it('shows resolved subject and granter names for a MANAGER without a system role', async () => {
    // #423 code review, finding 1 (confirmed): the fix is that these names come from the grant
    // response itself (subjectDisplayName/grantedByDisplayName), never looked up client-side.
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.getByText('Referat 50')).toBeInTheDocument()
    expect(screen.getAllByText(/Rolle vergeben von Manager/i)).toHaveLength(2)
    expect(screen.queryByText('user-alice')).not.toBeInTheDocument()
  })

  it('#1052: dates the granter line by updatedAt, never by createdAt', async () => {
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
        role: 'OWNER',
        expiresAt: null,
        grantedByUserId: 'admin-2',
        grantedByDisplayName: 'Admin B',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-08-20T10:00:00Z',
      },
    ])
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText(/Rolle vergeben von Admin B/i)).toHaveTextContent('20.8.2026')
    expect(screen.queryByText(/1\.3\.2026/)).not.toBeInTheDocument()
  })

  it('#777: offers the searchable user picker for a MANAGER without a system role', async () => {
    // Before this fix, the user picker was only ever attempted for SYSTEM_ADMIN callers (GET
    // /v1/admin/users) - every other MANAGER went straight to the free-text UUID field, even
    // though the user list loaded successfully via GET /v1/users.
    setManager()
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))

    const personField = await screen.findByLabelText(/^person suchen$/i)
    expect(screen.queryByLabelText(/^nutzer-id$/i)).not.toBeInTheDocument()

    // #778 review, finding 4: no preload on mount - the search only runs once queried.
    await userEventInstance.type(personField, 'al')
    await waitFor(() => expect(mockGetUserSummaries).toHaveBeenCalledWith('al'))
    expect(await screen.findByRole('option', { name: /Alice/ })).toBeInTheDocument()
  })

  it('offers a manual group id as an alternative to the search', async () => {
    mockResolveSelectableGroup.mockResolvedValue({
      ...group,
      id: '22222222-3333-4444-8555-666666666666',
    })
    // #1820: Die Eingabe per Kennung unterliegt derselben Durchsetzung wie die Suche - der Dienst
    // antwortet auch dort mit nicht gefunden, wenn die Gruppe nicht freigegeben ist.
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: /gruppe/i }))
    await userEventInstance.click(
      await screen.findByRole('button', { name: /gruppen-id eingeben/i }),
    )
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /entziehen/i }))

    // Das Overlay liegt über dem Freigabe-Dialog; die Frage macht es eindeutig.
    const question = 'Freigabe für "Manager" entziehen?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /eigene freigabe/i,
    )
    await answerConfirm(userEventInstance, question, 'Abbrechen')

    expect(mockRevokeAssetGrant).not.toHaveBeenCalled()
  })

  /** ADR-0036, Entscheidung 2: Das Erteilen an eine externe Gruppe verlangt eine Zwischenfrage. */
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: /gruppe/i }))
    await userEventInstance.type(await screen.findByLabelText(/^gruppe suchen$/i), 'Referat')
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

  /** ADR-0036, Entscheidung 9: „23 bei Erteilung, heute 41" - eine Zeile, kein Vorgang. */
  it('shows the growth signal of a group grant', async () => {
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-group',
        subjectType: 'GROUP',
        subjectId: group.id,
        subjectDisplayName: 'Referat 50',
        protectedGroup: false,
        memberCountAtGrant: 23,
        memberCountNow: 41,
        smallGroup: false,
        emptyGroup: false,
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText(/23 bei Erteilung, heute 41/)).toBeInTheDocument()
  })

  /**
   * #1880, ADR-0036 Entscheidung 9: Wer der Gruppe hier ein Recht eingeräumt hat, sieht, an wen —
   * und die Liste entsteht erst auf ausdrücklichen Wunsch, nicht beim Öffnen des Dialogs.
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
        memberCountAtGrant: 23,
        memberCountNow: 41,
        smallGroup: false,
        emptyGroup: false,
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const trigger = await screen.findByRole('button', {
      name: 'Mitglieder der Gruppe „Referat 50“ anzeigen',
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

  it('withholds both figures of a small group and says so', async () => {
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-small',
        subjectType: 'GROUP',
        subjectId: group.id,
        subjectDisplayName: 'Referat 50',
        protectedGroup: false,
        memberCountAtGrant: null,
        memberCountNow: null,
        smallGroup: true,
        emptyGroup: false,
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText(/kleine Gruppe/)).toBeInTheDocument()
  })

  /** ADR-0036, Entscheidung 9: namenlose Zeile, kein Signal - und trotzdem entziehbar. */
  it('shows a protected group as a nameless row without any figure', async () => {
    setManager()
    setGrants(library.id, [
      {
        id: 'grant-protected',
        subjectType: 'GROUP',
        subjectId: 'group-personalrat',
        subjectDisplayName: null,
        protectedGroup: true,
        memberCountAtGrant: null,
        memberCountNow: null,
        smallGroup: null,
        emptyGroup: null,
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'manager-1',
        grantedByDisplayName: 'Manager',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

    expect(await screen.findByText('Geschützte Gruppe')).toBeInTheDocument()
    expect(screen.queryByText('group-personalrat')).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: /Freigabe für Geschützte Gruppe entziehen/ }),
    ).toBeInTheDocument()
  })

  /**
   * ADR-0036, Entscheidung 2 (#1820): Der Kennungsweg zeigt weder Herkunft noch Symbol - deshalb
   * loest er die Gruppe vor dem Erteilen auf und stellt dieselbe Zwischenfrage. Ohne das waere die
   * Zwischenfrage fuer externe Anbieter durch Eintippen der Kennung umgehbar.
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
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: /gruppe/i }))
    await userEventInstance.click(
      await screen.findByRole('button', { name: /gruppen-id eingeben/i }),
    )
    await userEventInstance.type(
      await screen.findByLabelText(/gruppen-id/i),
      '22222222-3333-4444-8555-666666666666',
    )
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    // Erst die aufgeloeste Herkunft, dann die Zwischenfrage des externen Anbieters.
    await answerConfirm(userEventInstance, /Recht an .Referat 50. erteilen\?/, 'Weiter')
    const question = 'Sie geben für eine Gruppe eines externen Anbieters frei — fortfahren?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /Verzeichnis Partner/,
    )
    await answerConfirm(userEventInstance, question, 'Abbrechen')

    expect(mockResolveSelectableGroup).toHaveBeenCalledWith('22222222-3333-4444-8555-666666666666')
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  /** Was sich fuer diesen Aufrufer nicht aufloesen laesst, wird nicht erteilt. */
  it('grants nothing when the typed id does not resolve for this caller', async () => {
    setManager()
    mockResolveSelectableGroup.mockRejectedValue(new Error('Gruppe nicht gefunden'))
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: /gruppe/i }))
    await userEventInstance.click(
      await screen.findByRole('button', { name: /gruppen-id eingeben/i }),
    )
    await userEventInstance.type(
      await screen.findByLabelText(/gruppen-id/i),
      '22222222-3333-4444-8555-666666666666',
    )
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(await screen.findByText('Gruppe nicht gefunden')).toBeInTheDocument()
    expect(mockUpsertAssetGrant).not.toHaveBeenCalled()
  })

  /** Eine Kennung gehoert zu genau einer Art von Empfaenger. */
  it('clears a typed id when the subject type changes', async () => {
    setManager()
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: /gruppe/i }))
    await userEventInstance.click(
      await screen.findByRole('button', { name: /gruppen-id eingeben/i }),
    )
    await userEventInstance.type(
      await screen.findByLabelText(/gruppen-id/i),
      '22222222-3333-4444-8555-666666666666',
    )
    await userEventInstance.click(screen.getByRole('radio', { name: 'Person' }))

    expect(await screen.findByLabelText(/nutzer-id/i)).toHaveValue('')
  })

  it('explains every grantable role', async () => {
    setSystemAdmin()
    renderWithProviders(
      <AssetGrantsDialog
        open
        assetType="KNOWLEDGE_LIBRARY"
        assetId={library.id}
        assetName={library.name}
        onClose={vi.fn()}
      />,
    )

    expect(
      await screen.findByText(/darf die bibliothek benutzen und ihren inhalt einsehen/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/darf zusätzlich dokumente ändern, hochladen und entfernen/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(
        /darf zusätzlich rechte vergeben und die sichtbarkeit der bibliothek ändern/i,
      ),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/darf zusätzlich die bibliothek löschen und das eigentum übertragen/i),
    ).toBeInTheDocument()
  })
})
