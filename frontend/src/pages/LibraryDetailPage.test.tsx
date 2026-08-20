import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import { server } from '../mocks/server'
import LibraryDetailPage from './LibraryDetailPage'
import { useAuthStore } from '../stores/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useDocumentStore } from '../stores/documentStore'
import { useIndexingStore } from '../stores/indexingStore'
import type {
  IndexingRunListResponse,
  IndexingStatusResponse,
  LibraryDocumentPageResponse,
  LibraryDocumentResponse,
  LibraryListResponse,
  LibraryResponse,
  LibraryUpdateRequest,
} from '../types/api'

function pageOf(
  items: LibraryDocumentResponse[],
  overrides: Partial<LibraryDocumentPageResponse> = {},
): LibraryDocumentPageResponse {
  return { items, page: 0, size: 20, totalElements: items.length, ...overrides }
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
  mockTriggerIndexing,
  mockGetIndexingStatus,
} = vi.hoisted(() => ({
  mockGetLibrary: vi.fn(async (id: string) => useLibraryStore.getState().libraryDetails[id]),
  mockUpdateLibrary: vi.fn(async () => ({}) as LibraryResponse),
  mockDeleteLibrary: vi.fn(async () => undefined),
  mockGetLibraryDocuments: vi.fn(async () => pageOf([])),
  mockUploadDocument: vi.fn(),
  mockDeleteLibraryDocument: vi.fn(async () => undefined),
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
    expect(mockUploadDocument).toHaveBeenCalledWith('library-team', file)
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
    expect(
      await screen.findByRole('button', { name: /^quellkonfiguration bearbeiten$/i }),
    ).toBeInTheDocument()
    unmount()

    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByText(/quellkonfiguration/i)
    expect(
      screen.queryByRole('button', { name: /^quellkonfiguration bearbeiten$/i }),
    ).not.toBeInTheDocument()
  })

  // #507: the backend now only serves sourcePath/sourceUrl/sourceProxy/sourceInsecureSsl/
  // sourceCredentialsSet to a caller with at least MANAGER - a VIEWER's library object simply
  // carries none of them. This test still passes sourcePath explicitly in the VIEWER case to
  // prove the frontend itself withholds the display rather than merely reflecting an already
  // absent field.
  it('shows the source configuration detail for a MANAGER but hides it behind an info hint for a VIEWER', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByText('/data/dokumente')).toBeInTheDocument()
    expect(screen.queryByText(/verbindungsdaten sind nur für verwaltende/i)).not.toBeInTheDocument()
    unmount()

    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(
      await screen.findByText(/verbindungsdaten sind nur für verwaltende/i),
    ).toBeInTheDocument()
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

    await screen.findByText(/dateien hierher ziehen/i)
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

    await screen.findByText(/quellkonfiguration/i)
    expect(screen.queryByText(/letzte indizierungsläufe/i)).not.toBeInTheDocument()
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
    expect(screen.getByRole('button', { name: 'Go to page 2' })).toBeInTheDocument()
  })
})
