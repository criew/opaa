import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import LibraryDetailPage from './LibraryDetailPage'
import { useAuthStore } from '../stores/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useDocumentStore } from '../stores/documentStore'
import { useIndexingStore } from '../stores/indexingStore'
import type {
  IndexingStatusResponse,
  LibraryDocumentResponse,
  LibraryListResponse,
  LibraryResponse,
  LibraryUpdateRequest,
} from '../types/api'

let currentLibraryId = 'library-team'

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return {
    ...actual,
    useParams: () => ({ libraryId: currentLibraryId }),
    useNavigate: () => vi.fn(),
  }
})

const {
  mockGetLibrary,
  mockUpdateLibrary,
  mockDeleteLibrary,
  mockGetLibraryDocuments,
  mockTriggerIndexing,
  mockGetIndexingStatus,
} = vi.hoisted(() => ({
  mockGetLibrary: vi.fn(async (id: string) => useLibraryStore.getState().libraryDetails[id]),
  mockUpdateLibrary: vi.fn(async () => ({}) as LibraryResponse),
  mockDeleteLibrary: vi.fn(async () => undefined),
  mockGetLibraryDocuments: vi.fn(async () => [] as LibraryDocumentResponse[]),
  mockTriggerIndexing: vi.fn(
    async () =>
      ({
        status: 'RUNNING',
        documentCount: 0,
        totalDocuments: 0,
        documentsSkipped: 0,
        message: null,
        timestamp: '2026-03-01T10:00:00Z',
      }) as IndexingStatusResponse,
  ),
  mockGetIndexingStatus: vi.fn(
    async () =>
      ({
        status: 'IDLE',
        documentCount: 0,
        totalDocuments: 0,
        documentsSkipped: 0,
        message: null,
        timestamp: '2026-03-01T10:00:00Z',
      }) as IndexingStatusResponse,
  ),
}))

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getLibrary: mockGetLibrary,
    updateLibrary: mockUpdateLibrary,
    deleteLibrary: mockDeleteLibrary,
    getLibraryDocuments: mockGetLibraryDocuments,
    triggerIndexing: mockTriggerIndexing,
    getIndexingStatus: mockGetIndexingStatus,
  }
})

const managerLibrary: LibraryListResponse = {
  id: 'library-team',
  name: 'Rechtsquellen Soziales',
  description: 'SGB II, SGB XII',
  ownerType: 'GROUP',
  visibility: 'SHARED',
  listed: true,
  personal: false,
  myRole: 'MANAGER',
  sourceType: 'UPLOAD',
  documentCount: 431,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const viewerLibrary: LibraryListResponse = {
  id: 'library-readonly',
  name: 'Dienstanweisungen',
  description: 'Organisationsweit',
  ownerType: 'SYSTEM',
  visibility: 'ORGANIZATION',
  listed: true,
  personal: false,
  myRole: 'VIEWER',
  sourceType: 'UPLOAD',
  documentCount: 87,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

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

function detailsOf(
  library: LibraryListResponse,
  overrides: Partial<LibraryResponse> = {},
): LibraryResponse {
  return { ...library, ownerId: null, ...overrides }
}

function setLibraryState(library: LibraryListResponse, details: LibraryResponse) {
  currentLibraryId = library.id
  useLibraryStore.setState({
    libraries: [library],
    libraryDetails: { [library.id]: details },
    isLoading: false,
    error: null,
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

describe('LibraryDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useDocumentStore.getState().reset()
    useIndexingStore.getState().stopPolling()
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
    useIndexingStore.getState().stopPolling()
  })

  it('shows neither edit nor delete controls for a VIEWER', async () => {
    setLibraryState(viewerLibrary, detailsOf(viewerLibrary, { documentCount: 87 }))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText(/87 Dokumente/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /speichern/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
  })

  it('offers editing but not deleting for a MANAGER', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
  })

  it('offers "Rechte verwalten" for a MANAGER but hides it for a VIEWER', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByRole('button', { name: /rechte verwalten/i })).toBeInTheDocument()
    unmount()

    setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByText(/87 Dokumente/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /rechte verwalten/i })).not.toBeInTheDocument()
  })

  it('never offers deleting or sharing the personal library, even for its OWNER', async () => {
    setLibraryState(personalLibrary, detailsOf(personalLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /rechte verwalten/i })).not.toBeInTheDocument()
  })

  it('does not offer ORGANIZATION visibility for the personal library', async () => {
    setLibraryState(personalLibrary, detailsOf(personalLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('combobox', { name: /sichtbarkeit/i }))

    expect(screen.getByRole('option', { name: 'privat' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'geteilt' })).toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'organisationsweit' })).not.toBeInTheDocument()
  })

  it('lets a system admin edit and delete a library without an own grant', async () => {
    setSystemAdmin()
    setLibraryState(
      { ...viewerLibrary, ownerType: 'GROUP' },
      detailsOf(viewerLibrary, { ownerType: 'GROUP' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /bibliothek löschen/i })).toBeInTheDocument()
    expect(screen.getByText('administrativ')).toBeInTheDocument()
  })

  // Two clear+type sequences plus a MUI Select interaction genuinely take longer than the
  // default 5s under full-suite CPU contention (this mirrors the equivalent, previously
  // passing test in the pre-#481 LibraryManagementPage.test.tsx).
  it('saves changed name, description and visibility together', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    const nameField = await screen.findByLabelText(/name der bibliothek/i)
    await user.clear(nameField)
    await user.type(nameField, 'Rechtsquellen Soziales (neu)')
    const descriptionField = screen.getByLabelText(/beschreibung/i)
    await user.clear(descriptionField)
    await user.type(descriptionField, 'Aktualisierte Beschreibung')
    await user.click(screen.getByRole('combobox', { name: /sichtbarkeit/i }))
    await user.click(await screen.findByRole('option', { name: 'privat' }))
    await user.click(screen.getByRole('button', { name: /speichern/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-team', {
        name: 'Rechtsquellen Soziales (neu)',
        description: 'Aktualisierte Beschreibung',
        visibility: 'PRIVATE',
        listed: true,
        sourceInsecureSsl: null,
      } satisfies LibraryUpdateRequest)
    })
  }, 15000)

  it('deletes a library with an OWNER role after confirmation', async () => {
    setLibraryState(
      { ...managerLibrary, myRole: 'OWNER' },
      detailsOf({ ...managerLibrary, myRole: 'OWNER' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    await user.click(await screen.findByRole('button', { name: /bibliothek löschen/i }))

    await waitFor(() => {
      expect(mockDeleteLibrary).toHaveBeenCalledWith('library-team')
    })
  })

  it('warns that a connector library delete also removes its indexed documents', async () => {
    // #479, ADR-0018 Entscheidung 5: deleting a connector library takes its bestand with it.
    const ownerLibrary = { ...managerLibrary, myRole: 'OWNER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)

    await user.click(await screen.findByRole('button', { name: /bibliothek löschen/i }))

    await waitFor(() => {
      expect(mockDeleteLibrary).toHaveBeenCalledWith('library-team')
    })
    expect(confirmSpy).toHaveBeenCalledWith(expect.stringMatching(/indizierten dokumente/i))
  })

  it('shows the upload zone and document list for an UPLOAD library', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce([
      {
        id: 'doc-1',
        fileName: 'dienstanweisung.pdf',
        contentType: 'application/pdf',
        fileSize: 1024,
        status: 'INDEXED',
        sourceType: 'UPLOAD',
        chunkCount: 3,
        indexedAt: '2026-03-01T10:00:00Z',
        uploadedByUserId: 'u1',
      },
    ])
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText('dienstanweisung.pdf')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /dateien hochladen/i })).toBeInTheDocument()
    expect(screen.queryByText(/quellkonfiguration/i)).not.toBeInTheDocument()
  })

  it('shows the configuration and an indexing trigger for a connector library', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText(/quellkonfiguration/i)).toBeInTheDocument()
    expect(screen.getByText('/data/dokumente')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /dateien hochladen/i })).not.toBeInTheDocument()
    const triggerButton = screen.getByRole('button', { name: /jetzt indizieren/i })
    expect(triggerButton).toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(triggerButton)

    await waitFor(() => {
      expect(mockTriggerIndexing).toHaveBeenCalledWith('library-team')
    })
  })

  it('hides the indexing trigger for a VIEWER on a connector library', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText(/quellkonfiguration/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /jetzt indizieren/i })).not.toBeInTheDocument()
  })
})
