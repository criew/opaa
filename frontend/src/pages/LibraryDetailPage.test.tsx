import { AxiosError } from 'axios'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import LibraryDetailPage from './LibraryDetailPage'
import { useAuthStore } from '../stores/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useDocumentStore } from '../stores/documentStore'
import { IDLE_RUN_STATE, useIndexingStore } from '../stores/indexingStore'
import type {
  IndexingRunListResponse,
  IndexingStatusResponse,
  LibraryDocumentPageResponse,
  LibraryDocumentResponse,
  LibraryListResponse,
  LibraryResponse,
  LibraryUpdateRequest,
} from '../types/api'
import type { OpenDocumentContentResult } from '../utils/documentContent'

function pageOf(
  items: LibraryDocumentResponse[],
  overrides: Partial<LibraryDocumentPageResponse> = {},
): LibraryDocumentPageResponse {
  return {
    items,
    page: 0,
    size: 20,
    totalElements: items.length,
    folders: [],
    breadcrumb: [],
    ...overrides,
  }
}

// #822 review, finding 2: mirrors documentStore.test.ts's own notFoundError - normalizeError
// (services/api.ts) attaches the original AxiosError as `cause`, and only an actual 404 status may
// trigger the "unknown folder" root fallback, not merely an Error whose message happens to read
// "nicht gefunden".
function notFoundError(message = 'Ordner nicht gefunden'): Error {
  const axiosError = new AxiosError('Request failed', 'ERR_BAD_REQUEST', undefined, undefined, {
    status: 404,
    statusText: '',
    headers: {},
    config: {} as never,
    data: { error: message },
  })
  return new Error(message, { cause: axiosError })
}

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
  mockUploadDocument,
  mockDeleteLibraryDocument,
  mockCreateLibraryFolder,
  mockRenameLibraryFolder,
  mockDeleteLibraryFolder,
  mockTriggerIndexing,
  mockGetIndexingStatus,
} = vi.hoisted(() => ({
  mockGetLibrary: vi.fn(async (id: string) => useLibraryStore.getState().libraryDetails[id]),
  mockUpdateLibrary: vi.fn(async () => ({}) as LibraryResponse),
  mockDeleteLibrary: vi.fn(async () => undefined),
  mockGetLibraryDocuments: vi.fn(async () => pageOf([])),
  mockUploadDocument: vi.fn(),
  mockDeleteLibraryDocument: vi.fn(async () => undefined),
  mockCreateLibraryFolder: vi.fn(),
  mockRenameLibraryFolder: vi.fn(),
  mockDeleteLibraryFolder: vi.fn(async () => undefined),
  mockTriggerIndexing: vi.fn(
    async () =>
      ({
        status: 'RUNNING',
        documentCount: 0,
        totalDocuments: 0,
        documentsSkipped: 0,
        documentsFailed: 0,
        documentsIndexedTotal: 0,
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
        documentsFailed: 0,
        documentsIndexedTotal: 0,
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
    uploadDocument: mockUploadDocument,
    deleteLibraryDocument: mockDeleteLibraryDocument,
    createLibraryFolder: mockCreateLibraryFolder,
    renameLibraryFolder: mockRenameLibraryFolder,
    deleteLibraryFolder: mockDeleteLibraryFolder,
    triggerIndexing: mockTriggerIndexing,
    getIndexingStatus: mockGetIndexingStatus,
  }
})

// #738/#780: the "Original öffnen" action delegates to this shared module (see its own tests for
// the blob-fetch/preview/download behaviour) - mocked here so this file only has to verify which
// target LibraryDetailPage picks per document, and which UI reacts to a text-preview/download
// result, not how opening/downloading itself works.
const { mockOpenDocumentContent } = vi.hoisted(() => ({
  mockOpenDocumentContent: vi.fn<() => Promise<OpenDocumentContentResult>>(async () => ({
    kind: 'blob-preview',
  })),
}))

vi.mock('../utils/documentContent', () => ({
  openDocumentContent: mockOpenDocumentContent,
}))

const managerLibrary: LibraryListResponse = {
  id: 'library-team',
  name: 'Rechtsquellen Soziales',
  description: 'SGB II, SGB XII',
  ownerType: 'GROUP',
  visibility: 'SHARED',
  listed: true,
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
  ownerType: 'GROUP',
  visibility: 'ORGANIZATION',
  listed: true,
  myRole: 'VIEWER',
  sourceType: 'UPLOAD',
  documentCount: 87,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const personalLibrary: LibraryListResponse = {
  id: 'library-mine',
  name: 'Meine Dokumente',
  description: 'Private Dokumente',
  ownerType: 'USER',
  visibility: 'PRIVATE',
  listed: false,
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
  return { ...library, ownerId: 'mock-owner-id', ...overrides }
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
    useIndexingStore.getState().stopPolling('library-team')
    useIndexingStore.getState().stopPolling('library-readonly')
    useIndexingStore.getState().stopPolling('library-mine')
  })

  afterEach(() => {
    useAuthStore.setState({ user: null })
    useIndexingStore.getState().stopPolling('library-team')
    useIndexingStore.getState().stopPolling('library-readonly')
    useIndexingStore.getState().stopPolling('library-mine')
  })

  it('shows neither edit nor delete controls for a VIEWER', async () => {
    setLibraryState(viewerLibrary, detailsOf(viewerLibrary, { documentCount: 87 }))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText(/87 Dokumente/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /speichern/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
  })

  it('shows storage quota usage for a MANAGER but not for a VIEWER whose response omits it', async () => {
    // #119: storageQuotaBytes/storageUsedBytes are only ever sent to a caller with at least
    // MANAGER (see KnowledgeLibraryService#toLibraryResponse) - the frontend simply renders
    // whatever the backend response carries, so a VIEWER's details object having neither field is
    // enough to hide the line.
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, {
        storageQuotaBytes: 10 * 1024 * 1024 * 1024,
        storageUsedBytes: 3 * 1024 * 1024 * 1024,
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(
      await screen.findByText(/3([.,]0)? GB von 10([.,]0)? GB Speicherkontingent belegt/i),
    ).toBeInTheDocument()
    unmount()

    setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByText(/87 Dokumente/i)).toBeInTheDocument()
    expect(screen.queryByText(/Speicherkontingent belegt/i)).not.toBeInTheDocument()
  })

  it('offers editing but not deleting for a MANAGER', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Verwaltung' }))
    expect(await screen.findByRole('button', { name: /speichern/i })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /bibliothek löschen/i })).not.toBeInTheDocument()
  })

  it('offers "Rechte verwalten" for a MANAGER but hides it for a VIEWER', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Verwaltung' }))
    expect(await screen.findByRole('button', { name: /rechte verwalten/i })).toBeInTheDocument()
    unmount()

    setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByText(/87 Dokumente/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /rechte verwalten/i })).not.toBeInTheDocument()
  })

  it('lets a system admin edit and delete a library without an own grant', async () => {
    setSystemAdmin()
    setLibraryState(
      { ...viewerLibrary, ownerType: 'GROUP' },
      detailsOf(viewerLibrary, { ownerType: 'GROUP' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Verwaltung' }))
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

    await user.click(await screen.findByRole('tab', { name: 'Verwaltung' }))
    const nameField = await screen.findByLabelText(/name der bibliothek/i)
    await user.clear(nameField)
    await user.type(nameField, 'Rechtsquellen Soziales (neu)')
    const descriptionField = screen.getByLabelText(/beschreibung/i)
    await user.clear(descriptionField)
    await user.type(descriptionField, 'Aktualisierte Beschreibung')
    await user.click(screen.getByRole('combobox', { name: /verteilungsstufe/i }))
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

    await user.click(await screen.findByRole('tab', { name: 'Verwaltung' }))
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

    await user.click(await screen.findByRole('tab', { name: 'Verwaltung' }))
    await user.click(await screen.findByRole('button', { name: /bibliothek löschen/i }))

    await waitFor(() => {
      expect(mockDeleteLibrary).toHaveBeenCalledWith('library-team')
    })
    expect(confirmSpy).toHaveBeenCalledWith(expect.stringMatching(/indizierten dokumente/i))
  })

  it('shows the upload zone and document list for an UPLOAD library', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce(
      pageOf([
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
      ]),
    )
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText('dienstanweisung.pdf')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /dateien hochladen/i })).toBeInTheDocument()
    expect(screen.queryByText(/quellkonfiguration/i)).not.toBeInTheDocument()
  })

  it('shows the errorMessage of a FAILED document as a tooltip on its status chip', async () => {
    // #434/#589 review, item 6: a FAILED row's asynchronous processing failure is only visible to
    // the user via this German errorMessage - the status chip text alone only says something went
    // wrong ("fehlgeschlagen"), not what.
    mockGetLibraryDocuments.mockResolvedValueOnce(
      pageOf([
        {
          id: 'doc-failed',
          fileName: 'unlesbar.pdf',
          contentType: 'application/pdf',
          fileSize: 512,
          status: 'FAILED',
          sourceType: 'UPLOAD',
          chunkCount: 0,
          indexedAt: null,
          uploadedByUserId: 'u1',
          errorMessage: 'Aus der Datei konnte kein Text extrahiert werden',
        },
      ]),
    )
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await screen.findByText('unlesbar.pdf')
    const statusChip = screen.getByText('fehlgeschlagen')
    await user.hover(statusChip)

    expect(
      await screen.findByText('Aus der Datei konnte kein Text extrahiert werden'),
    ).toBeInTheDocument()
  })

  it('uploads a file and shows it in the list afterwards', async () => {
    // #506 review, finding 5: durchstich test for upload on the new page, mirroring the
    // equivalent test in the deleted DocumentsPage.test.tsx.
    // #517 code review, finding 2: uploadNewDocument no longer prepends the response locally - it
    // reloads the current page from the server, hence the second mockGetLibraryDocuments answer.
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
    mockUploadDocument.mockResolvedValueOnce({
      id: 'document-new',
      fileName: 'neues-dokument.pdf',
      contentType: 'application/pdf',
      fileSize: 1000,
      status: 'PENDING',
      sourceType: 'UPLOAD',
      chunkCount: 0,
      indexedAt: null,
      uploadedByUserId: 'mock-user-id',
    })
    mockGetLibraryDocuments.mockResolvedValueOnce(
      pageOf([
        {
          id: 'document-new',
          fileName: 'neues-dokument.pdf',
          contentType: 'application/pdf',
          fileSize: 1000,
          status: 'PENDING',
          sourceType: 'UPLOAD',
          chunkCount: 0,
          indexedAt: null,
          uploadedByUserId: 'mock-user-id',
        },
      ]),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    const file = new File(['Inhalt'], 'neues-dokument.pdf', { type: 'application/pdf' })
    const input = await screen.findByLabelText(/dateien auswählen/i, { selector: 'input' })
    await user.upload(input, file)

    expect(await screen.findByText('neues-dokument.pdf')).toBeInTheDocument()
    // #823: uploadDocument's request-layer signature grew a trailing folderPath - undefined here,
    // a plain file-picker selection has no directory structure of its own.
    expect(mockUploadDocument).toHaveBeenCalledWith('library-team', file, null, undefined)
  })

  it('deletes a document after confirmation and removes it from the list', async () => {
    // #506 review, finding 5: durchstich test for deletion with the confirmation dialog on the
    // new page, mirroring the equivalent test in the deleted DocumentsPage.test.tsx.
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    mockGetLibraryDocuments.mockResolvedValueOnce(
      pageOf([
        {
          id: 'document-1',
          fileName: 'dienstanweisung-2024.pdf',
          contentType: 'application/pdf',
          fileSize: 1000,
          status: 'INDEXED',
          sourceType: 'UPLOAD',
          chunkCount: 12,
          indexedAt: '2026-03-01T10:00:00Z',
          uploadedByUserId: 'mock-user-id',
        },
      ]),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    await screen.findByText('dienstanweisung-2024.pdf')
    await user.click(
      screen.getByRole('button', { name: /dokument dienstanweisung-2024\.pdf löschen/i }),
    )

    await waitFor(() => {
      expect(mockDeleteLibraryDocument).toHaveBeenCalledWith('library-team', 'document-1')
    })
    expect(screen.queryByText('dienstanweisung-2024.pdf')).not.toBeInTheDocument()
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

  it('offers "Bearbeiten" for a MANAGER on a connector library but hides it for a VIEWER', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Indizierung' }))
    expect(
      await screen.findByRole('button', { name: /^quellkonfiguration bearbeiten$/i }),
    ).toBeInTheDocument()
    unmount()

    // Since the tab layout, a VIEWER has no "Indizierung" area at all - no tab, no section, and
    // therefore no edit affordance (#507 unchanged: the path never reaches the client).
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByText(/87 Dokumente/i)
    expect(screen.queryByRole('tab', { name: 'Indizierung' })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /^quellkonfiguration bearbeiten$/i }),
    ).not.toBeInTheDocument()
  })

  it('shows a Confluence library read-only with edition and spaces, and still offers "Bearbeiten" (#1135)', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceCredentialsSet: true,
        confluenceEdition: 'DATA_CENTER',
        confluenceSpaces: [{ key: 'BAU', name: 'Bauamt' }],
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Indizierung' }))
    expect(
      await screen.findByRole('button', { name: /^quellkonfiguration bearbeiten$/i }),
    ).toBeInTheDocument()
    expect(screen.getByText('https://wiki.behoerde.example/confluence')).toBeInTheDocument()
    expect(screen.getByText(/Data Center/)).toBeInTheDocument()
    expect(screen.getByText(/nach der Anlage nicht änderbar/)).toBeInTheDocument()
    expect(screen.getByText('Bauamt (BAU)')).toBeInTheDocument()
    expect(screen.getByText(/gilt für alle Leseberechtigten der Bibliothek/)).toBeInTheDocument()
    // the edition is never an input here
    expect(screen.queryByRole('button', { name: 'Edition erkennen' })).not.toBeInTheDocument()
    // #1138: the sharing consequence is stated permanently, above the fold
    expect(screen.getByTestId('confluence-sharing-consequence')).toHaveTextContent(
      /für alle Leseberechtigten dieser Bibliothek sichtbar/,
    )
  })

  it('offers a forced full reconciliation only for a Confluence library (#1139)', async () => {
    const mockTrigger = vi.fn().mockResolvedValue(undefined)
    useIndexingStore.setState({ triggerIndexing: mockTrigger })
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        confluenceEdition: 'DATA_CENTER',
        confluenceSpaces: [{ key: 'ENG', name: 'Engineering' }],
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Vollabgleich starten' }))
    expect(mockTrigger).toHaveBeenCalledWith(ownerLibrary.id, 'CONFLUENCE', 'FULL')
    await user.click(screen.getByRole('button', { name: 'Jetzt indizieren' }))
    expect(mockTrigger).toHaveBeenLastCalledWith(ownerLibrary.id, 'CONFLUENCE')
    expect(
      screen.getByText(/in der Regel nur Änderungen seit dem letzten Lauf/),
    ).toBeInTheDocument()
    unmount()

    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByRole('button', { name: 'Jetzt indizieren' })
    expect(screen.queryByRole('button', { name: 'Vollabgleich starten' })).not.toBeInTheDocument()
  })

  it('marks an incomplete run and shows its cost figures (#1141)', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        confluenceEdition: 'DATA_CENTER',
        confluenceSpaces: [{ key: 'ENG', name: 'Engineering' }],
      }),
    )
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({
          runs: [
            {
              id: 'run-budget',
              status: 'COMPLETED',
              triggeredBy: 'SCHEDULED',
              runMode: 'FULL',
              documentCount: 400,
              totalDocuments: 900,
              documentsSkipped: 20,
              documentsFailed: 0,
              documentsIndexedTotal: 430,
              message:
                'Indizierung abgeschlossen: 400 verarbeitet, 20 übersprungen, 0 fehlgeschlagen — unvollständig (Anfragebudget erschöpft), der nächste Lauf setzt fort',
              startedAt: '2026-09-03T09:00:00Z',
              completedAt: '2026-09-03T09:12:30Z',
              incomplete: true,
              metrics: {
                requestsSent: 1000,
                throttleCount: 2,
                throttleWaitSeconds: 45,
                attachmentsProcessed: 30,
                attachmentsSkipped: 5,
                attachmentsFailed: 1,
              },
              events: [
                {
                  category: 'BUDGET_EXHAUSTED',
                  message:
                    'Anfragebudget von 1000 Anfragen erschöpft; der Lauf endet unvollständig, der nächste Lauf setzt bei Space HR fort',
                  reference: null,
                },
              ],
              eventsTruncatedCount: 0,
            },
          ],
        } satisfies IndexingRunListResponse),
      ),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByTestId('run-incomplete-run-budget')).toHaveTextContent(
      'unvollständig, wird fortgesetzt',
    )
    expect(screen.getByText('per Zeitplan')).toBeInTheDocument()
    expect(screen.getByTestId('run-metrics-run-budget')).toHaveTextContent(
      '1000 Anfragen an die Quelle · 2-mal gedrosselt (45 s gewartet) · Anhänge: 30 indiziert, 5 übersprungen, 1 fehlgeschlagen · Dauer 12 min 30 s',
    )
    expect(screen.getByText('Anfragebudget erschöpft')).toBeInTheDocument()
  })

  it('shows the webhook row to a manager of a Confluence library only (#1140)', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        confluenceEdition: 'DATA_CENTER',
        confluenceSpaces: [{ key: 'ENG', name: 'Engineering' }],
        confluenceWebhookSecretSet: true,
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Indizierung' }))
    expect(await screen.findByTestId('confluence-webhook-section')).toHaveTextContent(
      /Webhook:.*eingerichtet/,
    )
    expect(screen.getByRole('button', { name: 'Geheimnis neu erzeugen' })).toBeInTheDocument()
    unmount()

    // a VIEWER sees edition and spaces (#1138), but no webhook affordance
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, {
        sourceType: 'CONFLUENCE',
        confluenceEdition: 'DATA_CENTER',
        confluenceSpaces: [{ key: 'ENG', name: 'Engineering' }],
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByText('Engineering (ENG)')
    expect(screen.queryByTestId('confluence-webhook-section')).not.toBeInTheDocument()
  })

  it('states the Confluence sharing consequence to a VIEWER as well, and never for other types (#1138)', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, {
        sourceType: 'CONFLUENCE',
        confluenceEdition: 'CLOUD',
        confluenceSpaces: [{ key: 'BAU', name: 'Bauamt' }],
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByTestId('confluence-sharing-consequence')).toHaveTextContent(
      /weist das Laufprotokoll das aus/,
    )
    // the scope itself - edition and spaces - is visible to the VIEWER, address and proxy are not
    expect(screen.getByText('Bauamt (BAU)')).toBeInTheDocument()
    expect(screen.getByText(/Cloud/)).toBeInTheDocument()
    expect(screen.queryByText(/Proxy/)).not.toBeInTheDocument()
    unmount()

    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'RSS_FEED', sourceUrl: 'https://example.org/feed' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByText(/Sie können diese Bibliothek einsehen/)
    expect(screen.queryByTestId('confluence-sharing-consequence')).not.toBeInTheDocument()
  })

  // #507: the backend now only serves sourcePath/sourceUrl/sourceProxy/sourceInsecureSsl/
  // sourceCredentialsSet to a caller with at least MANAGER - a VIEWER's library object simply
  // carries none of them. This test still passes sourcePath explicitly in the VIEWER case to
  // prove the frontend itself withholds the display rather than merely reflecting an already
  // absent field.
  it('shows the source configuration detail for a MANAGER but not at all for a VIEWER', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByText('/data/dokumente')).toBeInTheDocument()
    unmount()

    // Since the tab layout, a VIEWER has no source configuration area (and no hint about it) -
    // #507 unchanged: the backend never serves the path to a VIEWER, and the page renders no
    // placeholder for data that was never sent.
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByText(/87 Dokumente/i)
    expect(screen.queryByRole('tab', { name: 'Indizierung' })).not.toBeInTheDocument()
    expect(screen.queryByText('/data/dokumente')).not.toBeInTheDocument()
  })

  it('edits the source configuration through the dialog, resending the unrelated Stammdaten fields untouched', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('tab', { name: 'Indizierung' }))
    await user.click(
      await screen.findByRole('button', { name: /^quellkonfiguration bearbeiten$/i }),
    )
    const pathField = await screen.findByLabelText(/verzeichnispfad/i)
    await user.clear(pathField)
    await user.type(pathField, '/data/umgezogen')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-team', {
        name: ownerLibrary.name,
        description: ownerLibrary.description,
        visibility: ownerLibrary.visibility,
        listed: ownerLibrary.listed,
        sourcePath: '/data/umgezogen',
        sourceUrl: undefined,
        sourceProxy: undefined,
        sourceCredentials: undefined,
        sourceInsecureSsl: false,
      } satisfies LibraryUpdateRequest)
    })
  })

  it('shows the current schedule and the next planned run for a MANAGER', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, {
        sourceType: 'FILESYSTEM',
        sourcePath: '/data/dokumente',
        schedule: {
          frequency: 'DAILY',
          hour: 3,
          minute: 30,
          nextRunAt: '2026-03-02T03:30:00Z',
        },
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText('Täglich')).toBeInTheDocument()
    expect(await screen.findByText(/nächster geplanter lauf/i)).toBeInTheDocument()
  })

  it('shows a warning when the last two scheduled runs failed', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, {
        sourceType: 'FILESYSTEM',
        sourcePath: '/data/dokumente',
        schedule: { frequency: 'HOURLY', nextRunAt: '2026-03-02T03:00:00Z' },
        lastScheduledRunsFailed: true,
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(
      await screen.findByText(/letzten geplanten läufe dieser bibliothek sind fehlgeschlagen/i),
    ).toBeInTheDocument()
  })

  it('edits the schedule through the dialog, resending the unrelated Stammdaten fields untouched', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'FILESYSTEM',
        sourcePath: '/data/dokumente',
        schedule: { frequency: 'DISABLED' },
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('tab', { name: 'Indizierung' }))
    await user.click(await screen.findByRole('button', { name: /^zeitplan bearbeiten$/i }))
    await user.click(screen.getByRole('combobox', { name: /^zeitplan$/i }))
    await user.click(await screen.findByRole('option', { name: 'Stündlich' }))
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-team', {
        name: ownerLibrary.name,
        description: ownerLibrary.description,
        visibility: ownerLibrary.visibility,
        listed: ownerLibrary.listed,
        sourceInsecureSsl: null,
        schedule: {
          frequency: 'HOURLY',
          hour: null,
          minute: null,
          weekday: undefined,
        },
      } satisfies LibraryUpdateRequest)
    })
  })

  it('shows the plain document wording for a completed FILESYSTEM run', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    mockGetIndexingStatus.mockResolvedValueOnce({
      status: 'COMPLETED',
      documentCount: 10,
      totalDocuments: 12,
      documentsSkipped: 2,
      documentsFailed: 0,
      documentsIndexedTotal: 10,
      message: null,
      timestamp: '2026-03-01T10:00:00Z',
    } as IndexingStatusResponse)
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(
      await screen.findByText(/dokumente: 10 verarbeitet \(2 übersprungen\)/i),
    ).toBeInTheDocument()
    expect(screen.queryByText(/feed-einträge/i)).not.toBeInTheDocument()
  })

  it('reloads the document list once a run finishes, without a manual page reload', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByLabelText('Dokumente durchsuchen')
    const initialLoads = mockGetLibraryDocuments.mock.calls.length

    // The status polling flips the run to RUNNING and later to COMPLETED - the falling edge is
    // what must re-fetch the currently shown view of the document list.
    act(() => {
      useIndexingStore.setState({
        runsByLibrary: { [managerLibrary.id]: { ...IDLE_RUN_STATE, status: 'RUNNING' } },
      })
    })
    act(() => {
      useIndexingStore.setState({
        runsByLibrary: {
          [managerLibrary.id]: {
            ...IDLE_RUN_STATE,
            status: 'COMPLETED',
            timestamp: '2026-09-04T10:00:00Z',
          },
        },
      })
    })

    await waitFor(() => {
      expect(mockGetLibraryDocuments.mock.calls.length).toBeGreaterThan(initialLoads)
    })
  })

  // #513: the run history section shows past runs' header data up front and reveals each run's
  // own protocol (category/message/reference per skipped or rejected item) only after expanding
  // it - collapsed by default, so a library with a long history does not dump every event onto
  // the page at once.
  it('shows past runs collapsed, revealing the protocol only after expanding a run', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({
          runs: [
            {
              id: 'run-1',
              status: 'COMPLETED',
              triggeredBy: 'MANUAL',
              runMode: 'FULL',
              documentCount: 10,
              totalDocuments: 12,
              documentsSkipped: 2,
              documentsFailed: 0,
              documentsIndexedTotal: 10,
              message: null,
              startedAt: '2026-03-01T09:00:00Z',
              completedAt: '2026-03-01T09:01:00Z',
              events: [
                {
                  category: 'UNSUPPORTED_FORMAT',
                  message: 'Dateiformat wird nicht unterstützt',
                  reference: 'bad.csv',
                },
              ],
              eventsTruncatedCount: 0,
            },
          ],
        } satisfies IndexingRunListResponse),
      ),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Indizierung' }))

    expect(await screen.findByText(/letzte indizierungsläufe/i)).toBeInTheDocument()
    await screen.findByText(/10 verarbeitet, 2 übersprungen/i)
    // MUI's Accordion keeps its (collapsed) AccordionDetails mounted in the DOM for the
    // collapse/expand animation - collapsed-ness is exposed through aria-expanded on the summary
    // button, not through the protocol's absence from the DOM.
    const runToggle = screen.getByRole('button', { name: /10 verarbeitet, 2 übersprungen/i })
    expect(runToggle).toHaveAttribute('aria-expanded', 'false')

    const user = userEvent.setup()
    await user.click(runToggle)

    expect(runToggle).toHaveAttribute('aria-expanded', 'true')
    expect(await screen.findByText('bad.csv')).toBeVisible()
    expect(screen.getByText('Dateiformat wird nicht unterstützt')).toBeVisible()
    expect(
      within(screen.getByText('bad.csv').closest('div')!).getByText(/format nicht/i),
    ).toBeVisible()
  })

  // ADR-0023, Entscheidung 4 (#1136): a Confluence library's runs name their Betriebsart; for
  // every other type the mode is implied by the source type and no chip is shown.
  it("names the run mode on a Confluence library's runs and only there", async () => {
    const run = {
      id: 'run-voll',
      status: 'COMPLETED',
      triggeredBy: 'MANUAL',
      runMode: 'FULL',
      documentCount: 4,
      totalDocuments: 4,
      documentsSkipped: 0,
      documentsFailed: 0,
      documentsIndexedTotal: 5,
      message: null,
      startedAt: '2026-09-03T09:00:00Z',
      completedAt: '2026-09-03T09:01:00Z',
      events: [
        {
          category: 'RATE_LIMITED',
          message: 'Confluence hat den Lauf 2-mal gedrosselt',
          reference: null,
        },
        {
          category: 'REJECTED',
          message: 'Space SEC ist für das hinterlegte Dienstkonto nicht lesbar',
          reference: 'SEC',
        },
      ],
      eventsTruncatedCount: 0,
    }
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({ runs: [run] }),
      ),
    )
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        confluenceEdition: 'DATA_CENTER',
        confluenceSpaces: [{ key: 'ENG', name: 'Engineering' }],
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByTestId('run-mode-run-voll')).toHaveTextContent('Vollabgleich')
    // #1138: a run that could not read something says so in its header, not only when expanded
    expect(screen.getByText('2 Ereignisse, davon 1 nicht lesbar')).toBeInTheDocument()
    const user = userEvent.setup()
    // expanding the run through its own header (the mode chip sits inside the accordion summary)
    await user.click(screen.getByTestId('run-mode-run-voll'))
    expect(await screen.findByText('Ratenbegrenzung')).toBeInTheDocument()
    unmount()

    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByText(/4 verarbeitet/)
    expect(screen.queryByTestId('run-mode-run-voll')).not.toBeInTheDocument()
  })

  it('names how many further events a run recorded beyond the protocol cap', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({
          runs: [
            {
              id: 'run-1',
              status: 'COMPLETED',
              triggeredBy: 'MANUAL',
              runMode: 'FULL',
              documentCount: 1,
              totalDocuments: 600,
              documentsSkipped: 599,
              documentsFailed: 0,
              documentsIndexedTotal: 1,
              message: null,
              startedAt: '2026-03-01T09:00:00Z',
              completedAt: '2026-03-01T09:01:00Z',
              events: [
                {
                  category: 'REJECTED',
                  message: 'Vom Quellserver abgewiesen',
                  reference: 'https://example.org/1',
                },
              ],
              eventsTruncatedCount: 42,
            },
          ],
        } satisfies IndexingRunListResponse),
      ),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const user = userEvent.setup()
    await user.click(await screen.findByText(/1 verarbeitet, 599 übersprungen/i))

    expect(await screen.findByText(/… und 42 weitere/i)).toBeInTheDocument()
  })

  it('shows the feed-entries-plus-document-total wording for a completed RSS_FEED run', async () => {
    // #518 review, finding 1: the wording is decided by sourceType (RSS_FEED here), not by
    // comparing documentCount/documentsIndexedTotal - both are deliberately equal below to prove
    // the switch does not derive from that comparison.
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, {
        sourceType: 'RSS_FEED',
        sourceUrl: 'https://example.gov/feed.xml',
      }),
    )
    mockGetIndexingStatus.mockResolvedValueOnce({
      status: 'COMPLETED',
      documentCount: 13,
      totalDocuments: 18,
      documentsSkipped: 5,
      documentsFailed: 0,
      documentsIndexedTotal: 13,
      message: null,
      timestamp: '2026-03-01T10:00:00Z',
    } as IndexingStatusResponse)
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(
      await screen.findByText(
        /18 feed-einträge, 5 übersprungen, 13 indiziert \(13 dokumente insgesamt\)/i,
      ),
    ).toBeInTheDocument()
  })

  it('clears a stale upload error from a previously viewed library after switching', async () => {
    // #506 review, finding 2: uploadErrors/deleteError/error in documentStore are not keyed by
    // library - without a reset on mount/switch, an error from library A would keep showing on
    // library B's section.
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
    mockUploadDocument.mockRejectedValueOnce(new Error('Diese Datei ist bereits vorhanden'))
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    const file = new File(['Inhalt'], 'dublette.pdf', { type: 'application/pdf' })
    const input = await screen.findByLabelText(/dateien auswählen/i, { selector: 'input' })
    await user.upload(input, file)
    expect(await screen.findByText(/bereits vorhanden.*dublette\.pdf/is)).toBeInTheDocument()
    unmount()

    setLibraryState(personalLibrary, detailsOf(personalLibrary))
    mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await screen.findByText(/dateien oder ordner hierher ziehen/i)
    expect(screen.queryByText(/bereits vorhanden/i)).not.toBeInTheDocument()
  })

  it('shows the library store error alongside the loaded library when reloading its details fails', async () => {
    // #506 review, finding 3: without this, a failed GET /libraries/{id} while the list entry is
    // already cached silently drops both typed sections with no explanation.
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    useLibraryStore.setState((state) => ({
      libraryDetails: {},
      error: 'Bibliotheksdetails konnten nicht geladen werden',
      libraries: state.libraries,
      isLoading: false,
    }))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(
      await screen.findByText(/bibliotheksdetails konnten nicht geladen werden/i),
    ).toBeInTheDocument()
    expect(screen.getByText(managerLibrary.name)).toBeInTheDocument()
  })

  // #604 review, finding 1: the backend gates GET .../indexing/runs at MANAGER (canManage) since
  // its events routinely carry the library's own sourcePath/sourceUrl - the same internal-path
  // leak #507 exists to close for the source configuration display. The frontend must not even
  // fire that request for a VIEWER, let alone render a section only an error would otherwise fill.
  it('hides the run history section entirely for a VIEWER on a connector library', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await screen.findByText(/87 Dokumente/i)
    expect(screen.queryByText(/letzte indizierungsläufe/i)).not.toBeInTheDocument()
  })

  it('hides the indexing trigger for a VIEWER on a connector library', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await screen.findByText(/87 Dokumente/i)
    expect(screen.queryByRole('button', { name: /jetzt indizieren/i })).not.toBeInTheDocument()
  })

  // #517 code review, nit 4: a VIEWER on a connector library must see both hints - "nur
  // Leserechte" was previously scoped to isUploadLibrary and silently dropped for this case.
  it('shows the read-only hint for a VIEWER on a connector library, alongside the connector hint', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    // Exact match, not a substring: LibraryIndexingSection shows its own, differently worded
    // "nur Leserechte und können keine Indizierung anstoßen." hint on the same connector-library
    // page, so a loose regex would find two elements and fail as ambiguous.
    expect(
      await screen.findByText('Sie haben in dieser Bibliothek nur Leserechte.'),
    ).toBeInTheDocument()
    expect(screen.getByText(/lassen sich hier nicht löschen/i)).toBeInTheDocument()
  })

  // #517: the document list previously only rendered for UPLOAD libraries - a connector library's
  // indexed bestand was invisible even though the API always served it.
  it('shows the document list for a FILESYSTEM library, without upload or delete controls', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce(
      pageOf([
        {
          id: 'doc-1',
          fileName: 'rundschreiben.pdf',
          contentType: 'application/pdf',
          fileSize: 2048,
          status: 'INDEXED',
          sourceType: 'FILESYSTEM',
          chunkCount: 5,
          indexedAt: '2026-03-01T10:00:00Z',
          uploadedByUserId: null,
        },
      ]),
    )
    setLibraryState(
      { ...managerLibrary, myRole: 'OWNER' },
      detailsOf(
        { ...managerLibrary, myRole: 'OWNER' },
        { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' },
      ),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText('rundschreiben.pdf')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /dateien hochladen/i })).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: /dokument rundschreiben\.pdf löschen/i }),
    ).not.toBeInTheDocument()
    expect(screen.getByText(/lassen sich hier nicht löschen/i)).toBeInTheDocument()
  })

  // #493: eine RSS-Anlage (#468) trägt ihren Herkunfts-Eintrag über sourceEntryUrl - die
  // Detailseite muss ihn sichtbar machen, statt ihn nur zu speichern.
  it('shows the source entry URL for an RSS attachment, but not for a document without one', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce(
      pageOf([
        {
          // The RSS entry's own document row (its detail page's main text) - sourceEntryUrl is
          // null here (Document#getSourceEntryUrl's Javadoc: "null ... including the RSS entry's
          // own row"), only an attachment discovered on it carries one.
          id: 'doc-1',
          fileName: 'rundschreiben.pdf',
          contentType: 'application/pdf',
          fileSize: 2048,
          status: 'INDEXED',
          sourceType: 'RSS_FEED',
          chunkCount: 5,
          indexedAt: '2026-03-01T10:00:00Z',
          uploadedByUserId: null,
        },
        {
          id: 'doc-2',
          fileName: 'dienstanweisung-anlage.pdf',
          contentType: 'application/pdf',
          fileSize: 4096,
          status: 'INDEXED',
          sourceType: 'RSS_FEED',
          chunkCount: 8,
          indexedAt: '2026-03-01T10:05:00Z',
          uploadedByUserId: null,
          sourceEntryUrl: 'https://example.gov/aktuelles/dienstanweisung-2024',
        },
      ]),
    )
    setLibraryState(
      { ...managerLibrary, myRole: 'OWNER' },
      detailsOf(
        { ...managerLibrary, myRole: 'OWNER' },
        // #493 review, finding 3: an RSS attachment (sourceType RSS_FEED, sourceEntryUrl set)
        // can only exist in an RSS_FEED library - a FILESYSTEM library here would be an
        // impossible state the backend never produces.
        { sourceType: 'RSS_FEED', sourceUrl: 'https://example.gov/feed.xml' },
      ),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText('dienstanweisung-anlage.pdf')).toBeInTheDocument()
    const link = screen.getByRole('link', {
      name: 'https://example.gov/aktuelles/dienstanweisung-2024',
    })
    expect(link).toHaveAttribute('href', 'https://example.gov/aktuelles/dienstanweisung-2024')

    // Nur die Anlage trägt sourceEntryUrl - kein zweiter "Herkunft:"-Hinweis für das
    // FILESYSTEM-Dokument ohne diesen Wert.
    expect(screen.getAllByText(/herkunft:/i)).toHaveLength(1)
  })

  // #1184 (ADR-0022, Entscheidung 5): attachments carry parentDocumentId and follow their
  // top-level parent in the server's items order - the list groups them collapsibly under it.
  describe('attachment grouping (#1184)', () => {
    function docOf(
      id: string,
      fileName: string,
      overrides: Partial<LibraryDocumentResponse> = {},
    ): LibraryDocumentResponse {
      return {
        id,
        fileName,
        contentType: 'application/pdf',
        fileSize: 1000,
        status: 'INDEXED',
        sourceType: 'UPLOAD',
        chunkCount: 3,
        indexedAt: '2026-03-01T10:00:00Z',
        uploadedByUserId: null,
        ...overrides,
      }
    }

    const mail = docOf('document-mail', 'posteingang.eml')
    const attachment1 = docOf('document-anhang-1', 'foerderbescheid.pdf', {
      parentDocumentId: 'document-mail',
    })
    const attachment2 = docOf('document-anhang-2', 'anlage-berechnung.xlsx', {
      parentDocumentId: 'document-mail',
    })

    it('collapses attachments under their parent by default and toggles them via the button', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      mockGetLibraryDocuments.mockResolvedValue(
        pageOf([mail, attachment1, attachment2], { totalElements: 1 }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await screen.findByText('posteingang.eml')
      // Collapsed by default: the list stays at its parent-level length.
      expect(screen.queryByText('foerderbescheid.pdf')).not.toBeInTheDocument()
      expect(screen.queryByText('anlage-berechnung.xlsx')).not.toBeInTheDocument()

      const toggle = screen.getByRole('button', { name: 'Anhänge von posteingang.eml anzeigen' })
      expect(toggle).toHaveAttribute('aria-expanded', 'false')
      expect(toggle).toHaveTextContent('2 Anhänge')
      await user.click(toggle)

      expect(screen.getByText('foerderbescheid.pdf')).toBeInTheDocument()
      expect(screen.getByText('anlage-berechnung.xlsx')).toBeInTheDocument()
      // Attachment rows are marked as such.
      expect(screen.getAllByText('Anhang', { exact: true })).toHaveLength(2)

      const collapse = screen.getByRole('button', { name: 'Anhänge von posteingang.eml verbergen' })
      expect(collapse).toHaveAttribute('aria-expanded', 'true')
      await user.click(collapse)
      expect(screen.queryByText('foerderbescheid.pdf')).not.toBeInTheDocument()
    })

    it('expands groups by default while a search is active, so an attachment hit is visible', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      // The backend answers an attachment hit with the parent plus its whole group (#1184).
      mockGetLibraryDocuments.mockResolvedValue(
        pageOf([mail, attachment1, attachment2], { totalElements: 1 }),
      )
      await user.type(await screen.findByLabelText(/dokumente durchsuchen/i), 'foerder')

      expect(await screen.findByText('foerderbescheid.pdf')).toBeInTheDocument()
      expect(screen.getByText('posteingang.eml')).toBeInTheDocument()
      expect(
        screen.getByRole('button', { name: 'Anhänge von posteingang.eml verbergen' }),
      ).toHaveAttribute('aria-expanded', 'true')
    })

    it('renders a nested attachment chain flat under the top-level parent with a via hint', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      const forwardedMail = docOf('document-weiterleitung', 'weiterleitung.eml', {
        parentDocumentId: 'document-mail',
      })
      const innerAttachment = docOf('document-innere-anlage', 'innere-anlage.pdf', {
        parentDocumentId: 'document-weiterleitung',
      })
      mockGetLibraryDocuments.mockResolvedValue(
        pageOf([mail, forwardedMail, innerAttachment], { totalElements: 1 }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await screen.findByText('posteingang.eml')
      await user.click(screen.getByRole('button', { name: 'Anhänge von posteingang.eml anzeigen' }))

      // Both nesting levels sit flat under the one top-level row; the deeper one names its
      // direct parent instead of nesting further.
      expect(screen.getByText('weiterleitung.eml')).toBeInTheDocument()
      expect(screen.getByText('innere-anlage.pdf')).toBeInTheDocument()
      expect(screen.getByText('Anhang von: weiterleitung.eml')).toBeInTheDocument()
    })

    it('pages on the parent level: attachments neither count towards totalElements nor paginate', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      const other = docOf('document-b', 'b-dokument.pdf')
      // A page of size 2 carrying 2 parents plus 2 attachments - totalElements counts the 3
      // parents across all pages, so exactly 2 pagination pages result, not 3.
      mockGetLibraryDocuments.mockResolvedValue(
        pageOf([mail, attachment1, attachment2, other], { size: 2, totalElements: 3 }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await screen.findByText('posteingang.eml')
      expect(screen.getByText('b-dokument.pdf')).toBeInTheDocument()
      await user.click(screen.getByRole('button', { name: 'Anhänge von posteingang.eml anzeigen' }))
      expect(screen.getByText('foerderbescheid.pdf')).toBeInTheDocument()

      const pagination = screen.getByRole('navigation', { name: 'Dokumentenliste blättern' })
      expect(within(pagination).getByRole('button', { name: /seite 2/i })).toBeInTheDocument()
      expect(within(pagination).queryByRole('button', { name: /seite 3/i })).not.toBeInTheDocument()
    })
  })

  // #738: local sourceTypes fetch the file as a Blob and open/download it client-side, since the
  // download endpoint is Bearer-authenticated - see utils/documentContent.test.ts for that piece's
  // own behaviour, mocked here (see the vi.mock above) to isolate which target this page picks.
  describe('"Original öffnen"', () => {
    it('opens a Confluence document at its source URL instead of the content endpoint (ADR-0023)', async () => {
      // The backend's content endpoint deliberately answers 404 for CONFLUENCE - a page has no
      // original file of its own; its original IS the page in the instance.
      setLibraryState(
        managerLibrary,
        detailsOf(managerLibrary, {
          sourceType: 'CONFLUENCE',
          sourceUrl: 'http://wiki.example',
          confluenceEdition: 'DATA_CENTER',
          confluenceSpaces: [{ key: 'IT', name: 'IT-Betrieb' }],
        }),
      )
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-conf',
            fileName: 'Betriebshandbuch',
            contentType: 'text/html',
            fileSize: 512,
            status: 'INDEXED',
            sourceType: 'CONFLUENCE',
            chunkCount: 2,
            indexedAt: '2026-09-04T10:00:00Z',
            uploadedByUserId: null,
            sourceUrl: 'http://wiki.example/pages/viewpage.action?pageId=42',
          },
        ]),
      )
      const openSpy = vi.spyOn(window, 'open').mockReturnValue(null)
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(
        await screen.findByRole('button', { name: 'Original von Betriebshandbuch öffnen' }),
      )

      expect(openSpy).toHaveBeenCalledWith(
        'http://wiki.example/pages/viewpage.action?pageId=42',
        '_blank',
        'noopener,noreferrer',
      )
      expect(mockOpenDocumentContent).not.toHaveBeenCalled()
      openSpy.mockRestore()
    })

    it("names a Confluence document's space and hierarchy path in its row (ADR-0023)", async () => {
      setLibraryState(
        managerLibrary,
        detailsOf(managerLibrary, {
          sourceType: 'CONFLUENCE',
          sourceUrl: 'http://wiki.example',
          confluenceEdition: 'DATA_CENTER',
          confluenceSpaces: [{ key: 'IT', name: 'IT-Betrieb' }],
        }),
      )
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-conf-space',
            fileName: 'Abschnitt 1.1 — Firewall-Regeln',
            contentType: 'text/html',
            fileSize: 512,
            status: 'INDEXED',
            sourceType: 'CONFLUENCE',
            chunkCount: 2,
            indexedAt: '2026-09-04T10:00:00Z',
            uploadedByUserId: null,
            sourceUrl: 'http://wiki.example/pages/viewpage.action?pageId=43',
            sourceContainerKey: 'IT',
            sourceHierarchyPath: 'Betriebshandbuch / Kapitel 1 — Netzwerkbetrieb',
          },
        ]),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      // The key resolves to the name the library's own selection carries.
      expect(
        await screen.findByText(
          'Space: IT-Betrieb (IT) · Betriebshandbuch / Kapitel 1 — Netzwerkbetrieb',
        ),
      ).toBeInTheDocument()
    })

    it('fetches and opens the file as a Blob for a local (UPLOAD/FILESYSTEM) document', async () => {
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-1',
            fileName: 'dienstanweisung.pdf',
            contentType: 'application/pdf',
            fileSize: 2048,
            status: 'INDEXED',
            sourceType: 'UPLOAD',
            chunkCount: 3,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: null,
          },
        ]),
      )
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const button = await screen.findByRole('button', {
        name: 'Original von dienstanweisung.pdf öffnen',
      })
      await user.click(button)

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'dienstanweisung.pdf')
    })

    it('fetches and opens the file as a Blob for an HTTP_DIRECTORY document too (#747)', async () => {
      // #747: the content endpoint now proxies HTTP_DIRECTORY/RSS_FEED server-side from their own
      // stored source URL instead of the client navigating there directly - the Demo-Instanz's
      // corpus containers are only reachable from OPAA's own Docker network, never the browser.
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-1',
            fileName: 'dienstanweisung.pdf',
            contentType: 'application/pdf',
            fileSize: 2048,
            status: 'INDEXED',
            sourceType: 'HTTP_DIRECTORY',
            chunkCount: 3,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: null,
            sourceUrl: 'https://example.gov/verzeichnis/dienstanweisung.pdf',
          },
        ]),
      )
      setLibraryState(
        { ...managerLibrary, myRole: 'OWNER' },
        detailsOf({ ...managerLibrary, myRole: 'OWNER' }, { sourceType: 'HTTP_DIRECTORY' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const button = await screen.findByRole('button', {
        name: 'Original von dienstanweisung.pdf öffnen',
      })
      await user.click(button)

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'dienstanweisung.pdf')

      // #747: the source URL stays visible as secondary information (a "Quelle:" link) even
      // though "Original öffnen" no longer navigates there directly.
      const sourceLink = screen.getByRole('link', {
        name: 'https://example.gov/verzeichnis/dienstanweisung.pdf',
      })
      expect(sourceLink).toHaveAttribute(
        'href',
        'https://example.gov/verzeichnis/dienstanweisung.pdf',
      )
    })

    it('opens an RSS attachment through the content endpoint, keeping sourceEntryUrl as secondary information', async () => {
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-1',
            fileName: 'anlage.pdf',
            contentType: 'application/pdf',
            fileSize: 2048,
            status: 'INDEXED',
            sourceType: 'RSS_FEED',
            chunkCount: 3,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: null,
            sourceEntryUrl: 'https://example.gov/aktuelles/dienstanweisung-2024',
            sourceUrl: 'https://example.gov/feed/anlage.pdf',
          },
        ]),
      )
      setLibraryState(
        { ...managerLibrary, myRole: 'OWNER' },
        detailsOf({ ...managerLibrary, myRole: 'OWNER' }, { sourceType: 'RSS_FEED' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const button = await screen.findByRole('button', { name: 'Original von anlage.pdf öffnen' })
      await user.click(button)

      expect(mockOpenDocumentContent).toHaveBeenCalledWith('doc-1', 'anlage.pdf')
      expect(
        screen.getByRole('link', { name: 'https://example.gov/aktuelles/dienstanweisung-2024' }),
      ).toBeInTheDocument()
    })

    it('still offers the action for a remote document with no other source information', async () => {
      // #747: every sourceType opens through the content endpoint now - a document that turns out
      // unreachable answers a German 404 shown via openOriginalError, not a reason to hide the
      // button up front the way the pre-#747 sourceUrl-based gating did.
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-1',
            fileName: 'eintrag.html',
            status: 'INDEXED',
            sourceType: 'RSS_FEED',
            chunkCount: 1,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: null,
          },
        ]),
      )
      setLibraryState(
        { ...managerLibrary, myRole: 'OWNER' },
        detailsOf({ ...managerLibrary, myRole: 'OWNER' }, { sourceType: 'RSS_FEED' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      expect(
        await screen.findByRole('button', { name: /original von eintrag\.html öffnen/i }),
      ).toBeInTheDocument()
    })

    it('shows a German error message when opening the original fails (e.g. 404)', async () => {
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-1',
            fileName: 'dienstanweisung.pdf',
            contentType: 'application/pdf',
            fileSize: 2048,
            status: 'INDEXED',
            sourceType: 'UPLOAD',
            chunkCount: 3,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: null,
          },
        ]),
      )
      mockOpenDocumentContent.mockRejectedValueOnce(
        new Error('Das Originaldokument wurde nicht gefunden.'),
      )
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const button = await screen.findByRole('button', {
        name: 'Original von dienstanweisung.pdf öffnen',
      })
      await user.click(button)

      expect(
        await screen.findByText('Das Originaldokument wurde nicht gefunden.'),
      ).toBeInTheDocument()
    })

    // #780: Markdown/plain text render in a client-side dialog instead of a silent download.
    it('opens a Markdown text preview dialog instead of a silent download (#780)', async () => {
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-1',
            fileName: '001_personalausweis.md',
            contentType: 'text/markdown',
            fileSize: 512,
            status: 'INDEXED',
            sourceType: 'UPLOAD',
            chunkCount: 1,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: null,
          },
        ]),
      )
      mockOpenDocumentContent.mockResolvedValueOnce({
        kind: 'text-preview',
        fileName: '001_personalausweis.md',
        contentType: 'text/markdown',
        content: '# Personalausweis\n\nAusgestellt am 1. März.',
      })
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const button = await screen.findByRole('button', {
        name: 'Original von 001_personalausweis.md öffnen',
      })
      await user.click(button)

      expect(await screen.findByRole('dialog')).toBeInTheDocument()
      // #1016: heading elements are normalised per rendered content (rank compression from h2);
      // the h5 LOOK of "#" survives as the Typography variant.
      const heading1016 = screen.getByText('Personalausweis').closest('h2')
      expect(heading1016).toBeInTheDocument()
      expect(heading1016).toHaveClass('MuiTypography-h5')
      expect(screen.getByText(/Ausgestellt am 1\. März\./)).toBeInTheDocument()
    })

    // #780 acceptance criteria: every format without a preview (DOCX among them) must give visible
    // download feedback so the click never appears to do nothing.
    it('shows a snackbar with the file name when a DOCX download starts (#780)', async () => {
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-1',
            fileName: 'bescheid.docx',
            contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
            fileSize: 4096,
            status: 'INDEXED',
            sourceType: 'UPLOAD',
            chunkCount: 2,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: null,
          },
        ]),
      )
      mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'bescheid.docx' })
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const button = await screen.findByRole('button', {
        name: 'Original von bescheid.docx öffnen',
      })
      await user.click(button)

      expect(await screen.findByText('bescheid.docx wird heruntergeladen')).toBeInTheDocument()
    })
  })

  it('searches documents server-side, debounced, resetting to the first page', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await screen.findByText(/es sind noch keine dokumente vorhanden/i)
    mockGetLibraryDocuments.mockClear()
    mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

    const searchField = screen.getByLabelText(/dokumente durchsuchen/i)
    await user.type(searchField, 'sozial')

    await waitFor(
      () => {
        expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-team', {
          page: 0,
          size: 20,
          q: 'sozial',
          folderId: null,
        })
      },
      { timeout: 2000 },
    )
  })

  it('shows pagination controls once more than one page of documents exists', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce(
      pageOf(
        [
          {
            id: 'doc-1',
            fileName: 'a.pdf',
            contentType: 'application/pdf',
            fileSize: 100,
            status: 'INDEXED',
            sourceType: 'UPLOAD',
            chunkCount: 1,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: 'u1',
          },
        ],
        { page: 0, size: 1, totalElements: 3 },
      ),
    )
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await screen.findByText('a.pdf')
    // #784: the deDE MUI locale translates Pagination's default item aria-labels.
    expect(screen.getByRole('button', { name: 'Gehe zu Seite 2' })).toBeInTheDocument()
  })

  // #822 (Epic #520 Phase 3): folder navigation/management UI - Backend-Fundament (#820/#821) is
  // already covered elsewhere; these tests only exercise the frontend wiring.
  describe('folder navigation and management (#822)', () => {
    it('shows folder rows above documents and navigates into one via the breadcrumb', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folders: [{ id: 'folder-protokolle', name: 'Protokolle', documentCount: 3 }],
        }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const folderRow = await screen.findByRole('button', {
        name: /ordner protokolle öffnen/i,
      })
      expect(screen.getByText(/3 dokumente/i)).toBeInTheDocument()

      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folderId: 'folder-protokolle',
          breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
        }),
      )
      await user.click(folderRow)

      const breadcrumbNav = await screen.findByRole('navigation', { name: /ordnerpfad/i })
      expect(within(breadcrumbNav).getByText('Protokolle')).toBeInTheDocument()
      expect(mockGetLibraryDocuments).toHaveBeenLastCalledWith('library-mine', {
        page: 0,
        size: 20,
        q: '',
        folderId: 'folder-protokolle',
      })
    })

    it('loads the folder named in the ?folder= URL param directly (deep link/reload)', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folderId: 'folder-protokolle',
          breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
        }),
      )

      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?folder=folder-protokolle',
      })

      const breadcrumbNav = await screen.findByRole('navigation', { name: /ordnerpfad/i })
      expect(within(breadcrumbNav).getByText('Protokolle')).toBeInTheDocument()
      expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-mine', {
        page: 0,
        size: 20,
        q: '',
        folderId: 'folder-protokolle',
      })
    })

    it('falls back to the root and shows a hint when the URL names an unknown folder (404)', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockRejectedValueOnce(notFoundError())
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?folder=does-not-exist',
      })

      expect(await screen.findByText(/der ordner wurde nicht gefunden/i)).toBeInTheDocument()
      expect(screen.queryByRole('navigation', { name: /ordnerpfad/i })).not.toBeInTheDocument()
    })

    it('does not fall back to the root on a non-404 failure (e.g. a 500)', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockRejectedValueOnce(new Error('Interner Serverfehler'))

      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?folder=folder-protokolle',
      })

      expect(await screen.findByText('Interner Serverfehler')).toBeInTheDocument()
      expect(mockGetLibraryDocuments).toHaveBeenCalledTimes(1)
      expect(screen.queryByText(/der ordner wurde nicht gefunden/i)).not.toBeInTheDocument()
    })

    it('creates a new folder via the dialog and reloads the current view', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      mockCreateLibraryFolder.mockResolvedValueOnce({
        id: 'folder-new',
        libraryId: 'library-mine',
        parentFolderId: null,
        name: 'Archiv',
        documentCount: 0,
        createdAt: '2026-03-01T10:00:00Z',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], { folders: [{ id: 'folder-new', name: 'Archiv', documentCount: 0 }] }),
      )

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /neuer ordner/i }))
      await user.type(await screen.findByLabelText(/ordnername/i), 'Archiv')
      await user.click(screen.getByRole('button', { name: /^anlegen$/i }))

      expect(
        await screen.findByRole('button', { name: /ordner archiv öffnen/i }),
      ).toBeInTheDocument()
      expect(mockCreateLibraryFolder).toHaveBeenCalledWith('library-mine', {
        name: 'Archiv',
        parentFolderId: null,
      })
    })

    it('shows a 409 name conflict inside the dialog without closing it', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      mockCreateLibraryFolder.mockRejectedValueOnce(
        new Error('Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene'),
      )

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /neuer ordner/i }))
      await user.type(await screen.findByLabelText(/ordnername/i), 'Protokolle')
      await user.click(screen.getByRole('button', { name: /^anlegen$/i }))

      expect(await screen.findByText(/existiert bereits auf dieser ebene/i)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /^anlegen$/i })).toBeInTheDocument()
    })

    it('renames a folder via its context menu', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folders: [{ id: 'folder-protokolle', name: 'Protokolle', documentCount: 0 }],
        }),
      )
      mockRenameLibraryFolder.mockResolvedValueOnce({
        id: 'folder-protokolle',
        libraryId: 'library-mine',
        parentFolderId: null,
        name: 'Protokolle 2026',
        documentCount: 0,
        createdAt: '2026-03-01T10:00:00Z',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folders: [{ id: 'folder-protokolle', name: 'Protokolle 2026', documentCount: 0 }],
        }),
      )

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(
        await screen.findByRole('button', { name: /optionen für ordner protokolle/i }),
      )
      await user.click(await screen.findByRole('menuitem', { name: /umbenennen/i }))
      const nameField = await screen.findByLabelText(/ordnername/i)
      await user.clear(nameField)
      await user.type(nameField, 'Protokolle 2026')
      await user.click(screen.getByRole('button', { name: /^umbenennen$/i }))

      expect(
        await screen.findByRole('button', { name: /ordner protokolle 2026 öffnen/i }),
      ).toBeInTheDocument()
      expect(mockRenameLibraryFolder).toHaveBeenCalledWith('library-mine', 'folder-protokolle', {
        name: 'Protokolle 2026',
      })
    })

    it('deletes a folder after a confirmation naming its live (re-fetched) document count', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        // #822 review, finding 4: the row's own count (5, from the last-loaded list) is
        // deliberately stale here - GET .../folders/{id} below answers with a different number
        // (7) that the caller cannot know without a live re-fetch, proving the confirmation names
        // that fresh count rather than reusing whatever the list happened to show.
        pageOf([], {
          folders: [{ id: 'folder-protokolle', name: 'Protokolle', documentCount: 5 }],
        }),
      )
      // getLibraryFolder is not mocked in this file's services/api override (see the vi.mock
      // above) - it goes through the real implementation, intercepted by MSW's `server`
      // (mocks/handlers.ts), so this overrides just that one response for the test.
      server.use(
        http.get('/api/v1/libraries/library-mine/folders/folder-protokolle', () =>
          HttpResponse.json({
            id: 'folder-protokolle',
            libraryId: 'library-mine',
            parentFolderId: null,
            name: 'Protokolle',
            documentCount: 7,
            createdAt: '2026-03-01T10:00:00Z',
          }),
        ),
      )
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
      mockDeleteLibraryFolder.mockResolvedValueOnce(undefined)
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(
        await screen.findByRole('button', { name: /optionen für ordner protokolle/i }),
      )
      await user.click(await screen.findByRole('menuitem', { name: /löschen/i }))

      await waitFor(() => {
        expect(confirmSpy).toHaveBeenCalledWith(
          expect.stringContaining('Ordner "Protokolle" und 7 Dokumente löschen?'),
        )
      })
      expect(mockDeleteLibraryFolder).toHaveBeenCalledWith('library-mine', 'folder-protokolle')
      await waitFor(() => {
        expect(
          screen.queryByRole('button', { name: /ordner protokolle öffnen/i }),
        ).not.toBeInTheDocument()
      })
    })

    it('falls back to the list count when the live re-fetch itself fails', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folders: [{ id: 'folder-protokolle', name: 'Protokolle', documentCount: 3 }],
        }),
      )
      server.use(
        http.get('/api/v1/libraries/library-mine/folders/folder-protokolle', () =>
          HttpResponse.json({ error: 'Interner Serverfehler' }, { status: 500 }),
        ),
      )
      const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(
        await screen.findByRole('button', { name: /optionen für ordner protokolle/i }),
      )
      await user.click(await screen.findByRole('menuitem', { name: /löschen/i }))

      await waitFor(() => {
        expect(confirmSpy).toHaveBeenCalledWith(
          expect.stringContaining('Ordner "Protokolle" und 3 Dokumente löschen?'),
        )
      })
      expect(mockDeleteLibraryFolder).not.toHaveBeenCalled()
    })

    it('loads a new file into the currently open folder', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folderId: 'folder-protokolle',
          breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
        }),
      )
      mockUploadDocument.mockResolvedValueOnce({
        id: 'document-new',
        fileName: 'protokoll.pdf',
        contentType: 'application/pdf',
        fileSize: 100,
        status: 'PENDING',
        sourceType: 'UPLOAD',
        chunkCount: 0,
        indexedAt: null,
        uploadedByUserId: 'mock-user-id',
        folderId: 'folder-protokolle',
        folderPath: 'Protokolle',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf(
          [
            {
              id: 'document-new',
              fileName: 'protokoll.pdf',
              contentType: 'application/pdf',
              fileSize: 100,
              status: 'PENDING',
              sourceType: 'UPLOAD',
              chunkCount: 0,
              indexedAt: null,
              uploadedByUserId: 'mock-user-id',
              folderId: 'folder-protokolle',
              folderPath: 'Protokolle',
            },
          ],
          {
            folderId: 'folder-protokolle',
            breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
          },
        ),
      )

      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?folder=folder-protokolle',
      })
      const user = userEvent.setup()

      const file = new File(['Inhalt'], 'protokoll.pdf', { type: 'application/pdf' })
      const input = await screen.findByLabelText(/dateien auswählen/i, { selector: 'input' })
      await user.upload(input, file)

      expect(await screen.findByText('protokoll.pdf')).toBeInTheDocument()
      expect(mockUploadDocument).toHaveBeenCalledWith(
        'library-mine',
        file,
        'folder-protokolle',
        undefined,
      )
    })

    // #823: "Ordner hochladen" - the webkitdirectory file input, whose selected files carry the
    // full relative path within the selected directory as File.webkitRelativePath.
    it('uploads a folder selected via the "Ordner hochladen" input with its relative path', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      mockUploadDocument.mockResolvedValueOnce({
        id: 'document-new',
        fileName: 'januar.pdf',
        contentType: 'application/pdf',
        fileSize: 100,
        status: 'PENDING',
        sourceType: 'UPLOAD',
        chunkCount: 0,
        indexedAt: null,
        uploadedByUserId: 'mock-user-id',
        folderId: 'folder-new',
        folderPath: 'Protokolle/2026',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const file = new File(['Inhalt'], 'januar.pdf', { type: 'application/pdf' })
      Object.defineProperty(file, 'webkitRelativePath', {
        value: 'Protokolle/2026/januar.pdf',
      })
      const input = await screen.findByLabelText(/ordner auswählen/i, { selector: 'input' })
      await user.upload(input, file)

      await waitFor(() => {
        expect(mockUploadDocument).toHaveBeenCalledWith(
          'library-mine',
          file,
          null,
          'Protokolle/2026',
        )
      })
    })

    // #823: a whole folder dragged and dropped - DataTransferItem.webkitGetAsEntry() must be
    // resolved recursively (see utils/directoryEntries.test.ts for the recursive resolution logic
    // itself); this exercises only the wiring between the drop handler and uploadNewDocument.
    it("uploads a dragged-and-dropped folder tree with each file's own relative path", async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      mockUploadDocument.mockResolvedValueOnce({
        id: 'document-new',
        fileName: 'januar.pdf',
        contentType: 'application/pdf',
        fileSize: 100,
        status: 'PENDING',
        sourceType: 'UPLOAD',
        chunkCount: 0,
        indexedAt: null,
        uploadedByUserId: 'mock-user-id',
        folderId: 'folder-new',
        folderPath: 'Protokolle',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      const file = new File(['Inhalt'], 'januar.pdf', { type: 'application/pdf' })
      const fileEntry = {
        isFile: true,
        isDirectory: false,
        name: 'januar.pdf',
        file: (successCallback: (f: File) => void) => successCallback(file),
      }
      // readEntries answers its one batch on the first call and empty from then on, matching the
      // "keep calling until empty" shape resolveDroppedItems (utils/directoryEntries.ts) expects.
      let readCount = 0
      const folderEntry = {
        isFile: false,
        isDirectory: true,
        name: 'Protokolle',
        createReader: () => ({
          readEntries: (callback: (entries: unknown[]) => void) => {
            readCount += 1
            callback(readCount === 1 ? [fileEntry] : [])
          },
        }),
      }

      const dropZone = await screen.findByLabelText(/dateien oder ordner hierher ziehen/i)
      fireEvent.drop(dropZone, {
        dataTransfer: {
          items: [{ kind: 'file', webkitGetAsEntry: () => folderEntry }],
          files: [],
        },
      })

      await waitFor(() => {
        expect(mockUploadDocument).toHaveBeenCalledWith('library-mine', file, null, 'Protokolle')
      })
    })

    // #823 review, Befund 2: a real OS folder routinely carries files nobody dragged there on
    // purpose - an unsupported format must be filtered client-side before ever reaching the
    // backend, and reported as one collective summary instead of a per-file error.
    it('filters an unsupported format out of a dropped folder and reports one summary message', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      mockUploadDocument.mockResolvedValueOnce({
        id: 'document-new',
        fileName: 'januar.pdf',
        contentType: 'application/pdf',
        fileSize: 100,
        status: 'PENDING',
        sourceType: 'UPLOAD',
        chunkCount: 0,
        indexedAt: null,
        uploadedByUserId: 'mock-user-id',
        folderId: 'folder-new',
        folderPath: 'Protokolle',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      const acceptedFile = new File(['Inhalt'], 'januar.pdf', { type: 'application/pdf' })
      const rejectedFile = new File(['Inhalt'], 'foto.jpg', { type: 'image/jpeg' })
      const acceptedEntry = {
        isFile: true,
        isDirectory: false,
        name: 'januar.pdf',
        file: (successCallback: (f: File) => void) => successCallback(acceptedFile),
      }
      const rejectedEntry = {
        isFile: true,
        isDirectory: false,
        name: 'foto.jpg',
        file: (successCallback: (f: File) => void) => successCallback(rejectedFile),
      }
      let readCount = 0
      const folderEntry = {
        isFile: false,
        isDirectory: true,
        name: 'Protokolle',
        createReader: () => ({
          readEntries: (callback: (entries: unknown[]) => void) => {
            readCount += 1
            callback(readCount === 1 ? [acceptedEntry, rejectedEntry] : [])
          },
        }),
      }

      const dropZone = await screen.findByLabelText(/dateien oder ordner hierher ziehen/i)
      fireEvent.drop(dropZone, {
        dataTransfer: {
          items: [{ kind: 'file', webkitGetAsEntry: () => folderEntry }],
          files: [],
        },
      })

      await waitFor(() => {
        expect(mockUploadDocument).toHaveBeenCalledWith(
          'library-mine',
          acceptedFile,
          null,
          'Protokolle',
        )
      })
      expect(mockUploadDocument).not.toHaveBeenCalledWith(
        'library-mine',
        rejectedFile,
        expect.anything(),
        expect.anything(),
      )
      expect(
        await screen.findByText(/1 datei wurde wegen eines nicht unterstützten formats/i),
      ).toBeInTheDocument()
    })

    // #823 review, Befund 2: Thumbs.db/.DS_Store/desktop.ini are dropped silently, without being
    // named in (or even counted towards) the skipped-files summary.
    it('drops Thumbs.db silently from a dropped folder without a summary message', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      mockUploadDocument.mockResolvedValueOnce({
        id: 'document-new',
        fileName: 'januar.pdf',
        contentType: 'application/pdf',
        fileSize: 100,
        status: 'PENDING',
        sourceType: 'UPLOAD',
        chunkCount: 0,
        indexedAt: null,
        uploadedByUserId: 'mock-user-id',
        folderId: 'folder-new',
        folderPath: 'Protokolle',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      const acceptedFile = new File(['Inhalt'], 'januar.pdf', { type: 'application/pdf' })
      const systemFile = new File(['x'], 'Thumbs.db')
      const acceptedEntry = {
        isFile: true,
        isDirectory: false,
        name: 'januar.pdf',
        file: (successCallback: (f: File) => void) => successCallback(acceptedFile),
      }
      const systemEntry = {
        isFile: true,
        isDirectory: false,
        name: 'Thumbs.db',
        file: (successCallback: (f: File) => void) => successCallback(systemFile),
      }
      let readCount = 0
      const folderEntry = {
        isFile: false,
        isDirectory: true,
        name: 'Protokolle',
        createReader: () => ({
          readEntries: (callback: (entries: unknown[]) => void) => {
            readCount += 1
            callback(readCount === 1 ? [acceptedEntry, systemEntry] : [])
          },
        }),
      }

      const dropZone = await screen.findByLabelText(/dateien oder ordner hierher ziehen/i)
      fireEvent.drop(dropZone, {
        dataTransfer: {
          items: [{ kind: 'file', webkitGetAsEntry: () => folderEntry }],
          files: [],
        },
      })

      await waitFor(() => {
        expect(mockUploadDocument).toHaveBeenCalledWith(
          'library-mine',
          acceptedFile,
          null,
          'Protokolle',
        )
      })
      expect(screen.queryByText(/übersprungen/i)).not.toBeInTheDocument()
    })

    // #823 review, Befund 3: an unreadable file inside a dropped folder must not abort the whole
    // drop - the rest of the folder still uploads, and the failure is reported collectively.
    it('reports an unreadable file inside a dropped folder without blocking the rest', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      mockUploadDocument.mockResolvedValueOnce({
        id: 'document-new',
        fileName: 'gut.pdf',
        contentType: 'application/pdf',
        fileSize: 100,
        status: 'PENDING',
        sourceType: 'UPLOAD',
        chunkCount: 0,
        indexedAt: null,
        uploadedByUserId: 'mock-user-id',
        folderId: 'folder-new',
        folderPath: 'Protokolle',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      const goodFile = new File(['Inhalt'], 'gut.pdf', { type: 'application/pdf' })
      const goodEntry = {
        isFile: true,
        isDirectory: false,
        name: 'gut.pdf',
        file: (successCallback: (f: File) => void) => successCallback(goodFile),
      }
      const unreadableEntry = {
        isFile: true,
        isDirectory: false,
        name: 'kaputt.pdf',
        file: (_success: (f: File) => void, errorCallback?: (error: unknown) => void) =>
          errorCallback?.(new Error('NotReadableError')),
      }
      let readCount = 0
      const folderEntry = {
        isFile: false,
        isDirectory: true,
        name: 'Protokolle',
        createReader: () => ({
          readEntries: (callback: (entries: unknown[]) => void) => {
            readCount += 1
            callback(readCount === 1 ? [unreadableEntry, goodEntry] : [])
          },
        }),
      }

      const dropZone = await screen.findByLabelText(/dateien oder ordner hierher ziehen/i)
      fireEvent.drop(dropZone, {
        dataTransfer: {
          items: [{ kind: 'file', webkitGetAsEntry: () => folderEntry }],
          files: [],
        },
      })

      await waitFor(() => {
        expect(mockUploadDocument).toHaveBeenCalledWith(
          'library-mine',
          goodFile,
          null,
          'Protokolle',
        )
      })
      expect(await screen.findByText(/1 datei konnte nicht gelesen werden/i)).toBeInTheDocument()
    })

    it("shows a search hit's folder path and navigates into it on click", async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await screen.findByText(/es sind noch keine dokumente vorhanden/i)

      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'document-hit',
            fileName: 'protokoll-2026-01.pdf',
            contentType: 'application/pdf',
            fileSize: 1000,
            status: 'INDEXED',
            sourceType: 'UPLOAD',
            chunkCount: 3,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: 'mock-user-id',
            folderId: 'folder-protokolle',
            folderPath: 'Protokolle',
          },
        ]),
      )
      await user.type(screen.getByLabelText(/dokumente durchsuchen/i), 'protokoll')

      expect(await screen.findByText('protokoll-2026-01.pdf')).toBeInTheDocument()
      const folderLink = screen.getByRole('button', { name: 'Protokolle' })

      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folderId: 'folder-protokolle',
          breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
        }),
      )
      await user.click(folderLink)

      const breadcrumbNav = await screen.findByRole('navigation', { name: /ordnerpfad/i })
      expect(within(breadcrumbNav).getByText('Protokolle')).toBeInTheDocument()
      expect(screen.getByLabelText(/dokumente durchsuchen/i)).toHaveValue('')
    })

    // #822 review, finding 1: navigateToFolder only reacted to a change of the URL's own folder
    // param - a search hit's folderPath link pointing at the folder already open left the URL (and
    // therefore the load effect) untouched, so the stale bibliotheksweit search results kept
    // showing despite the now-empty search field.
    it('reloads when a search hit points at the folder that is already open', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folderId: 'folder-protokolle',
          breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
        }),
      )
      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?folder=folder-protokolle',
      })
      const user = userEvent.setup()

      await screen.findByRole('navigation', { name: /ordnerpfad/i })

      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'document-hit',
            fileName: 'protokoll-2026-01.pdf',
            contentType: 'application/pdf',
            fileSize: 1000,
            status: 'INDEXED',
            sourceType: 'UPLOAD',
            chunkCount: 3,
            indexedAt: '2026-03-01T10:00:00Z',
            uploadedByUserId: 'mock-user-id',
            folderId: 'folder-protokolle',
            folderPath: 'Protokolle',
          },
        ]),
      )
      await user.type(screen.getByLabelText(/dokumente durchsuchen/i), 'protokoll')
      expect(await screen.findByText('protokoll-2026-01.pdf')).toBeInTheDocument()

      const folderLink = screen.getByRole('button', { name: 'Protokolle' })
      mockGetLibraryDocuments.mockClear()
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folderId: 'folder-protokolle',
          breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
        }),
      )
      await user.click(folderLink)

      await waitFor(() => {
        expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-mine', {
          page: 0,
          size: 20,
          q: '',
          folderId: 'folder-protokolle',
        })
      })
      expect(screen.queryByText('protokoll-2026-01.pdf')).not.toBeInTheDocument()
    })

    // #822 review, finding 3: cancelling a dialog that just showed a 409 conflict left
    // documentStore.folderError set - the page-level Alert (hidden while a folder dialog is open)
    // would then flash that same message once the dialog was gone.
    it('clears the page-level folderError alert when a dialog with a 409 is cancelled', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
      mockCreateLibraryFolder.mockRejectedValueOnce(
        new Error('Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene'),
      )

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /neuer ordner/i }))
      await user.type(await screen.findByLabelText(/ordnername/i), 'Protokolle')
      await user.click(screen.getByRole('button', { name: /^anlegen$/i }))
      expect(await screen.findByText(/existiert bereits auf dieser ebene/i)).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /abbrechen/i }))

      expect(screen.queryByText(/existiert bereits auf dieser ebene/i)).not.toBeInTheDocument()
    })

    // #822 review, finding 6a: folders/breadcrumb are not paged - the backend returns the same
    // full set of direct subfolders on every page of a folder-scoped GET .../documents response,
    // so rendering them again on page 2+ would just repeat the same rows pointlessly.
    it('hides folder rows on pages beyond the first', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf(
          [
            {
              id: 'doc-1',
              fileName: 'a.pdf',
              contentType: 'application/pdf',
              fileSize: 100,
              status: 'INDEXED',
              sourceType: 'UPLOAD',
              chunkCount: 1,
              indexedAt: '2026-03-01T10:00:00Z',
              uploadedByUserId: 'u1',
            },
          ],
          {
            page: 1,
            size: 1,
            totalElements: 3,
            folders: [{ id: 'folder-protokolle', name: 'Protokolle', documentCount: 0 }],
          },
        ),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await screen.findByText('a.pdf')
      expect(
        screen.queryByRole('button', { name: /ordner protokolle öffnen/i }),
      ).not.toBeInTheDocument()
    })

    // #822 review, finding 6c: handleCreateFolder used to derive the new folder's parent from
    // documentStore's pageStateByLibrary, which a deep link's own first load has not necessarily
    // populated yet - clicking "Neuer Ordner" in that window created the folder at the library's
    // root instead of the folder actually named in the URL.
    it("derives the new folder's parent from the URL, even before the deep-linked folder's first load resolves", async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      let resolveInitialLoad!: (value: LibraryDocumentPageResponse) => void
      const initialLoad = new Promise<LibraryDocumentPageResponse>((resolve) => {
        resolveInitialLoad = resolve
      })
      mockGetLibraryDocuments.mockReturnValueOnce(initialLoad)
      mockCreateLibraryFolder.mockResolvedValueOnce({
        id: 'folder-new',
        libraryId: 'library-mine',
        parentFolderId: 'folder-protokolle',
        name: 'Archiv',
        documentCount: 0,
        createdAt: '2026-03-01T10:00:00Z',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([], {
          folderId: 'folder-protokolle',
          breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
        }),
      )

      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?folder=folder-protokolle',
      })
      const user = userEvent.setup()

      // "Neuer Ordner" is already available - canManageFolders only depends on the library's own
      // role/sourceType, not on the document list having loaded - while the deep-linked folder's
      // first GET .../documents is still pending.
      await user.click(await screen.findByRole('button', { name: /neuer ordner/i }))
      await user.type(await screen.findByLabelText(/ordnername/i), 'Archiv')
      await user.click(screen.getByRole('button', { name: /^anlegen$/i }))

      await waitFor(() => {
        expect(mockCreateLibraryFolder).toHaveBeenCalledWith('library-mine', {
          name: 'Archiv',
          parentFolderId: 'folder-protokolle',
        })
      })

      resolveInitialLoad(
        pageOf([], {
          folderId: 'folder-protokolle',
          breadcrumb: [{ id: 'folder-protokolle', name: 'Protokolle' }],
        }),
      )
      await initialLoad
    })
  })
})
