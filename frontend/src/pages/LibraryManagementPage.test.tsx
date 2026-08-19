import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import LibraryManagementPage from './LibraryManagementPage'
import { useLibraryStore } from '../stores/libraryStore'
import type {
  GroupListResponse,
  LibraryListResponse,
  LibraryRequest,
  LibraryResponse,
} from '../types/api'

const mockNavigate = vi.fn()

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useNavigate: () => mockNavigate,
  }
})

const { mockCreateLibrary, mockGetMyGroups } = vi.hoisted(() => ({
  mockCreateLibrary: vi.fn(async (request: LibraryRequest) => {
    const created: LibraryListResponse = {
      id: `library-${request.name}`,
      name: request.name,
      description: request.description ?? null,
      ownerType: request.ownerType ?? 'USER',
      visibility: request.visibility ?? 'PRIVATE',
      listed: request.listed ?? false,
      personal: false,
      myRole: 'OWNER',
      sourceType: request.sourceType,
      documentCount: 0,
      createdAt: '2026-03-01T10:00:00Z',
      updatedAt: '2026-03-01T10:00:00Z',
    }
    // Simulates the real backend response influencing the next getLibraries() call, so the AC
    // "erscheint ohne Neuladen in der Liste" is actually exercised instead of assumed.
    useLibraryStore.setState((state) => ({ libraries: [...state.libraries, created] }))
    return { ...created, ownerId: 'mock-user-id', documentCount: 0 } as LibraryResponse
  }),
  mockGetMyGroups: vi.fn(async () => [] as GroupListResponse[]),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getMyGroups: mockGetMyGroups,
    getLibraries: vi.fn(async () => useLibraryStore.getState().libraries),
    createLibrary: mockCreateLibrary,
  }
})

const personalLibrary: LibraryListResponse = {
  id: 'library-personal',
  name: 'Meine Dokumente',
  description: 'Private Dokumente',
  ownerType: 'USER',
  visibility: 'PRIVATE',
  listed: false,
  personal: true,
  myRole: 'OWNER',
  sourceType: 'UPLOAD',
  documentCount: 12,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const managerLibrary: LibraryListResponse = {
  id: 'library-team',
  name: 'Rechtsquellen Soziales',
  description: 'SGB II, SGB XII',
  ownerType: 'GROUP',
  visibility: 'SHARED',
  listed: true,
  personal: false,
  myRole: 'MANAGER',
  sourceType: 'FILESYSTEM',
  documentCount: 431,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const viewerLibrary: LibraryListResponse = {
  id: 'library-readonly',
  name: 'Dienstanweisungen',
  description: 'Organisationsweit',
  ownerType: 'GROUP',
  visibility: 'ORGANIZATION',
  listed: true,
  personal: false,
  myRole: 'VIEWER',
  sourceType: 'UPLOAD',
  documentCount: 87,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

function setLibraryState(libraries: LibraryListResponse[]) {
  useLibraryStore.setState({
    libraries,
    libraryDetails: {},
    isLoading: false,
    error: null,
  })
}

describe('LibraryManagementPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('lists libraries with the personal library first and marked as such', async () => {
    setLibraryState([managerLibrary, personalLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    const items = await screen.findAllByText(/persönlich|Rechtsquellen Soziales/)
    expect(items[0]).toHaveTextContent('persönlich')
    expect(screen.getByText('Rechtsquellen Soziales')).toBeInTheDocument()
  })

  it('shows the document count and source type per library without a detail round trip', async () => {
    setLibraryState([managerLibrary, viewerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText(/431 dokumente/i)).toBeInTheDocument()
    expect(await screen.findByText(/87 dokumente/i)).toBeInTheDocument()
    expect(screen.getByText('Dateisystem')).toBeInTheDocument()
    expect(screen.getByText('Hochgeladen')).toBeInTheDocument()
  })

  it('shows an empty state when there are no libraries', async () => {
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    expect(await screen.findByText(/noch keine bibliotheken/i)).toBeInTheDocument()
  })

  it('renders each library row as a real link to its detail page', async () => {
    // #506 review, finding 6: a navigate()-triggering button offers none of a real link's
    // affordances (open in new tab, middle-click, hover preview) - the row must carry a genuine
    // href instead.
    setLibraryState([managerLibrary])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })

    const link = await screen.findByRole('link', { name: /Rechtsquellen Soziales/ })
    expect(link).toHaveAttribute('href', '/libraries/library-team')
  })

  it('creates a new library owned by the caller and navigates to its detail page', async () => {
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Frisch angelegte Bibliothek')
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateLibrary).toHaveBeenCalledWith({
        name: 'Frisch angelegte Bibliothek',
        description: undefined,
        ownerType: 'USER',
        ownerId: undefined,
        sourceType: 'UPLOAD',
        sourceInsecureSsl: false,
      })
    })
    expect(mockNavigate).toHaveBeenCalledWith('/libraries/library-Frisch angelegte Bibliothek')
  })

  it('creates a group-owned library, offering only the groups returned for the user', async () => {
    mockGetMyGroups.mockResolvedValueOnce([
      {
        id: 'group-referat-50',
        name: 'Referat 50',
        description: null,
        kind: 'ORG_UNIT',
        externalId: 'directory-guid',
        parentGroupId: null,
        memberCount: 3,
        createdAt: '2026-03-01T10:00:00Z',
        updatedAt: '2026-03-01T10:00:00Z',
      },
    ])
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))
    await user.type(screen.getByLabelText(/^name/i), 'Team-Bibliothek')
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))
    await user.click(await screen.findByLabelText(/^gruppe$/i))
    await user.click(await screen.findByRole('option', { name: 'Referat 50' }))
    await user.click(screen.getByRole('button', { name: /^erstellen$/i }))

    await waitFor(() => {
      expect(mockCreateLibrary).toHaveBeenCalledWith({
        name: 'Team-Bibliothek',
        description: undefined,
        ownerType: 'GROUP',
        ownerId: 'group-referat-50',
        sourceType: 'UPLOAD',
        sourceInsecureSsl: false,
      })
    })
  })

  it('shows a visible hint instead of a silent empty list when the caller has no groups', async () => {
    mockGetMyGroups.mockResolvedValueOnce([])
    setLibraryState([])
    renderWithProviders(<LibraryManagementPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: /neue bibliothek/i }))
    await user.click(screen.getByRole('radio', { name: /eine gruppe/i }))

    expect(await screen.findByText(/keiner gruppe mitglied/i)).toBeInTheDocument()
  })
})
