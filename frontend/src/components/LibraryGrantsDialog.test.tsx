import { fireEvent, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import LibraryGrantsDialog from './LibraryGrantsDialog'
import { useAuthStore } from '../stores/authStore'
import { useGrantStore } from '../stores/grantStore'
import type {
  AssetGrantRequest,
  AssetGrantResponse,
  GroupListResponse,
  UserInfo,
} from '../types/api'

const {
  mockGetLibraryGrants,
  mockUpsertLibraryGrant,
  mockRevokeLibraryGrant,
  mockGetGroups,
  mockGetMyGroups,
  mockGetUsers,
} = vi.hoisted(() => ({
  mockGetLibraryGrants: vi.fn(async (libraryId: string) => {
    return useGrantStore.getState().grantsByLibrary[libraryId] ?? []
  }),
  mockUpsertLibraryGrant: vi.fn(),
  mockRevokeLibraryGrant: vi.fn(async () => undefined),
  mockGetGroups: vi.fn(async () => [] as GroupListResponse[]),
  mockGetMyGroups: vi.fn(async () => [] as GroupListResponse[]),
  mockGetUsers: vi.fn(async () => [] as UserInfo[]),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getLibraryGrants: mockGetLibraryGrants,
    upsertLibraryGrant: mockUpsertLibraryGrant,
    revokeLibraryGrant: mockRevokeLibraryGrant,
    getGroups: mockGetGroups,
    getMyGroups: mockGetMyGroups,
    getUsers: mockGetUsers,
  }
})

const library = { id: 'library-team', name: 'Rechtsquellen Soziales' }

const group: GroupListResponse = {
  id: 'group-referat-50',
  name: 'Referat 50',
  description: null,
  kind: 'ORG_UNIT',
  externalId: 'directory-guid',
  parentGroupId: null,
  memberCount: 3,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const user: UserInfo = {
  id: 'user-alice',
  email: 'alice@opaa.local',
  displayName: 'Alice',
  systemRole: 'USER',
}

function setGrants(libraryId: string, grants: AssetGrantResponse[]) {
  useGrantStore.setState({
    grantsByLibrary: { [libraryId]: grants },
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

describe('LibraryGrantsDialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetGroups.mockResolvedValue([group])
    mockGetMyGroups.mockResolvedValue([group])
    mockGetUsers.mockResolvedValue([user])
    useGrantStore.setState({ grantsByLibrary: {}, isLoading: false, error: null })
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
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'admin-1',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)

    expect(await screen.findByText('Alice')).toBeInTheDocument()
    expect(screen.queryByText('user-alice')).not.toBeInTheDocument()
  })

  it('marks an expired grant as expired instead of hiding it', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-expired',
        subjectType: 'USER',
        subjectId: 'user-alice',
        role: 'VIEWER',
        expiresAt: '2020-01-01T00:00:00.000Z',
        grantedByUserId: 'admin-1',
        createdAt: '2019-01-01T10:00:00Z',
        updatedAt: '2019-01-01T10:00:00Z',
      },
    ])
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)

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
        role: 'VIEWER',
        expiresAt: '2099-12-31T12:00:00.000Z',
        grantedByUserId: 'admin-1',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)

    expect(await screen.findByText(/bis 31\.12\.2099/)).toBeInTheDocument()
  })

  it('grants a person VIEWER access and shows it without a reload', async () => {
    setSystemAdmin()
    mockUpsertLibraryGrant.mockImplementationOnce(
      async (_libraryId: string, request: AssetGrantRequest) => {
        const created: AssetGrantResponse = {
          id: 'grant-new',
          subjectType: request.subjectType,
          subjectId: request.subjectId,
          role: request.role,
          expiresAt: request.expiresAt ?? null,
          grantedByUserId: 'admin-1',
          createdAt: '2026-03-05T10:00:00Z',
          updatedAt: '2026-03-05T10:00:00Z',
        }
        return created
      },
    )
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByLabelText(/^person auswählen$/i))
    await userEventInstance.click(await screen.findByRole('option', { name: /Alice/ }))
    await userEventInstance.click(
      screen.getAllByRole('button', { name: /^freigeben$/i })[
        screen.getAllByRole('button', { name: /^freigeben$/i }).length - 1
      ],
    )

    await waitFor(() => {
      expect(mockUpsertLibraryGrant).toHaveBeenCalledWith(library.id, {
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
    mockUpsertLibraryGrant.mockResolvedValueOnce({
      id: 'grant-group',
      subjectType: 'GROUP',
      subjectId: group.id,
      role: 'VIEWER',
      expiresAt: null,
      grantedByUserId: 'admin-1',
      createdAt: '2026-03-05T10:00:00Z',
      updatedAt: '2026-03-05T10:00:00Z',
    } satisfies AssetGrantResponse)
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByRole('radio', { name: /gruppe/i }))
    await userEventInstance.click(await screen.findByLabelText(/^gruppe auswählen$/i))
    await userEventInstance.click(await screen.findByRole('option', { name: 'Referat 50' }))
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    await waitFor(() => {
      expect(mockUpsertLibraryGrant).toHaveBeenCalledWith(library.id, {
        subjectType: 'GROUP',
        subjectId: group.id,
        role: 'VIEWER',
        expiresAt: null,
      })
    })
  })

  it('rejects an expiry date in the past before calling the API', async () => {
    setSystemAdmin()
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByLabelText(/^person auswählen$/i))
    await userEventInstance.click(await screen.findByRole('option', { name: /Alice/ }))
    const dateField = screen.getByLabelText(/befristung/i)
    fireEvent.change(dateField, { target: { value: '2020-01-01' } })
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(
      await screen.findByText(/ablaufdatum darf nicht in der vergangenheit liegen/i),
    ).toBeInTheDocument()
    expect(mockUpsertLibraryGrant).not.toHaveBeenCalled()
  })

  it('changes the role of an existing grant without creating a second entry', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-1',
        subjectType: 'USER',
        subjectId: 'user-alice',
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'admin-1',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    mockUpsertLibraryGrant.mockResolvedValueOnce({
      id: 'grant-1',
      subjectType: 'USER',
      subjectId: 'user-alice',
      role: 'EDITOR',
      expiresAt: null,
      grantedByUserId: 'admin-1',
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-06T10:00:00Z',
    } satisfies AssetGrantResponse)
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('combobox', { name: /^rolle$/i }))
    await userEventInstance.click(await screen.findByRole('option', { name: 'Bearbeiter' }))

    await waitFor(() => {
      expect(mockUpsertLibraryGrant).toHaveBeenCalledWith(library.id, {
        subjectType: 'USER',
        subjectId: 'user-alice',
        role: 'EDITOR',
        expiresAt: null,
      })
    })
    expect(useGrantStore.getState().grantsByLibrary[library.id]).toHaveLength(1)
  })

  it('revokes a grant after confirmation and removes it from the list', async () => {
    setSystemAdmin()
    setGrants(library.id, [
      {
        id: 'grant-1',
        subjectType: 'USER',
        subjectId: 'user-alice',
        role: 'VIEWER',
        expiresAt: null,
        grantedByUserId: 'admin-1',
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /entziehen/i }))

    await waitFor(() => {
      expect(mockRevokeLibraryGrant).toHaveBeenCalledWith(library.id, 'grant-1')
    })
    expect(screen.queryByText('Alice')).not.toBeInTheDocument()
  })

  it('shows a German 403 message instead of failing silently', async () => {
    setSystemAdmin()
    mockUpsertLibraryGrant.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Bibliothek'))
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))
    await userEventInstance.click(await screen.findByLabelText(/^person auswählen$/i))
    await userEventInstance.click(await screen.findByRole('option', { name: /Alice/ }))
    const submitButtons = screen.getAllByRole('button', { name: /^freigeben$/i })
    await userEventInstance.click(submitButtons[submitButtons.length - 1])

    expect(await screen.findByText('Kein Zugriff auf diese Bibliothek')).toBeInTheDocument()
  })

  it('falls back to a free-text user id field when the user list is not available', async () => {
    setManager()
    mockGetUsers.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Ressource'))
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)
    const userEventInstance = userEvent.setup()

    await userEventInstance.click(await screen.findByRole('button', { name: /freigeben/i }))

    expect(await screen.findByLabelText(/nutzer-id/i)).toBeInTheDocument()
  })

  it('explains every grantable role', async () => {
    setSystemAdmin()
    renderWithProviders(<LibraryGrantsDialog open library={library} onClose={vi.fn()} />)

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
