import { AxiosError } from 'axios'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { answerConfirm, renderWithProviders } from '@/test/test-utils'
import { server } from '@/test/server'
import LibraryDetailPage from './LibraryDetailPage'
import { useAuthStore } from '@/shared/session/authStore'
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
} from '@/shared/api/types'
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

// Mirrors documentStore.test.ts's own notFoundError - normalizeError
// (shared/api/api.ts) attaches the original AxiosError as `cause`, and only an actual 404 status may
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
  mockGetDocumentMetadata,
  mockBulkSetDocumentMetadata,
  mockGetDocumentTypeVocabulary,
  mockSetDocumentMetadataValue,
  mockBulkDeleteLibraryDocuments,
  mockTestLibrarySource,
} = vi.hoisted(() => ({
  mockBulkDeleteLibraryDocuments: vi.fn(async (_libraryId: string, documentIds: string[]) => ({
    deletedDocumentIds: documentIds,
    failures: [] as { documentId: string; message: string }[],
  })),
  mockTestLibrarySource: vi.fn(async () => ({
    reachable: true,
    message: 'Verzeichnis erreichbar, 3 unterstützte Dokumente gefunden.',
  })),
  mockSetDocumentMetadataValue: vi.fn(async () => ({
    fieldKey: 'document_type',
    label: 'Dokumentart',
    state: 'NOT_DETERMINABLE',
    origin: 'MANUAL',
    actorDisplayName: 'Max Mustermann',
    updatedAt: '2026-09-04T10:00:00Z',
  })),
  mockGetDocumentMetadata: vi.fn(async (_libraryId: string, documentId: string) => ({
    documentId,
    fields: [
      { fieldKey: 'title', label: 'Titel' },
      {
        fieldKey: 'document_type',
        label: 'Dokumentart',
        value: 'VERMERK',
        displayValue: 'Vermerk',
        origin: 'DERIVED',
        confidence: 0.7,
      },
      { fieldKey: 'document_date', label: 'Datum/Stand' },
    ],
  })),
  mockBulkSetDocumentMetadata: vi.fn(async () => ({
    updatedCount: 2,
    unchangedCount: 0,
    rejectedDocumentIds: [],
    correlationRef: 'metadata-bulk-1',
  })),
  mockGetDocumentTypeVocabulary: vi.fn(async () => ({
    items: [{ code: 'VERMERK', label: 'Vermerk' }],
  })),
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

vi.mock('../api/libraryApi', async () => {
  const actual = await vi.importActual<typeof import('../api/libraryApi')>('../api/libraryApi')
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
    bulkDeleteLibraryDocuments: mockBulkDeleteLibraryDocuments,
    testLibrarySource: mockTestLibrarySource,
  }
})

vi.mock('../api/indexingApi', async () => {
  const actual = await vi.importActual<typeof import('../api/indexingApi')>('../api/indexingApi')
  return {
    ...actual,
    triggerIndexing: mockTriggerIndexing,
    getIndexingStatus: mockGetIndexingStatus,
  }
})

vi.mock('../api/libraryMetadataApi', async () => {
  const actual = await vi.importActual<typeof import('../api/libraryMetadataApi')>(
    '../api/libraryMetadataApi',
  )
  return {
    ...actual,
    getDocumentMetadata: mockGetDocumentMetadata,
    bulkSetDocumentMetadata: mockBulkSetDocumentMetadata,
    getDocumentTypeVocabulary: mockGetDocumentTypeVocabulary,
    setDocumentMetadataValue: mockSetDocumentMetadataValue,
  }
})

// The "Original öffnen" action delegates to this shared module (see its own tests for
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
  reach: { allAccounts: false, groupCount: 0, userCount: 1 },
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
  reach: { allAccounts: true, groupCount: 0, userCount: 1 },
  myRole: 'VIEWER',
  sourceType: 'UPLOAD',
  documentCount: 87,
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const personalLibrary: LibraryListResponse = {
  id: 'library-mine',
  name: 'Eigene Unterlagen',
  description: 'Private Dokumente',
  ownerType: 'USER',
  reach: { allAccounts: false, groupCount: 0, userCount: 1 },
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

/** The „Ändern“ link of a row of the Reiter „Quelle“; its hidden part names the row. */
function changeButton(row: 'Verbindung' | 'Umfang' | 'Zeitplan') {
  return screen.getByRole('button', { name: new RegExp(`^Ändern\\s*${row}$`) })
}

/** The rows of the run list in the Reiter „Quelle“, newest first. */
async function runRows(): Promise<HTMLElement[]> {
  const list = await screen.findByRole('list', { name: 'Läufe' })
  await waitFor(() => expect(list.querySelector(':scope > li')).not.toBeNull())
  return Array.from(list.querySelectorAll<HTMLElement>(':scope > li'))
}

/** Opens a run row; its detail appears inside the same list entry. */
async function openRun(user: ReturnType<typeof userEvent.setup>, row: HTMLElement) {
  const toggle = row.querySelector<HTMLElement>('[data-run-row]')
  expect(toggle).not.toBeNull()
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  await user.click(toggle as HTMLElement)
  expect(toggle).toHaveAttribute('aria-expanded', 'true')
}

/** The lines behind „Technische Angaben“ of an opened run. */
async function technicalDetails(user: ReturnType<typeof userEvent.setup>, row: HTMLElement) {
  const disclosure = within(row).getByRole('button', { name: 'Technische Angaben' })
  await user.click(disclosure)
  return document.getElementById(disclosure.getAttribute('aria-controls') ?? '') as HTMLElement
}

/** A plain indexed upload: a library with it is not empty, so its list and actions show. */
const someDocument: LibraryDocumentResponse = {
  id: 'document-some',
  fileName: 'vorhanden.pdf',
  contentType: 'application/pdf',
  fileSize: 100,
  status: 'INDEXED',
  sourceType: 'UPLOAD',
  chunkCount: 1,
  indexedAt: '2026-03-01T10:00:00Z',
  uploadedByUserId: 'u1',
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

  // Tab bar and header - which areas a role sees, and where edit and
  // delete are.
  describe('Kopf und Reiterrahmen (#1939)', () => {
    async function tabNames(): Promise<string[]> {
      const tabs = await screen.findAllByRole('tab')
      return tabs.map((tab) => tab.textContent ?? '')
    }

    it('shows every area to a VIEWER of an UPLOAD library, without a source area', async () => {
      setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      expect(await tabNames()).toEqual(['Dokumente', 'Metadaten', 'Freigaben', 'Zuordnungen'])
    })

    it('adds the source area for a connector library, for a VIEWER as well', async () => {
      setLibraryState(
        viewerLibrary,
        detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      expect(await tabNames()).toEqual([
        'Dokumente',
        'Quelle',
        'Metadaten',
        'Freigaben',
        'Zuordnungen',
      ])
    })

    it('shows the same five areas to a MANAGER of a connector library', async () => {
      setLibraryState(
        managerLibrary,
        detailsOf(managerLibrary, { sourceType: 'CONFLUENCE', sourceUrl: 'https://wiki.local' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      expect(await tabNames()).toEqual([
        'Dokumente',
        'Quelle',
        'Metadaten',
        'Freigaben',
        'Zuordnungen',
      ])
    })

    it('lets a VIEWER reach the Herleitung in the Freigaben area', async () => {
      setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))

      // the derivation is a row whose „Anzeigen“ opens it on demand
      expect(await screen.findByRole('heading', { name: 'Herleitung' })).toBeInTheDocument()
      const show = screen.getByRole('button', { name: /^Anzeigen\s*Herleitung$/ })
      expect(show).toHaveAttribute('aria-expanded', 'false')
      await user.click(show)
      expect(screen.getByRole('button', { name: /^Schließen\s*Herleitung$/ })).toHaveAttribute(
        'aria-expanded',
        'true',
      )
      // The associations have a tab of their own.
      expect(screen.queryByRole('heading', { name: 'Zuordnungen' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /rechte verwalten/i })).not.toBeInTheDocument()
    })

    it('lists the associations in the Zuordnungen area under „In Space verwenden"', async () => {
      setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/libraries/library-readonly?tab=zuordnungen',
      })

      expect(await screen.findByRole('tab', { selected: true })).toHaveTextContent('Zuordnungen')
      const panel = screen.getByRole('tabpanel', { name: 'Zuordnungen' })
      expect(within(panel).getByRole('heading', { name: 'Spaces' })).toBeInTheDocument()
      expect(within(panel).getByRole('button', { name: 'In Space verwenden' })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Zuordnungen' })).not.toBeInTheDocument()
    })

    // The derivation is in the same tab for both roles, but from two branches - for a
    // managing role from the sharing section, otherwise as a section of its own. Exactly one.
    it('shows the Herleitung exactly once for a MANAGER', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))

      expect(await screen.findAllByRole('heading', { name: 'Herleitung' })).toHaveLength(1)
    })

    // ADR-0038: a type the backend has a connector for but this client no form - shown as not
    // configurable, never with a Bearbeiten that would open an empty dialog.
    it('shows a source type without a registered form as not configurable', async () => {
      setLibraryState(
        managerLibrary,
        detailsOf(managerLibrary, { sourceType: 'PROBE', sourceUrl: 'https://quelle.local' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(await screen.findByRole('tab', { name: 'Quelle' }))

      expect(await screen.findByTestId('source-not-configurable')).toHaveTextContent(
        'nicht konfigurierbar',
      )
      expect(
        screen.queryByRole('button', { name: /^quellkonfiguration bearbeiten$/i }),
      ).not.toBeInTheDocument()
    })

    it('names the source type exactly once, as a badge', async () => {
      setLibraryState(
        managerLibrary,
        detailsOf(managerLibrary, { sourceType: 'CONFLUENCE', sourceUrl: 'https://wiki.local' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      expect(await screen.findAllByText('Confluence')).toHaveLength(1)
      expect(screen.queryByText(/zurück zur übersicht/i)).not.toBeInTheDocument()
    })

    it('falls back to the documents for a stale ?tab= value from before the rename', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/libraries/library-team?tab=verwaltung',
      })

      expect(await screen.findByRole('tab', { selected: true })).toHaveTextContent('Dokumente')
    })

    it('edits name and description in the head and discards the draft on Escape', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /name und beschreibung/i }))
      const nameField = await screen.findByLabelText(/name der bibliothek/i)
      expect(nameField).toHaveFocus()
      await user.type(nameField, ' (Entwurf)')
      await user.keyboard('{Escape}')

      expect(screen.queryByLabelText(/name der bibliothek/i)).not.toBeInTheDocument()
      expect(mockUpdateLibrary).not.toHaveBeenCalled()
      // accessibility.md 2.1: focus returns to the triggering element, otherwise the
      // next tab key starts again at the very top of the page.
      expect(screen.getByRole('button', { name: /name und beschreibung/i })).toHaveFocus()
    })

    it('returns the focus to the pencil after Abbrechen and after a successful save', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()
      const pencil = () => screen.getByRole('button', { name: /name und beschreibung/i })

      await user.click(await screen.findByRole('button', { name: /name und beschreibung/i }))
      await user.click(screen.getByRole('button', { name: 'Abbrechen' }))
      expect(pencil()).toHaveFocus()

      await user.click(pencil())
      await user.click(screen.getByRole('button', { name: /^speichern$/i }))
      await waitFor(() => expect(mockUpdateLibrary).toHaveBeenCalled())
      await waitFor(() => expect(pencil()).toHaveFocus())
    }, 15000)

    it('shows a rejected head save in the head itself, keeping the draft and taking the focus', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      mockUpdateLibrary.mockRejectedValueOnce(new Error('Name bereits vergeben'))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(await screen.findByRole('button', { name: /name und beschreibung/i }))
      await user.click(await screen.findByRole('button', { name: /^speichern$/i }))

      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent('Name bereits vergeben')
      // accessibility.md 2.4: after a submit error focus moves to the error message, and
      // the input is kept.
      expect(alert).toHaveFocus()
      expect(screen.getByLabelText(/name der bibliothek/i)).toBeInTheDocument()
    })
  })

  it('shows neither edit nor delete controls for a VIEWER', async () => {
    setLibraryState(viewerLibrary, detailsOf(viewerLibrary, { documentCount: 87 }))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText(/87 Dokumente/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /name und beschreibung/i })).not.toBeInTheDocument()
    // „⋯" carries „In Space verwenden" for every reading role, but not the deletion.
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /weitere aktionen/i }))
    const menu = await screen.findByRole('menu')
    expect(
      within(menu)
        .getAllByRole('menuitem')
        .map((item) => item.textContent),
    ).toEqual(['In Space verwenden'])
  })

  it('shows storage quota usage for a MANAGER but not for a VIEWER whose response omits it', async () => {
    // storageQuotaBytes/storageUsedBytes are only ever sent to a caller with at least
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
    // a figure of the head; what it counts stands in its tooltip
    const figure = await screen.findByText(/^3([.,]0)? GB von 10([.,]0)? GB$/i)
    expect(figure).toHaveAttribute('title', 'Belegtes Speicherkontingent dieser Bibliothek')
    unmount()

    setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    expect(await screen.findByText(/87 Dokumente/i)).toBeInTheDocument()
    expect(screen.queryByText(/ GB von /i)).not.toBeInTheDocument()
  })

  it('offers editing but not deleting for a MANAGER', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(
      await screen.findByRole('button', { name: /name und beschreibung/i }),
    ).toBeInTheDocument()
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /weitere aktionen/i }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).queryByRole('menuitem', { name: 'Löschen' })).not.toBeInTheDocument()
  })

  // The permissions are a list on the page instead of behind „Rechte verwalten";
  // a reading role does not see the section at all, but does see the owner.
  it('lists the grants for a MANAGER and hides the whole section from a VIEWER', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))
    expect(await screen.findByRole('heading', { name: 'Berechtigungen' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Freigeben' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /rechte verwalten/i })).not.toBeInTheDocument()
    unmount()

    setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const viewer = userEvent.setup()
    await viewer.click(await screen.findByRole('tab', { name: 'Freigaben' }))
    expect(await screen.findByRole('heading', { name: 'Eigentümer' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Berechtigungen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Eigentum übergeben' })).not.toBeInTheDocument()
  })

  it('lets a system admin edit and delete a library without an own grant', async () => {
    setSystemAdmin()
    setLibraryState(
      { ...viewerLibrary, ownerType: 'GROUP' },
      detailsOf(viewerLibrary, { ownerType: 'GROUP' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const user = userEvent.setup()
    expect(
      await screen.findByRole('button', { name: /name und beschreibung/i }),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /weitere aktionen/i }))
    expect(await screen.findByRole('menuitem', { name: 'Löschen' })).toBeInTheDocument()
    expect(screen.getByText('administrativ')).toBeInTheDocument()
  })

  // Two clear+type sequences genuinely take longer than the default 5s under full-suite CPU
  // contention.
  it('saves changed name and description without touching the reach', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /name und beschreibung/i }))
    const nameField = await screen.findByLabelText(/name der bibliothek/i)
    await user.clear(nameField)
    await user.type(nameField, 'Rechtsquellen Soziales (neu)')
    const descriptionField = screen.getByLabelText(/^beschreibung \(optional\)$/i)
    await user.clear(descriptionField)
    await user.type(descriptionField, 'Aktualisierte Beschreibung')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-team', {
        name: 'Rechtsquellen Soziales (neu)',
        description: 'Aktualisierte Beschreibung',
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

    await user.click(await screen.findByRole('button', { name: /weitere aktionen/i }))
    await user.click(await screen.findByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, 'Bibliothek "Rechtsquellen Soziales" löschen?', 'Löschen')

    await waitFor(() => {
      expect(mockDeleteLibrary).toHaveBeenCalledWith('library-team')
    })
  })

  it('warns that a connector library delete also removes its indexed documents', async () => {
    // ADR-0018 Entscheidung 5: deleting a connector library takes its bestand with it.
    const ownerLibrary = { ...managerLibrary, myRole: 'OWNER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /weitere aktionen/i }))
    await user.click(await screen.findByRole('menuitem', { name: 'Löschen' }))

    const question = 'Bibliothek "Rechtsquellen Soziales" löschen?'
    expect(await screen.findByRole('dialog', { name: question })).toHaveTextContent(
      /indizierten dokumente/i,
    )
    await answerConfirm(user, question, 'Löschen')

    await waitFor(() => {
      expect(mockDeleteLibrary).toHaveBeenCalledWith('library-team')
    })
  })

  // The Diagnosesperre is the row „Suchdiagnose“ of the Reiter „Freigaben“ - shown to whoever
  // manages the library, but only its „Entsperren“/„Sperren“ is scoped to the responsible body.
  describe('Diagnosesperre (#1257)', () => {
    async function openSharing(user: ReturnType<typeof userEvent.setup>) {
      await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))
      return screen.findByRole('region', { name: 'Suchdiagnose' })
    }

    it('shows the locked state, without a control, for a MANAGER', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary, { diagnosticsLocked: true }))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      const row = await openSharing(userEvent.setup())
      expect(row).toHaveTextContent('Gesperrt für Suchdiagnosen anderer')
      expect(within(row).queryByRole('button')).not.toBeInTheDocument()
    })

    it('shows no row to a VIEWER, who can neither set nor lift the lock', async () => {
      setLibraryState(viewerLibrary, detailsOf(viewerLibrary, { diagnosticsLocked: true }))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await userEvent.setup().click(await screen.findByRole('tab', { name: 'Freigaben' }))
      await screen.findByRole('heading', { name: 'Herleitung' })
      expect(screen.queryByRole('region', { name: 'Suchdiagnose' })).not.toBeInTheDocument()
    })

    // A system admin's myRole bypasses to OWNER unconditionally
    // (LibraryResponse#myRole) even without any grant on the library - the toggle must key off
    // the dedicated diagnosticsLockToggleable field instead, or this admin would see a button that
    // is guaranteed to fail its PUT with 403.
    it('hides the control for a system-admin OWNER bypass without an independent grant', async () => {
      const adminBypassLibrary = { ...managerLibrary, myRole: 'OWNER' as const }
      setLibraryState(
        adminBypassLibrary,
        detailsOf(adminBypassLibrary, {
          diagnosticsLocked: true,
          diagnosticsLockToggleable: false,
        }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      const row = await openSharing(userEvent.setup())
      expect(row).toHaveTextContent('Gesperrt für Suchdiagnosen anderer')
      expect(within(row).queryByRole('button')).not.toBeInTheDocument()
    })

    it('lets an OWNER lift the lock, updating the shown state', async () => {
      const ownerLibrary = { ...managerLibrary, myRole: 'OWNER' as const }
      setLibraryState(
        ownerLibrary,
        detailsOf(ownerLibrary, { diagnosticsLocked: true, diagnosticsLockToggleable: true }),
      )
      // The default handler in mocks/handlers.ts answers off mockLibraryDetails
      // (fixtures.ts), which this file's own library-team/-readonly/-mine fixtures never
      // populate - overridden here the same way the indexing/runs tests below already do for
      // the same reason.
      server.use(
        http.put('/api/v1/libraries/:libraryId/diagnostics-lock', async ({ params, request }) => {
          const body = (await request.json()) as { locked: boolean }
          return HttpResponse.json({ libraryId: String(params.libraryId), locked: body.locked })
        }),
      )
      const user = userEvent.setup()
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      const row = await openSharing(user)
      await user.click(within(row).getByRole('button', { name: /^Entsperren\s*Suchdiagnose$/ }))

      await waitFor(() => expect(row).toHaveTextContent('Nicht gesperrt'))
      expect(
        within(row).getByRole('button', { name: /^Sperren\s*Suchdiagnose$/ }),
      ).toBeInTheDocument()
    })

    it('shows the backend 403 message in German when the caller is not the responsible body', async () => {
      const ownerLibrary = { ...managerLibrary, myRole: 'OWNER' as const }
      setLibraryState(
        ownerLibrary,
        detailsOf(ownerLibrary, { diagnosticsLocked: true, diagnosticsLockToggleable: true }),
      )
      server.use(
        http.put('/api/v1/libraries/:libraryId/diagnostics-lock', () =>
          HttpResponse.json(
            {
              error:
                'Die Diagnosesperre setzt und löst nur die für die Bibliothek zuständige Stelle',
            },
            { status: 403 },
          ),
        ),
      )
      const user = userEvent.setup()
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      const row = await openSharing(user)
      await user.click(within(row).getByRole('button', { name: /^Entsperren\s*Suchdiagnose$/ }))

      expect(
        await screen.findByText(
          'Die Diagnosesperre setzt und löst nur die für die Bibliothek zuständige Stelle',
        ),
      ).toBeInTheDocument()
      // The optimistic toggle must not have gone through - the state stays locked.
      expect(row).toHaveTextContent('Gesperrt für Suchdiagnosen anderer')
    })
  })

  describe('Private Bibliothek (#2164)', () => {
    const privateLibrary: LibraryListResponse = {
      ...personalLibrary,
      id: 'library-private',
      name: 'Meine Ablage',
      sourceType: 'NEXTCLOUD',
      privateLibrary: true,
    }

    it('marks a private library in the head with the word, not only a colour', async () => {
      setLibraryState(
        privateLibrary,
        detailsOf(privateLibrary, { sourceUrl: 'https://cloud.intern.example' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      expect(await screen.findByText('Privat')).toBeVisible()
      expect(screen.getByLabelText('Private Bibliothek – nur Sie sehen sie')).toBeInTheDocument()
    })

    it('carries no mark on a shared library', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await screen.findByRole('tab', { name: 'Freigaben' })
      expect(screen.queryByText('Privat')).not.toBeInTheDocument()
    })

    // no lasting note in the tab (the head's mark says „Privat“): owner and lock rows only
    it('drops rights, external access, transfer and derivation from „Freigaben“', async () => {
      setLibraryState(
        privateLibrary,
        detailsOf(privateLibrary, {
          sourceUrl: 'https://cloud.intern.example',
          diagnosticsLocked: true,
          diagnosticsLockToggleable: false,
          allAccountsGrantAllowed: true,
        }),
      )
      const user = userEvent.setup()
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))

      expect(await screen.findByRole('heading', { name: 'Eigentümer' })).toBeInTheDocument()
      expect(screen.getByLabelText('Private Bibliothek – nur Sie sehen sie')).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Suchdiagnose' })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Berechtigungen' })).not.toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Externer Zugang' })).not.toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Herleitung' })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Übergeben/ })).not.toBeInTheDocument()
      expect(screen.queryByText(/Obergrenze \(Systemverwaltung\)/)).not.toBeInTheDocument()
    })
  })

  // The share cap control - visible only to a system administrator on a connector
  // library, never to the library's own owner/manager, who only sees its consequence (the 409
  // below).
  describe('Freigabe-Obergrenze (#797)', () => {
    it('is hidden for UPLOAD, even for a system-admin OWNER bypass', async () => {
      setSystemAdmin()
      const adminBypassLibrary = { ...managerLibrary, myRole: 'OWNER' as const }
      setLibraryState(
        adminBypassLibrary,
        detailsOf(adminBypassLibrary, { allAccountsGrantAllowed: true }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await userEvent.setup().click(await screen.findByRole('tab', { name: 'Freigaben' }))

      expect(screen.queryByText(/Obergrenze \(Systemverwaltung\)/)).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Freigabe an Alle erlaubt')).not.toBeInTheDocument()
    })

    it('is hidden from a MANAGER of a connector library, not being a system administrator', async () => {
      const managerOfFilesystem = { ...managerLibrary, myRole: 'MANAGER' as const }
      setLibraryState(
        managerOfFilesystem,
        detailsOf(managerOfFilesystem, {
          sourceType: 'FILESYSTEM',
          sourcePath: '/data/dokumente',
          allAccountsGrantAllowed: true,
        }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await userEvent.setup().click(await screen.findByRole('tab', { name: 'Freigaben' }))

      expect(screen.queryByText(/Obergrenze \(Systemverwaltung\)/)).not.toBeInTheDocument()
      expect(screen.queryByLabelText('Freigabe an Alle erlaubt')).not.toBeInTheDocument()
    })

    it('lets a system administrator lower the cap on a connector library', async () => {
      setSystemAdmin()
      const adminBypassLibrary = { ...managerLibrary, myRole: 'OWNER' as const }
      setLibraryState(
        adminBypassLibrary,
        detailsOf(adminBypassLibrary, {
          sourceType: 'FILESYSTEM',
          sourcePath: '/data/dokumente',
          reach: { allAccounts: true, groupCount: 0, userCount: 1 },
          allAccountsGrantAllowed: true,
        }),
      )
      // The button is disabled whether or not a PUT ever went out, so the request itself is
      // captured and asserted on, not just the button state afterwards.
      let putRequestBody: { allAccountsGrantAllowed: boolean } | null = null
      server.use(
        http.put('/api/v1/libraries/:libraryId/share-cap', async ({ params, request }) => {
          putRequestBody = (await request.json()) as { allAccountsGrantAllowed: boolean }
          return HttpResponse.json({
            ...detailsOf(adminBypassLibrary, {
              sourceType: 'FILESYSTEM',
              sourcePath: '/data/dokumente',
            }),
            id: String(params.libraryId),
            allAccountsGrantAllowed: putRequestBody.allAccountsGrantAllowed,
          })
        }),
      )
      const user = userEvent.setup()
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))
      await user.click(await screen.findByLabelText('Freigabe an Alle erlaubt'))
      await user.click(
        await screen.findByRole('button', {
          name: 'Obergrenze „Freigabe an Alle erlaubt“ speichern',
        }),
      )

      await waitFor(() => {
        expect(putRequestBody).toEqual({ allAccountsGrantAllowed: false })
      })
    })
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
    // the head's one action opens the file and folder pickers
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'Hochladen' }))
    expect(await screen.findByRole('menuitem', { name: 'Dateien auswählen …' })).toBeInTheDocument()
    expect(screen.getByRole('menuitem', { name: 'Ordner auswählen …' })).toBeInTheDocument()
    expect(screen.queryByText(/quellkonfiguration/i)).not.toBeInTheDocument()
  })

  it('shows the errorMessage of a FAILED document as a tooltip on its status chip', async () => {
    // A FAILED row's asynchronous processing failure is only visible to
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
    // the state column's mark carries the tooltip and takes the keyboard focus for it
    const statusMark = screen
      .getAllByText('Fehlgeschlagen')
      .map((word) => word.closest('[tabindex="0"]'))
      .find((mark) => mark !== null)
    expect(statusMark).toBeDefined()
    await user.hover(statusMark as HTMLElement)

    expect(
      await screen.findByText('Aus der Datei konnte kein Text extrahiert werden'),
    ).toBeInTheDocument()
  })

  it('uploads a file and shows it in the list afterwards', async () => {
    // Durchstich test for upload on the page. uploadNewDocument does not prepend the response
    // locally - it reloads the current page from the server, hence the second
    // mockGetLibraryDocuments answer.
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
    // uploadDocument's request-layer signature grew a trailing folderPath - undefined here,
    // a plain file-picker selection has no directory structure of its own.
    expect(mockUploadDocument).toHaveBeenCalledWith('library-team', file, null, undefined)
  })

  it('deletes a document after confirmation and removes it from the list', async () => {
    // Durchstich test for deletion with the confirmation dialog on the
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

    await screen.findByText('dienstanweisung-2024.pdf')
    await user.click(
      screen.getByRole('button', { name: 'Aktionen für Dokument dienstanweisung-2024.pdf' }),
    )
    await user.click(await screen.findByRole('menuitem', { name: 'Löschen' }))
    await answerConfirm(user, 'Dokument "dienstanweisung-2024.pdf" löschen?', 'Löschen')

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
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))

    expect(await screen.findByRole('heading', { name: 'Verbindung' })).toBeInTheDocument()
    expect(screen.getByText('/data/dokumente')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /dateien hochladen/i })).not.toBeInTheDocument()
    const triggerButton = screen.getByRole('button', { name: /jetzt indizieren/i })
    expect(triggerButton).toBeInTheDocument()

    await user.click(triggerButton)

    await waitFor(() => {
      expect(mockTriggerIndexing).toHaveBeenCalledWith('library-team')
    })
  })

  it('offers „Ändern“ for a MANAGER on a connector library but hides it for a VIEWER', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    await screen.findByRole('heading', { name: 'Verbindung' })
    expect(changeButton('Verbindung')).toBeInTheDocument()
    expect(changeButton('Zeitplan')).toBeInTheDocument()
    unmount()

    // A VIEWER has the "Quelle" area too, but it holds no source configuration - the path never
    // reaches the client.
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    expect(await screen.findByText(/Verbindung, Zeitplan und Läufe/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Ändern/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Verbindung' })).not.toBeInTheDocument()
    expect(screen.queryByText('/data/dokumente')).not.toBeInTheDocument()
  })

  it('shows an S3 library with its scopes, its sharing consequence and its stored configuration (#1377)', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'S3',
        sourceUrl: 'https://minio.intern.example:9000',
        sourceCredentialsSet: true,
        sourceSettings: {
          region: 'eu-central-1',
          pathStyle: true,
          scopes: [
            { bucket: 'protokolle', prefix: '2025/' },
            { bucket: 'satzungen', prefix: null },
          ],
          includePatterns: ['**/*.pdf'],
          excludePatterns: [],
        },
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    // the row „Umfang“ lists the scopes with one path separator and counts the patterns
    const scope = screen.getByRole('region', { name: 'Umfang' })
    expect(scope).toHaveTextContent('2 Buckets und Präfixe')
    expect(scope).toHaveTextContent('nur **/*.pdf')
    expect(scope).toHaveTextContent('protokolle / 2025')
    expect(scope).toHaveTextContent('satzungen')
    expect(screen.getByRole('region', { name: 'Verbindung' })).toHaveTextContent(
      'minio.intern.example:9000',
    )

    await user.click(changeButton('Verbindung'))
    const connection = screen.getByRole('region', { name: 'Verbindung' })
    expect(within(connection).getByLabelText('Endpoint', { selector: 'input' })).toHaveValue(
      'https://minio.intern.example:9000',
    )
    expect(within(connection).getByLabelText('Region', { selector: 'input' })).toHaveValue(
      'eu-central-1',
    )
    await user.click(within(connection).getByRole('button', { name: 'Abbrechen' }))
    await user.click(changeButton('Umfang'))
    expect(
      within(screen.getByRole('region', { name: 'Umfang' })).getByLabelText(/Einschlussmuster/, {
        selector: 'textarea',
      }),
    ).toHaveValue('**/*.pdf')
    // ADR-0027: the sharing consequence is stated permanently, for every reader
    expect(screen.getByTestId('s3-sharing-consequence')).toHaveTextContent(
      'für alle Leseberechtigten dieser Bibliothek sichtbar',
    )
  })

  it('shows a Confluence library with edition and spaces, the edition never as an input (#1135)', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceCredentialsSet: true,
        sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'BAU', name: 'Bauamt' }] },
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    // the row „Verbindung“ names host and edition, the row „Umfang“ the spaces
    expect(await screen.findByRole('region', { name: 'Verbindung' })).toHaveTextContent(
      /wiki\.behoerde\.example.*Data Center/,
    )
    expect(screen.getByRole('region', { name: 'Umfang' })).toHaveTextContent('1 SpaceBauamt')

    await user.click(changeButton('Verbindung'))
    const connection = screen.getByRole('region', { name: 'Verbindung' })
    expect(within(connection).getByLabelText('Adresse', { selector: 'input' })).toHaveValue(
      'https://wiki.behoerde.example/confluence',
    )
    // the edition is never an input here
    expect(connection).toHaveTextContent('Edition: Data Center')
    expect(within(connection).queryByRole('radio')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edition erkennen' })).not.toBeInTheDocument()
    // The sharing consequence is stated permanently, above the fold
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
        sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'ENG', name: 'Engineering' }] },
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    // the full reconciliation is the action of the section „Läufe“, „Jetzt indizieren“ stays in
    // the head
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    const runsSection = await screen.findByRole('region', { name: 'Läufe' })
    await user.click(
      await within(runsSection).findByRole('button', { name: 'Vollabgleich starten' }),
    )
    expect(mockTrigger).toHaveBeenCalledWith(ownerLibrary.id, 'CONFLUENCE', 'FULL')
    await user.click(screen.getByRole('button', { name: 'Jetzt indizieren' }))
    expect(mockTrigger).toHaveBeenLastCalledWith(ownerLibrary.id, 'CONFLUENCE')
    // no help text on what „Jetzt indizieren“ reads; the two kinds of run are its tooltip
    expect(screen.queryByText(/in der Regel nur Änderungen seit dem letzten Lauf/)).toBeNull()
    await user.hover(screen.getByRole('button', { name: 'Jetzt indizieren' }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(
      '„Jetzt indizieren“ liest Änderungen, „Vollabgleich starten“ alle Spaces neu.',
    )
    unmount()

    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByRole('button', { name: 'Jetzt indizieren' })
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    await screen.findByRole('region', { name: 'Läufe' })
    await screen.findByRole('list', { name: 'Läufe' })
    expect(screen.queryByRole('button', { name: 'Vollabgleich starten' })).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Weitere Aktionen' }))
    expect(screen.queryByRole('menuitem', { name: 'Vollabgleich starten' })).toBeNull()
  }, 15000)

  it('marks an incomplete run and shows its cost figures (#1141)', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'ENG', name: 'Engineering' }] },
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
                bytesDownloaded: 734003200,
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
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))

    // the row: an incomplete run is partly failed, its result says „unvollständig“
    const [row] = await runRows()
    expect(row).toHaveTextContent('Teilweise fehlgeschlagen')
    expect(row).toHaveTextContent('430 Dokumente · unvollständig')
    expect(row).toHaveTextContent('12 min')
    await openRun(user, row)
    expect(within(row).getByText('Anfragebudget erschöpft · 1')).toBeInTheDocument()
    const technical = await technicalDetails(user, row)
    expect(technical).toHaveTextContent('Auslöser: per Zeitplan · Vollabgleich')
    expect(technical).toHaveTextContent(
      '1.000 Anfragen an die Quelle · 2-mal gedrosselt (45 s) · 700 MB geladen',
    )
    expect(technical).toHaveTextContent('Anhänge: 30 indiziert · 5 übersprungen · 1 fehlgeschlagen')
  })

  it('shows only attachments and duration for a run of a source without a request meter', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({
          runs: [
            {
              id: 'run-filesystem',
              status: 'COMPLETED',
              triggeredBy: 'MANUAL',
              runMode: 'FULL',
              documentCount: 12,
              totalDocuments: 12,
              documentsSkipped: 0,
              documentsFailed: 0,
              documentsIndexedTotal: 15,
              message:
                'Indizierung abgeschlossen: 12 verarbeitet, 0 übersprungen, 0 fehlgeschlagen',
              startedAt: '2026-09-05T09:00:00Z',
              completedAt: '2026-09-05T09:00:42Z',
              incomplete: false,
              metrics: {
                requestsSent: 0,
                throttleCount: 0,
                throttleWaitSeconds: 0,
                attachmentsProcessed: 3,
                attachmentsSkipped: 1,
                attachmentsFailed: 0,
              },
              events: [],
              eventsTruncatedCount: 0,
            },
          ],
        } satisfies IndexingRunListResponse),
      ),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))

    const [row] = await runRows()
    expect(row).toHaveTextContent('42 s')
    await openRun(user, row)
    const technical = await technicalDetails(user, row)
    expect(technical).toHaveTextContent('Anhänge: 3 indiziert · 1 übersprungen · 0 fehlgeschlagen')
    expect(technical).not.toHaveTextContent('Anfragen an die Quelle')
    expect(technical).not.toHaveTextContent('gedrosselt')
  })

  it('names how many areas a run could not read (#2128)', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    const run: Omit<IndexingRunListResponse['runs'][number], 'id'> = {
      status: 'COMPLETED',
      triggeredBy: 'MANUAL',
      runMode: 'FULL',
      documentCount: 12,
      totalDocuments: 12,
      documentsSkipped: 0,
      documentsFailed: 0,
      documentsIndexedTotal: 12,
      startedAt: '2026-10-03T09:00:00Z',
      events: [],
      eventsTruncatedCount: 0,
    }
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({
          runs: [
            { ...run, id: 'run-two', unreadableScopeCount: 2 },
            { ...run, id: 'run-one', unreadableScopeCount: 1 },
            { ...run, id: 'run-none' },
          ],
        } satisfies IndexingRunListResponse),
      ),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))

    const [two, one, none] = await runRows()
    expect(two).toHaveTextContent('12 Dokumente · 2 nicht lesbar')
    expect(one).toHaveTextContent('12 Dokumente · 1 nicht lesbar')
    expect(none).not.toHaveTextContent('nicht lesbar')
    // without names the opened run counts the unreadable areas
    await openRun(user, two)
    expect(within(two).getByText('2 Bereiche der Quelle nicht lesbar')).toBeInTheDocument()
    await openRun(user, one)
    expect(within(one).getByText('Ein Bereich der Quelle nicht lesbar')).toBeInTheDocument()
  })

  it('shows the webhook row to a manager of a Confluence library only (#1140)', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'ENG', name: 'Engineering' }] },
        pushSecretSet: true,
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    // the row „Live-Abgleich“ names the webhook and opens its setup
    const live = await screen.findByRole('region', { name: 'Live-Abgleich' })
    expect(live).toHaveTextContent('an, über den Webhook')
    await user.click(within(live).getByRole('button', { name: /^Ändern\s*Live-Abgleich$/ }))
    expect(await screen.findByTestId('confluence-webhook-section')).toHaveTextContent(
      /Webhook:.*eingerichtet/,
    )
    expect(screen.getByRole('button', { name: 'Geheimnis neu erzeugen' })).toBeInTheDocument()
    unmount()

    // a VIEWER sees edition and spaces, but no webhook affordance
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'ENG', name: 'Engineering' }] },
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    expect(await screen.findByRole('region', { name: 'Umfang' })).toHaveTextContent('Engineering')
    expect(screen.queryByTestId('confluence-webhook-section')).not.toBeInTheDocument()
  })

  it('states the Confluence sharing consequence to a VIEWER as well, and never for other types (#1138)', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceSettings: { edition: 'CLOUD', spaces: [{ key: 'BAU', name: 'Bauamt' }] },
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Quelle' }))
    const consequence = screen.getByTestId('confluence-sharing-consequence')
    expect(consequence).toBeVisible()
    expect(consequence).toHaveTextContent(/für alle Leseberechtigten dieser Bibliothek sichtbar/)
    // the scope itself is visible to the VIEWER, the address is not
    expect(screen.getByRole('region', { name: 'Umfang' })).toHaveTextContent('Bauamt')
    expect(screen.queryByRole('region', { name: 'Verbindung' })).not.toBeInTheDocument()
    unmount()

    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'RSS_FEED', sourceUrl: 'https://example.org/feed' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Quelle' }))
    await screen.findByText(/sehen nur Verwaltende/)
    expect(screen.queryByTestId('confluence-sharing-consequence')).not.toBeInTheDocument()
  })

  it('warns every reader while the last full sync could not read a space (#1191)', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceSettings: {
          edition: 'DATA_CENTER',
          spaces: [
            { key: 'SEC', name: 'Sicherheit' },
            { key: 'ENG', name: 'Engineering' },
          ],
        },
      }),
    )
    mockGetIndexingStatus.mockResolvedValueOnce({
      status: 'COMPLETED',
      documentCount: 3,
      totalDocuments: 4,
      documentsSkipped: 0,
      documentsFailed: 0,
      documentsIndexedTotal: 3,
      message: null,
      timestamp: '2026-03-01T10:00:00Z',
      unlistedScopeKeys: ['SEC'],
    } as IndexingStatusResponse)

    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Quelle' }))

    // the row „Umfang“ marks the unreadable space for every reader, and only that one
    const scope = await screen.findByRole('region', { name: 'Umfang' })
    await waitFor(() => expect(scope).toHaveTextContent(/Sicherheit\s*nicht lesbar/))
    expect(scope).not.toHaveTextContent(/Engineering\s*nicht lesbar/)
  })

  it('names an S3 scope the last full sync could not list, for every reader (#1378)', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, {
        sourceType: 'S3',
        sourceUrl: 'https://minio.intern.example:9000',
        sourceSettings: {
          region: 'us-east-1',
          pathStyle: true,
          scopes: [
            { bucket: 'geheim', prefix: 'intern/' },
            { bucket: 'dokumente', prefix: null },
          ],
          includePatterns: [],
          excludePatterns: [],
        },
      }),
    )
    mockGetIndexingStatus.mockResolvedValueOnce({
      status: 'COMPLETED',
      documentCount: 3,
      totalDocuments: 4,
      documentsSkipped: 0,
      documentsFailed: 0,
      documentsIndexedTotal: 3,
      message: null,
      timestamp: '2026-09-06T10:00:00Z',
      unlistedScopeKeys: ['geheim/intern/'],
    } as IndexingStatusResponse)

    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Quelle' }))

    const scope = await screen.findByRole('region', { name: 'Umfang' })
    await waitFor(() => expect(scope).toHaveTextContent(/geheim \/ intern\s*nicht lesbar/))
    expect(scope).not.toHaveTextContent(/dokumente\s*nicht lesbar/)
  })

  it('shows no incomplete-listing warning while the assessment is clean (#1191)', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'ENG', name: 'Engineering' }] },
      }),
    )

    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    expect(await screen.findByRole('region', { name: 'Umfang' })).toHaveTextContent('Engineering')
    expect(screen.queryByTestId('confluence-incomplete-listing-warning')).not.toBeInTheDocument()
  })

  // The backend now only serves sourcePath/sourceUrl/
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

    // The „Quelle" tab is open to a reader as well, the source configuration is not:
    // the backend never delivers the path to a VIEWER, and the page shows
    // no placeholder for data that was never sent either.
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await screen.findByText(/87 Dokumente/i)
    expect(screen.getByRole('tab', { name: 'Quelle' })).toBeInTheDocument()
    expect(screen.queryByText('/data/dokumente')).not.toBeInTheDocument()
  })

  it('edits the row „Verbindung“ in place, resending the unrelated Stammdaten fields untouched', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    await screen.findByRole('heading', { name: 'Verbindung' })
    await user.click(changeButton('Verbindung'))
    const pathField = await screen.findByLabelText('Verzeichnis', { selector: 'input' })
    await user.clear(pathField)
    await user.type(pathField, '/data/umgezogen')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-team', {
        name: ownerLibrary.name,
        description: ownerLibrary.description,
        sourcePath: '/data/umgezogen',
        sourceUrl: undefined,
        sourceCredentials: undefined,
        sourceSettings: { excludePatterns: [] },
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
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Quelle' }))

    // the next run follows the schedule in its row, in the time zone of the browser
    const nextRun = new Date('2026-03-02T03:30:00Z').toLocaleString('de-DE', {
      day: '2-digit',
      month: '2-digit',
      year: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
    const row = await screen.findByRole('region', { name: 'Zeitplan' })
    expect(row).toHaveTextContent('täglich 03:30')
    expect(row).toHaveTextContent(`nächster Lauf ${nextRun}`)
  })

  it('shows no banner when the last two scheduled runs failed; the runs themselves say so', async () => {
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
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Quelle' }))

    expect(await screen.findByRole('region', { name: 'Zeitplan' })).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Läufe' })).toBeInTheDocument()
    expect(screen.queryByText(/geplanten läufe/i)).not.toBeInTheDocument()
    expect(screen.queryByText(/versucht es .* erneut/i)).not.toBeInTheDocument()
  })

  it('edits the row „Zeitplan“ in place, resending the unrelated Stammdaten fields untouched', async () => {
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

    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    await screen.findByRole('heading', { name: 'Zeitplan' })
    await user.click(changeButton('Zeitplan'))
    await user.click(screen.getByRole('combobox', { name: /^häufigkeit$/i }))
    await user.click(await screen.findByRole('option', { name: 'Stündlich' }))
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith('library-team', {
        name: ownerLibrary.name,
        description: ownerLibrary.description,
        schedule: {
          frequency: 'HOURLY',
          hour: null,
          minute: null,
          weekday: undefined,
        },
      } satisfies LibraryUpdateRequest)
    })
  })

  it('shows the full-sync rhythm of a Confluence library and lets a MANAGER lengthen it (#1200)', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceSettings: {
          edition: 'DATA_CENTER',
          spaces: [{ key: 'ENG', name: 'Engineering' }],
          fullSyncIntervalDays: null,
        },
        fullSyncIntervalDefaultDays: 7,
        schedule: { frequency: 'HOURLY', nextRunAt: '2026-03-02T03:00:00Z' },
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    expect(await screen.findByRole('region', { name: 'Zeitplan' })).toHaveTextContent(
      'Vollabgleich alle 7 Tage',
    )

    await user.click(changeButton('Zeitplan'))
    const daysField = await screen.findByLabelText('Vollabgleich alle … Tage')
    await user.type(daysField, '14')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith(
        'library-team',
        expect.objectContaining({ sourceSettings: { fullSyncIntervalDays: 14 } }),
      )
    })
  })

  it('returns the rhythm to the instance default when the field is cleared (#1200)', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'CONFLUENCE',
        sourceUrl: 'https://wiki.behoerde.example/confluence',
        sourceSettings: {
          edition: 'DATA_CENTER',
          spaces: [{ key: 'ENG', name: 'Engineering' }],
          fullSyncIntervalDays: 30,
        },
        fullSyncIntervalDefaultDays: 7,
        schedule: { frequency: 'HOURLY', nextRunAt: '2026-03-02T03:00:00Z' },
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    expect(await screen.findByRole('region', { name: 'Zeitplan' })).toHaveTextContent(
      'Vollabgleich alle 30 Tage',
    )

    await user.click(changeButton('Zeitplan'))
    const daysField = await screen.findByLabelText('Vollabgleich alle … Tage')
    await user.clear(daysField)
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith(
        'library-team',
        expect.objectContaining({ sourceSettings: { fullSyncIntervalDays: 0 } }),
      )
    })
  })

  it('resends the whole Google Drive settings with a changed rhythm (ADR-0040)', async () => {
    const ownerLibrary = { ...managerLibrary, myRole: 'MANAGER' as const }
    const settings = {
      scopes: [{ folder: 'f1', name: 'Akten' }],
      subject: 'fach@example.org',
      fullSyncIntervalDays: null,
    }
    setLibraryState(
      ownerLibrary,
      detailsOf(ownerLibrary, {
        sourceType: 'GOOGLE_DRIVE',
        sourceUrl: 'https://www.googleapis.com',
        sourceSettings: settings,
        fullSyncIntervalDefaultDays: 7,
        schedule: { frequency: 'HOURLY', nextRunAt: '2026-03-02T03:00:00Z' },
      }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    await screen.findByRole('heading', { name: 'Zeitplan' })
    await user.click(changeButton('Zeitplan'))
    await user.type(await screen.findByLabelText('Vollabgleich alle … Tage'), '14')
    await user.click(screen.getByRole('button', { name: /^speichern$/i }))

    await waitFor(() => {
      expect(mockUpdateLibrary).toHaveBeenCalledWith(
        'library-team',
        expect.objectContaining({ sourceSettings: { ...settings, fullSyncIntervalDays: 14 } }),
      )
    })
  })

  /** One completed run of the run history, with the given counts. */
  function serveCompletedRun(counts: {
    documentCount: number
    totalDocuments: number
    documentsSkipped: number
    documentsIndexedTotal: number
  }) {
    server.use(
      http.get('/api/v1/libraries/:libraryId/indexing/runs', () =>
        HttpResponse.json({
          runs: [
            {
              id: 'run-done',
              status: 'COMPLETED',
              triggeredBy: 'MANUAL',
              runMode: 'FULL',
              documentsFailed: 0,
              message: null,
              startedAt: '2026-03-01T09:00:00Z',
              completedAt: '2026-03-01T09:01:00Z',
              events: [],
              eventsTruncatedCount: 0,
              ...counts,
            },
          ],
        } satisfies IndexingRunListResponse),
      ),
    )
  }

  it('shows the plain document wording for a completed FILESYSTEM run', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    serveCompletedRun({
      documentCount: 10,
      totalDocuments: 12,
      documentsSkipped: 2,
      documentsIndexedTotal: 10,
    })
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))

    const [row] = await runRows()
    expect(row).toHaveTextContent('Abgeschlossen')
    expect(row).toHaveTextContent('10 Dokumente')
    await openRun(user, row)
    expect(await technicalDetails(user, row)).toHaveTextContent('Verarbeitet: 10 · übersprungen: 2')
    expect(screen.queryByText(/feed-einträge/i)).not.toBeInTheDocument()
  })

  it('shows an unknown run status with a retry instead of a failed run (#2908)', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    await runRows()

    act(() => {
      useIndexingStore.setState({
        runsByLibrary: {
          [managerLibrary.id]: {
            ...IDLE_RUN_STATE,
            status: 'RUNNING',
            sourceType: 'FILESYSTEM',
            statusUnknown: true,
          },
        },
      })
    })

    // the run still runs: the head says so, without a percent and without a failure
    const state = screen.getByTestId('library-state')
    await waitFor(() => expect(state).toHaveTextContent(/^Läuft\s*· Stand unbekannt$/))
    expect(state).toHaveAttribute('role', 'status')
    expect(state).not.toHaveTextContent(/Fehlgeschlagen/)
    expect(
      screen.queryByText(/Indizierungsstatus konnte nicht abgerufen werden/),
    ).not.toBeInTheDocument()
    const statusCallsBefore = mockGetIndexingStatus.mock.calls.length

    const runs = screen.getByRole('region', { name: 'Läufe' })
    await user.click(within(runs).getByRole('button', { name: 'Stand erneut abrufen' }))

    await waitFor(() => {
      expect(mockGetIndexingStatus.mock.calls.length).toBeGreaterThan(statusCallsBefore)
    })
    await waitFor(() => expect(state).not.toHaveTextContent('Stand unbekannt'))
  })

  it('shows a hint with a reload once the document polling stopped (#2908)', async () => {
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await waitFor(() => expect(mockGetLibraryDocuments).toHaveBeenCalled())
    const loadsBefore = mockGetLibraryDocuments.mock.calls.length

    act(() => {
      useDocumentStore.setState({
        pollingStoppedMessage:
          'Die Dokumentliste wird nicht mehr automatisch aktualisiert, weil der Abruf fehlgeschlagen ist.',
      })
    })

    const hint = await screen.findByText(/nicht mehr automatisch aktualisiert/)
    expect(hint.closest('[role="status"]')).not.toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Aktualisieren' }))

    await waitFor(() => {
      expect(mockGetLibraryDocuments.mock.calls.length).toBeGreaterThan(loadsBefore)
    })
    await waitFor(() => {
      expect(screen.queryByText(/nicht mehr automatisch aktualisiert/)).not.toBeInTheDocument()
    })
  })

  it('reloads the document list once a run finishes, without a manual page reload', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await waitFor(() => expect(mockGetLibraryDocuments).toHaveBeenCalled())
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

  // The run history section shows past runs' header data up front and reveals each run's
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
    await userEvent.setup().click(await screen.findByRole('tab', { name: 'Quelle' }))

    const [row] = await runRows()
    expect(row).toHaveTextContent('10 Dokumente')
    // collapsed: the protocol is not rendered until the row is opened
    expect(within(row).queryByText('bad.csv')).not.toBeInTheDocument()

    const user = userEvent.setup()
    await openRun(user, row)

    // errors grouped by reason, each Fundort on one line with its message in the tooltip
    expect(within(row).getByText(/format nicht.* · 1$/i)).toBeVisible()
    const fundort = within(row).getByText('bad.csv')
    expect(fundort).toBeVisible()
    expect(fundort.closest('li')).toHaveAttribute(
      'title',
      'bad.csv\nDateiformat wird nicht unterstützt',
    )
  })

  // ADR-0023, Entscheidung 4: a Confluence library's runs name their Betriebsart; for
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
        sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'ENG', name: 'Engineering' }] },
      }),
    )
    const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    // the column „Auslöser“ names the full reconciliation where the source knows two modes
    const [row] = await runRows()
    expect(row).toHaveTextContent('Vollabgleich')
    // A run that could not read something says so in its row, not only when expanded: its
    // state is „Teilweise fehlgeschlagen“, not a plain „Abgeschlossen“
    expect(row).toHaveTextContent('Teilweise fehlgeschlagen')
    expect(row).not.toHaveTextContent('Abgeschlossen')
    await openRun(user, row)
    expect(within(row).getByText(/abgewiesen.* · 1$|nicht lesbar.* · 1$/i)).toBeInTheDocument()
    await user.click(within(row).getByRole('button', { name: 'Hinweise · 1' }))
    expect(within(row).getByText('Ratenbegrenzung · 1')).toBeInTheDocument()
    expect(await technicalDetails(user, row)).toHaveTextContent(
      'Auslöser: manuell gestartet · Vollabgleich',
    )
    unmount()

    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    const [plain] = await runRows()
    expect(plain).toHaveTextContent('Manuell')
    expect(plain).not.toHaveTextContent('Vollabgleich')
    await openRun(user, plain)
    expect(await technicalDetails(user, plain)).toHaveTextContent(/^Auslöser: manuell gestartet/)
    expect(within(plain).queryByText(/Vollabgleich/)).not.toBeInTheDocument()
  }, 15000)

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
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
    const [row] = await runRows()
    await openRun(user, row)
    await user.click(within(row).getByRole('button', { name: 'Hinweise · 42' }))

    expect(within(row).getByText('42 weitere ohne gespeicherten Fundort')).toBeInTheDocument()
  })

  // one form for every source: a feed's run counts documents like any other, no feed entries
  it('shows the same document wording for a completed RSS_FEED run', async () => {
    setLibraryState(
      managerLibrary,
      detailsOf(managerLibrary, {
        sourceType: 'RSS_FEED',
        sourceUrl: 'https://example.gov/feed.xml',
      }),
    )
    serveCompletedRun({
      documentCount: 13,
      totalDocuments: 18,
      documentsSkipped: 5,
      documentsIndexedTotal: 13,
    })
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()
    await user.click(await screen.findByRole('tab', { name: 'Quelle' }))

    const [row] = await runRows()
    expect(row).toHaveTextContent('13 Dokumente')
    await openRun(user, row)
    expect(await technicalDetails(user, row)).toHaveTextContent('Verarbeitet: 13 · übersprungen: 5')
    expect(screen.queryByText(/feed-einträge/i)).not.toBeInTheDocument()
  })

  it('clears a stale upload error from a previously viewed library after switching', async () => {
    // uploadErrors/deleteError/error in documentStore are not keyed by
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
    expect(await screen.findByRole('alert')).toHaveTextContent(
      /Nicht hochgeladen: dublette\.pdf.*bereits vorhanden/s,
    )
    unmount()

    setLibraryState(personalLibrary, detailsOf(personalLibrary))
    mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await screen.findByText(/dateien oder ordner hierher ziehen/i)
    expect(screen.queryByText(/bereits vorhanden/i)).not.toBeInTheDocument()
  })

  it('shows the library store error alongside the loaded library when reloading its details fails', async () => {
    // Without this, a failed GET /libraries/{id} while the list entry is
    // already cached silently drops both typed sections with no explanation.
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    useLibraryStore.setState((state) => ({
      libraryDetails: {},
      error: 'Bibliotheksdetails konnten nicht geladen werden',
      libraries: state.libraries,
      isLoading: false,
    }))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText(/konnten nicht vollständig geladen werden/i)).toBeInTheDocument()
    expect(screen.getByText(managerLibrary.name)).toBeInTheDocument()
  })

  it('keeps the Quelle area and offers a reload when the details of a connector library fail', async () => {
    const user = userEvent.setup()
    const connectorLibrary: LibraryListResponse = { ...managerLibrary, sourceType: 'FILESYSTEM' }
    setLibraryState(connectorLibrary, detailsOf(connectorLibrary))
    useLibraryStore.setState((state) => ({
      libraryDetails: {},
      error: 'HTTP 502: Request failed with status code 502',
      libraries: state.libraries,
      isLoading: false,
    }))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    expect(await screen.findByText(/konnten nicht vollständig geladen werden/i)).toBeInTheDocument()
    expect(screen.queryByText(/HTTP 502/)).not.toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Quelle' })).toBeInTheDocument()

    mockGetLibrary.mockResolvedValueOnce(
      detailsOf(connectorLibrary, { sourcePath: '/data/dokumente' }),
    )
    await user.click(screen.getByRole('button', { name: 'Erneut versuchen' }))

    await waitFor(() =>
      expect(
        screen.queryByText(/konnten nicht vollständig geladen werden/i),
      ).not.toBeInTheDocument(),
    )
  })

  // The backend gates GET .../indexing/runs at MANAGER (canManage) since
  // its events routinely carry the library's own sourcePath/sourceUrl - the same internal-path
  // leak the source configuration display keeps from a VIEWER. The frontend must not even
  // fire that request for a VIEWER, let alone render a section only an error would otherwise fill.
  it('hides the run history section entirely for a VIEWER on a connector library', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    await screen.findByText(/87 Dokumente/i)
    expect(screen.queryByText('Läufe')).not.toBeInTheDocument()
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

  // A VIEWER on a connector library must see both hints - "nur Leserechte" is not limited to
  // upload libraries.
  it('names the reader role once, without a lasting hint, on a connector library', async () => {
    setLibraryState(
      viewerLibrary,
      detailsOf(viewerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data/dokumente' }),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    // no lasting notes: the own role is the head's badge, and the Reiter „Quelle“ names once
    // what only managers see
    expect(await screen.findByText('Leser')).toBeInTheDocument()
    expect(screen.queryByText(/nur Leserechte/)).not.toBeInTheDocument()
    expect(screen.queryByText(/lassen sich hier nicht löschen/i)).not.toBeInTheDocument()
    await userEvent.setup().click(screen.getByRole('tab', { name: 'Quelle' }))
    expect(
      await screen.findAllByText('Verbindung, Zeitplan und Läufe sehen nur Verwaltende.'),
    ).toHaveLength(1)
  })

  // The document list renders for connector libraries too - their indexed bestand is served by
  // the API like any other.
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
    expect(screen.queryByRole('button', { name: 'Hochladen' })).not.toBeInTheDocument()
    expect(screen.queryByLabelText('Dateien auswählen')).not.toBeInTheDocument()
    // a connector document leaves only with its source: no row menu, no bulk „Löschen“
    expect(
      screen.queryByRole('button', { name: 'Aktionen für Dokument rundschreiben.pdf' }),
    ).not.toBeInTheDocument()
    await userEvent
      .setup()
      .click(screen.getByRole('checkbox', { name: 'Dokument rundschreiben.pdf auswählen' }))
    const toolbar = screen.getByRole('toolbar', { name: 'Sammelaktionen' })
    expect(within(toolbar).getByRole('button', { name: 'Feld setzen' })).toBeInTheDocument()
    expect(within(toolbar).queryByRole('button', { name: 'Löschen' })).not.toBeInTheDocument()
  })

  // An RSS attachment carries its originating entry via sourceEntryUrl - the
  // detail page must make it visible instead of only storing it.
  it('names the source entry URL of an RSS attachment, but not for a document without one', async () => {
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
        // An RSS attachment (sourceType RSS_FEED, sourceEntryUrl set)
        // can only exist in an RSS_FEED library - a FILESYSTEM library here would be an
        // impossible state the backend never produces.
        { sourceType: 'RSS_FEED', sourceUrl: 'https://example.gov/feed.xml' },
      ),
    )
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })

    // the origin stands in the name's tooltip, as text; only the attachment carries one
    expect(await screen.findByText('dienstanweisung-anlage.pdf')).toHaveAttribute(
      'title',
      'dienstanweisung-anlage.pdf\nhttps://example.gov/aktuelles/dienstanweisung-2024',
    )
    expect(screen.getByText('rundschreiben.pdf')).toHaveAttribute('title', 'rundschreiben.pdf')
    expect(screen.queryByText(/herkunft:/i)).not.toBeInTheDocument()
  })

  // ADR-0022, Entscheidung 5: attachments carry parentDocumentId and follow their
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
      // the search appears from eight documents on
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([docOf('document-x', 'x.pdf')], { totalElements: 8 }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      // The backend answers an attachment hit with the parent plus its whole group.
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
      expect(screen.getByText(/^Anhang von weiterleitung\.eml · /)).toBeInTheDocument()
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

  // Local sourceTypes fetch the file as a Blob and open/download it client-side, since the
  // download endpoint is Bearer-authenticated - see utils/documentContent.test.ts for that piece's
  // own behaviour, mocked here (see the vi.mock above) to isolate which target this page picks.
  // Metadata view per document and the Sammelzuweisung on the selection.
  describe('Metadaten', () => {
    const indexedDocuments: LibraryDocumentResponse[] = [
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
      {
        id: 'doc-2',
        fileName: 'vermerk.docx',
        contentType: 'application/msword',
        fileSize: 1024,
        status: 'INDEXED',
        sourceType: 'UPLOAD',
        chunkCount: 2,
        indexedAt: '2026-03-01T10:00:00Z',
        uploadedByUserId: null,
      },
    ]

    it('opens the metadata view of a document and marks a derived value as such', async () => {
      mockGetLibraryDocuments.mockResolvedValue(pageOf(indexedDocuments))
      setLibraryState(viewerLibrary, detailsOf(viewerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(
        await screen.findByRole('button', { name: 'Metadaten von dienstanweisung.pdf anzeigen' }),
      )

      const region = await screen.findByRole('region', {
        name: 'Metadaten von dienstanweisung.pdf',
      })
      expect(mockGetDocumentMetadata).toHaveBeenCalledWith('library-readonly', 'doc-1')
      expect(within(region).getByText('Vermerk')).toBeInTheDocument()
      expect(within(region).getByText('abgeleitet')).toBeInTheDocument()
      // A VIEWER sees the values but neither correction controls nor the bulk toolbar.
      expect(within(region).queryByRole('button', { name: /bearbeiten/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('toolbar', { name: 'Sammelaktionen' })).not.toBeInTheDocument()
      expect(
        screen.queryByRole('checkbox', { name: 'Dokument dienstanweisung.pdf auswählen' }),
      ).not.toBeInTheDocument()

      await user.click(
        screen.getByRole('button', { name: 'Metadaten von dienstanweisung.pdf verbergen' }),
      )
      expect(
        screen.queryByRole('region', { name: 'Metadaten von dienstanweisung.pdf' }),
      ).not.toBeInTheDocument()
    })

    it('lets an editor select documents and set one field on the selection', async () => {
      mockGetLibraryDocuments.mockResolvedValue(pageOf(indexedDocuments))
      setLibraryState(
        { ...managerLibrary, myRole: 'EDITOR' },
        detailsOf({ ...managerLibrary, myRole: 'EDITOR' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      // the toolbar's actions appear with a selection; until then its head names the column
      const toolbar = await screen.findByRole('toolbar', { name: 'Sammelaktionen' })
      expect(within(toolbar).getByRole('status')).toHaveTextContent('Name')
      expect(within(toolbar).queryByRole('button', { name: 'Feld setzen' })).not.toBeInTheDocument()
      await user.click(
        screen.getByRole('checkbox', { name: 'Dokument dienstanweisung.pdf auswählen' }),
      )
      await user.click(screen.getByRole('checkbox', { name: 'Dokument vermerk.docx auswählen' }))
      expect(within(toolbar).getByText('2 ausgewählt')).toBeInTheDocument()
      await user.click(within(toolbar).getByRole('button', { name: 'Feld setzen' }))

      const dialog = await screen.findByRole('dialog', { name: 'Feld für 2 Dokumente setzen' })
      await user.click(await within(dialog).findByRole('combobox', { name: /Dokumentart/ }))
      await user.click(await screen.findByRole('option', { name: 'Vermerk' }))
      await user.click(within(dialog).getByRole('button', { name: 'Weiter' }))
      await user.click(within(dialog).getByRole('button', { name: 'Zuweisen' }))

      await waitFor(() =>
        expect(mockBulkSetDocumentMetadata).toHaveBeenCalledWith('library-team', {
          fieldKey: 'document_type',
          value: { vocabularyCode: 'VERMERK' },
          documentIds: ['doc-1', 'doc-2'],
        }),
      )
      await user.click(within(dialog).getByRole('button', { name: 'Schließen' }))
      expect(
        await screen.findByText('Feld gesetzt: 2 Dokumente aktualisiert, 0 unverändert.'),
      ).toBeInTheDocument()
      expect(within(toolbar).getByRole('status')).toHaveTextContent('Name')
    })

    // Working the filtered list („ohne Dokumentart“) document by document is the normal case -
    // a corrected document must leave the list right away.
    it('reloads the worklist after a single correction inside the worklist', async () => {
      mockGetLibraryDocuments.mockResolvedValue(pageOf(indexedDocuments))
      setLibraryState(
        { ...managerLibrary, myRole: 'EDITOR' },
        detailsOf({ ...managerLibrary, myRole: 'EDITOR' }),
      )
      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?missingField=document_type',
      })
      const user = userEvent.setup()

      await user.click(
        await screen.findByRole('button', { name: 'Metadaten von dienstanweisung.pdf anzeigen' }),
      )
      await user.click(
        await screen.findByRole('button', {
          name: 'Dokumentart von dienstanweisung.pdf bearbeiten',
        }),
      )
      const dialog = await screen.findByRole('dialog')
      await user.click(within(dialog).getByRole('checkbox', { name: 'Kein Wert ermittelbar' }))
      mockGetLibraryDocuments.mockClear()
      await user.click(within(dialog).getByRole('button', { name: 'Speichern' }))

      await waitFor(() => expect(mockSetDocumentMetadataValue).toHaveBeenCalled())
      await waitFor(() =>
        expect(mockGetLibraryDocuments).toHaveBeenCalledWith(
          'library-team',
          expect.objectContaining({ missingMetadataField: 'document_type' }),
        ),
      )
    })

    it('drops the selection when the list changes by page or search', async () => {
      // The selection belongs to the list the person is looking at - never to documents that
      // scrolled out of sight through a page change or a search.
      mockGetLibraryDocuments.mockResolvedValue(pageOf(indexedDocuments, { totalElements: 45 }))
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const toolbar = await screen.findByRole('toolbar', { name: 'Sammelaktionen' })
      await user.click(
        screen.getByRole('checkbox', { name: 'Dokument dienstanweisung.pdf auswählen' }),
      )
      expect(within(toolbar).getByText('1 ausgewählt')).toBeInTheDocument()

      const pagination = screen.getByRole('navigation', { name: 'Dokumentenliste blättern' })
      await user.click(within(pagination).getByRole('button', { name: /seite 2/i }))
      await waitFor(() => expect(within(toolbar).getByRole('status')).toHaveTextContent('Name'))

      await user.click(
        screen.getByRole('checkbox', { name: 'Dokument dienstanweisung.pdf auswählen' }),
      )
      expect(within(toolbar).getByText('1 ausgewählt')).toBeInTheDocument()
      await user.type(screen.getByLabelText('Dokumente durchsuchen'), 'dienst')
      expect(within(toolbar).getByRole('status')).toHaveTextContent('Name')
    })
  })

  describe('"Original öffnen"', () => {
    it('opens a Confluence document at its source URL instead of the content endpoint (ADR-0023)', async () => {
      // The backend's content endpoint deliberately answers 404 for CONFLUENCE - a page has no
      // original file of its own; its original IS the page in the instance.
      setLibraryState(
        managerLibrary,
        detailsOf(managerLibrary, {
          sourceType: 'CONFLUENCE',
          sourceUrl: 'http://wiki.example',
          sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'IT', name: 'IT-Betrieb' }] },
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
          sourceSettings: { edition: 'DATA_CENTER', spaces: [{ key: 'IT', name: 'IT-Betrieb' }] },
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

      // The key resolves to the name the library's own selection carries; the origin stands in
      // the name's tooltip.
      expect(await screen.findByText('Abschnitt 1.1 — Firewall-Regeln')).toHaveAttribute(
        'title',
        'Abschnitt 1.1 — Firewall-Regeln\nSpace: IT-Betrieb · Betriebshandbuch / Kapitel 1 — Netzwerkbetrieb',
      )
    })

    it("names an S3 document's bucket and key folders in its row, as text (ADR-0027)", async () => {
      setLibraryState(
        managerLibrary,
        detailsOf(managerLibrary, {
          sourceType: 'S3',
          sourceUrl: 'https://minio.intern.example:9000',
          sourceSettings: {
            region: 'us-east-1',
            pathStyle: true,
            scopes: [{ bucket: 'dokumente', prefix: '2025/' }],
            includePatterns: [],
            excludePatterns: [],
          },
        }),
      )
      mockGetLibraryDocuments.mockResolvedValueOnce(
        pageOf([
          {
            id: 'doc-s3',
            fileName: 'sitzung.pdf',
            contentType: 'application/pdf',
            fileSize: 512,
            status: 'INDEXED',
            sourceType: 'S3',
            chunkCount: 2,
            indexedAt: '2026-09-06T10:00:00Z',
            uploadedByUserId: null,
            sourceUrl: null,
            sourceContainerKey: 'dokumente',
            sourceHierarchyPath: 'protokolle / q1',
          },
        ]),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      expect(await screen.findByText('sitzung.pdf')).toHaveAttribute(
        'title',
        'sitzung.pdf\nBucket: dokumente · protokolle / q1',
      )
      expect(screen.queryByRole('link', { name: /dokumente/ })).not.toBeInTheDocument()
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
      // The content endpoint now proxies HTTP_DIRECTORY/RSS_FEED server-side from their own
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

      // The source URL stays secondary information in the name's tooltip, as text, even
      // though "Original öffnen" does not navigate there directly.
      expect(screen.getByText('dienstanweisung.pdf')).toHaveAttribute(
        'title',
        'dienstanweisung.pdf\nhttps://example.gov/verzeichnis/dienstanweisung.pdf',
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
      // the entry's address goes before the file's own in the tooltip
      expect(screen.getByText('anlage.pdf')).toHaveAttribute(
        'title',
        'anlage.pdf\nhttps://example.gov/aktuelles/dienstanweisung-2024',
      )
    })

    it('still offers the action for a remote document with no other source information', async () => {
      // Every sourceType opens through the content endpoint - a document that turns out
      // unreachable answers a German 404 shown via openOriginalError, not a reason to hide the
      // button up front.
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

    // Markdown/plain text render in a client-side dialog instead of a silent download.
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
      // Heading elements are normalised per rendered content (rank compression from h2);
      // the h5 LOOK of "#" survives as the Typography variant.
      const heading1016 = screen.getByText('Personalausweis').closest('h2')
      expect(heading1016).toBeInTheDocument()
      expect(heading1016).toHaveClass('MuiTypography-h5')
      expect(screen.getByText(/Ausgestellt am 1\. März\./)).toBeInTheDocument()
    })

    // Every format without a preview (DOCX among them) must give visible
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
    // the search appears from eight documents on
    mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([someDocument], { totalElements: 8 }))
    setLibraryState(managerLibrary, detailsOf(managerLibrary))
    renderWithProviders(<LibraryDetailPage />, { withRouter: true })
    const user = userEvent.setup()

    await screen.findByText('vorhanden.pdf')
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
          missingMetadataField: null,
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
    // The deDE MUI locale translates Pagination's default item aria-labels.
    expect(screen.getByRole('button', { name: 'Gehe zu Seite 2' })).toBeInTheDocument()
  })

  // Folder navigation/management UI - the backend is covered elsewhere; these tests only exercise
  // the frontend wiring.
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

      // the row is the button; its name starts with the visible folder name
      const folderRow = await screen.findByRole('button', { name: /^Protokolle\s*3 Dokumente$/ })
      expect(within(folderRow).getByText(/3 dokumente/i)).toBeInTheDocument()

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
        missingMetadataField: null,
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
        missingMetadataField: null,
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
      // „Neuer Ordner“ stands in the list's head; an empty library shows only its drop zone (D21)
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([someDocument]))
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

      await screen.findByText('vorhanden.pdf')
      await user.click(screen.getByRole('button', { name: /neuer ordner/i }))
      await user.type(await screen.findByLabelText(/ordnername/i), 'Archiv')
      await user.click(screen.getByRole('button', { name: /^anlegen$/i }))

      expect(
        await screen.findByRole('button', { name: /^Archiv\s*0 Dokumente$/ }),
      ).toBeInTheDocument()
      expect(mockCreateLibraryFolder).toHaveBeenCalledWith('library-mine', {
        name: 'Archiv',
        parentFolderId: null,
      })
    })

    it('shows a 409 name conflict inside the dialog without closing it', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      // „Neuer Ordner“ stands in the list's head; an empty library shows only its drop zone (D21)
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([someDocument]))
      mockCreateLibraryFolder.mockRejectedValueOnce(
        new Error('Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene'),
      )

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await screen.findByText('vorhanden.pdf')
      await user.click(screen.getByRole('button', { name: /neuer ordner/i }))
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
        await screen.findByRole('button', { name: /^Protokolle 2026\s*0 Dokumente$/ }),
      ).toBeInTheDocument()
      expect(mockRenameLibraryFolder).toHaveBeenCalledWith('library-mine', 'folder-protokolle', {
        name: 'Protokolle 2026',
      })
    })

    it('deletes a folder after a confirmation naming its live (re-fetched) document count', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      mockGetLibraryDocuments.mockResolvedValueOnce(
        // The row's own count (5, from the last-loaded list) is
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
      mockDeleteLibraryFolder.mockResolvedValueOnce(undefined)
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([]))

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(
        await screen.findByRole('button', { name: /optionen für ordner protokolle/i }),
      )
      await user.click(await screen.findByRole('menuitem', { name: /löschen/i }))

      await answerConfirm(user, 'Ordner "Protokolle" und 7 Dokumente löschen?', 'Löschen')

      await waitFor(() => {
        expect(mockDeleteLibraryFolder).toHaveBeenCalledWith('library-mine', 'folder-protokolle')
      })
      await waitFor(() => {
        expect(
          screen.queryByRole('button', { name: /^Protokolle\s*\d+ Dokumente?$/ }),
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
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await user.click(
        await screen.findByRole('button', { name: /optionen für ordner protokolle/i }),
      )
      await user.click(await screen.findByRole('menuitem', { name: /löschen/i }))

      await answerConfirm(user, 'Ordner "Protokolle" und 3 Dokumente löschen?', 'Abbrechen')

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

    // "Ordner hochladen" - the webkitdirectory file input, whose selected files carry the
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

    // A whole folder dragged and dropped - DataTransferItem.webkitGetAsEntry() must be
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

      const dropZone = await screen.findByText(/dateien oder ordner hierher ziehen/i)
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

    // A real OS folder routinely carries files nobody dragged there on
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

      const dropZone = await screen.findByText(/dateien oder ordner hierher ziehen/i)
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

    // Thumbs.db/.DS_Store/desktop.ini are dropped silently, without being
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

      const dropZone = await screen.findByText(/dateien oder ordner hierher ziehen/i)
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

    // An unreadable file inside a dropped folder must not abort the whole
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

      const dropZone = await screen.findByText(/dateien oder ordner hierher ziehen/i)
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
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([someDocument], { totalElements: 8 }))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await screen.findByText('vorhanden.pdf')

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

    // navigateToFolder only reacted to a change of the URL's own folder
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
          missingMetadataField: null,
        })
      })
      expect(screen.queryByText('protokoll-2026-01.pdf')).not.toBeInTheDocument()
    })

    // Cancelling a dialog that just showed a 409 conflict left
    // documentStore.folderError set - the page-level Alert (hidden while a folder dialog is open)
    // would then flash that same message once the dialog was gone.
    it('clears the page-level folderError alert when a dialog with a 409 is cancelled', async () => {
      setLibraryState(personalLibrary, detailsOf(personalLibrary))
      // „Neuer Ordner“ stands in the list's head; an empty library shows only its drop zone (D21)
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf([someDocument]))
      mockCreateLibraryFolder.mockRejectedValueOnce(
        new Error('Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene'),
      )

      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      await screen.findByText('vorhanden.pdf')
      await user.click(screen.getByRole('button', { name: /neuer ordner/i }))
      await user.type(await screen.findByLabelText(/ordnername/i), 'Protokolle')
      await user.click(screen.getByRole('button', { name: /^anlegen$/i }))
      expect(await screen.findByText(/existiert bereits auf dieser ebene/i)).toBeInTheDocument()

      await user.click(screen.getByRole('button', { name: /abbrechen/i }))

      expect(screen.queryByText(/existiert bereits auf dieser ebene/i)).not.toBeInTheDocument()
    })

    // Folders/breadcrumb are not paged - the backend returns the same
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
        screen.queryByRole('button', { name: /^Protokolle\s*\d+ Dokumente?$/ }),
      ).not.toBeInTheDocument()
    })

    // documentStore's pageStateByLibrary is not necessarily populated before a deep link's first
    // load resolves - "Neuer Ordner" in that window must still create the folder in the folder
    // named in the URL, not at the library's root.
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

  // metadata-schema.md, "Der Pflege-Anker": the fill of every field has one place, the
  // „Füllgrad“ of the Reiter „Metadaten“, and leads to exactly the documents without a value
  it('shows the Füllgrad per field and opens exactly those documents from it', async () => {
    server.use(
      http.get('/api/v1/libraries/:libraryId/metadata/quality', ({ params }) =>
        HttpResponse.json({
          libraryId: String(params.libraryId),
          totalDocuments: 4,
          modelExtractionEnabled: false,
          keywordsEnabled: false,
          confidenceThreshold: 0.6,
          fields: [
            {
              fieldKey: 'title',
              label: 'Titel',
              totalDocuments: 4,
              deterministicDocuments: 4,
              derivedDocuments: 0,
              manualDocuments: 0,
              notDeterminableDocuments: 0,
              emptyDocuments: 0,
              derivedShare: 0,
              emptyShare: 0,
            },
            {
              fieldKey: 'document_type',
              label: 'Dokumentart',
              totalDocuments: 4,
              deterministicDocuments: 0,
              derivedDocuments: 1,
              manualDocuments: 0,
              notDeterminableDocuments: 1,
              emptyDocuments: 3,
              derivedShare: 0.25,
              emptyShare: 0.75,
            },
          ],
          modelExtraction: {},
        }),
      ),
    )
    setLibraryState(personalLibrary, detailsOf(personalLibrary))
    renderWithProviders(<LibraryDetailPage />, {
      withRouter: true,
      initialRoute: '/libraries/library-mine?tab=metadaten',
    })
    const user = userEvent.setup()

    const fill = await screen.findByRole('region', { name: 'Füllgrad' })
    expect(fill).toHaveTextContent(/Titel\s*alle 4 gefüllt/)
    expect(fill).toHaveTextContent(/Dokumentart\s*1 von 4 gefüllt \(25 %\)/)
    // a field without empty documents offers nothing to open
    expect(
      within(fill).queryByRole('button', { name: /Dokumente ohne Titel/ }),
    ).not.toBeInTheDocument()

    mockGetLibraryDocuments.mockClear()
    await user.click(
      within(fill).getByRole('button', { name: /^Anzeigen\s*Dokumente ohne Dokumentart$/ }),
    )

    await waitFor(() =>
      expect(mockGetLibraryDocuments).toHaveBeenLastCalledWith('library-mine', {
        page: 0,
        size: 20,
        q: '',
        folderId: null,
        missingMetadataField: 'document_type',
      }),
    )
    // the documents open with the filter set, so it can be taken back where it shows
    expect(await screen.findByRole('tab', { selected: true })).toHaveTextContent('Dokumente')
    expect(screen.getByRole('combobox', { name: 'Metadaten' })).toHaveTextContent(
      'ohne Dokumentart',
    )
  })

  // The „Quelle" tab in four sections - scope for every reader, source connection, schedule
  // and runs for managers.
  describe('Reiter Quelle (#1940)', () => {
    const connectorLibrary: LibraryListResponse = {
      ...managerLibrary,
      id: 'library-team',
      myRole: 'MANAGER',
      sourceType: 'HTTP_DIRECTORY',
    }

    async function openSourceTab() {
      const user = userEvent.setup()
      await user.click(await screen.findByRole('tab', { name: 'Quelle' }))
      return user
    }

    it('names the rows and the runs behind the MANAGER bar in order', async () => {
      setLibraryState(
        connectorLibrary,
        detailsOf(connectorLibrary, {
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://files.example.com/dokumente/',
        }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      await openSourceTab()

      const panel = screen.getByRole('tabpanel', { name: 'Quelle' })
      const headings = within(panel)
        .getAllByRole('heading', { level: 2 })
        .map((heading) => heading.textContent)
      // a directory listing is read whole: its row „Umfang“ says so and opens nothing
      expect(headings).toEqual(['Verbindung', 'Umfang', 'Zeitplan', 'Läufe'])
      const scope = within(panel).getByRole('region', { name: 'Umfang' })
      expect(scope).toHaveTextContent('Ganzes Verzeichnis')
      expect(within(scope).queryByRole('button', { name: /^Ändern/ })).not.toBeInTheDocument()
    })

    it('shows a VIEWER the Umfang only, in the words of the provider', async () => {
      const readerOfS3: LibraryListResponse = {
        ...viewerLibrary,
        id: 'library-readonly',
        sourceType: 'S3',
      }
      setLibraryState(
        readerOfS3,
        detailsOf(readerOfS3, {
          sourceType: 'S3',
          sourceUrl: 'https://s3.example.com',
          sourceSettings: { pathStyle: false, scopes: [{ bucket: 'akten', prefix: '2026/' }] },
        }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      await openSourceTab()

      const panel = screen.getByRole('tabpanel', { name: 'Quelle' })
      const scope = within(panel).getByRole('region', { name: 'Umfang' })
      expect(scope).toHaveTextContent('1 Bucket oder Präfix')
      expect(scope).toHaveTextContent('akten / 2026')
      expect(within(panel).getByText(/Verbindung, Zeitplan und Läufe/)).toBeInTheDocument()
      expect(within(panel).queryByRole('heading', { name: 'Verbindung' })).not.toBeInTheDocument()
      expect(within(panel).queryByRole('button', { name: /^Ändern/ })).not.toBeInTheDocument()
    })

    it('tests the stored configuration from the row „Verbindung“, saving nothing', async () => {
      setLibraryState(
        connectorLibrary,
        detailsOf(connectorLibrary, {
          sourceType: 'HTTP_DIRECTORY',
          sourceUrl: 'https://files.example.com/dokumente/',
        }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = await openSourceTab()

      const panel = screen.getByRole('tabpanel', { name: 'Quelle' })
      await user.click(changeButton('Verbindung'))
      await user.click(within(panel).getByRole('button', { name: 'Verbindung testen' }))

      await waitFor(() =>
        // The credentials field stays blank, which is what makes the backend fall back to the
        // stored ones; libraryId is what lets it.
        expect(mockTestLibrarySource).toHaveBeenCalledWith(
          expect.objectContaining({
            sourceType: 'HTTP_DIRECTORY',
            sourceUrl: 'https://files.example.com/dokumente/',
            libraryId: 'library-team',
            sourceCredentials: undefined,
          }),
        ),
      )
      expect(
        await within(panel).findByText(
          'Verzeichnis erreichbar, 3 unterstützte Dokumente gefunden.',
        ),
      ).toBeInTheDocument()
      expect(mockUpdateLibrary).not.toHaveBeenCalled()
    })
  })

  // Bulk actions of the selection - „Feld setzen" everywhere, „Löschen" only where a single row
  // offers it too.
  describe('Sammelloeschen (#1943)', () => {
    const uploads: LibraryDocumentResponse[] = [
      {
        id: 'doc-1',
        fileName: 'a.pdf',
        contentType: 'application/pdf',
        fileSize: 10,
        status: 'INDEXED',
        sourceType: 'UPLOAD',
        chunkCount: 1,
        indexedAt: '2026-03-01T10:00:00Z',
        uploadedByUserId: null,
      },
      {
        id: 'doc-2',
        fileName: 'b.pdf',
        contentType: 'application/pdf',
        fileSize: 10,
        status: 'INDEXED',
        sourceType: 'UPLOAD',
        chunkCount: 1,
        indexedAt: '2026-03-01T10:00:00Z',
        uploadedByUserId: null,
      },
    ]

    it('deletes the whole selection after a confirmation and reports a partial success', async () => {
      mockGetLibraryDocuments.mockResolvedValue(pageOf(uploads))
      mockBulkDeleteLibraryDocuments.mockResolvedValueOnce({
        deletedDocumentIds: ['doc-1'],
        failures: [{ documentId: 'doc-2', message: 'Dokument nicht gefunden' }],
      })
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const toolbar = await screen.findByRole('toolbar', { name: 'Sammelaktionen' })
      await screen.findByText('a.pdf')
      await user.click(screen.getByRole('checkbox', { name: 'Alle auf dieser Seite auswählen' }))
      expect(within(toolbar).getByRole('status')).toHaveTextContent('2 ausgewählt')

      await user.click(within(toolbar).getByRole('button', { name: 'Löschen' }))
      await answerConfirm(user, '2 ausgewählte Dokumente löschen?', 'Löschen')

      await waitFor(() =>
        expect(mockBulkDeleteLibraryDocuments).toHaveBeenCalledWith('library-team', [
          'doc-1',
          'doc-2',
        ]),
      )
      // A partial success is a warning, not a green success, and identical reasons are counted
      // instead of strung together.
      const result = await screen.findByText(
        '1 von 2 Dokumenten gelöscht. Nicht gelöscht — 1 Dokument: Dokument nicht gefunden.',
      )
      expect(result.closest('.MuiAlert-root')).toHaveClass('MuiAlert-colorWarning')
    })

    it('keeps the outcome of a deletion that empties the library', async () => {
      mockGetLibraryDocuments.mockResolvedValueOnce(pageOf(uploads)).mockResolvedValue(pageOf([]))
      mockBulkDeleteLibraryDocuments.mockResolvedValueOnce({
        deletedDocumentIds: ['doc-1', 'doc-2'],
        failures: [],
      })
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const toolbar = await screen.findByRole('toolbar', { name: 'Sammelaktionen' })
      await screen.findByText('a.pdf')
      await user.click(screen.getByRole('checkbox', { name: 'Alle auf dieser Seite auswählen' }))
      await user.click(within(toolbar).getByRole('button', { name: 'Löschen' }))
      await answerConfirm(user, '2 ausgewählte Dokumente löschen?', 'Löschen')

      expect(await screen.findByText('2 Dokumente gelöscht.')).toBeInTheDocument()
      await waitFor(() => expect(screen.queryByText('a.pdf')).not.toBeInTheDocument())
      expect(screen.getByText('2 Dokumente gelöscht.')).toBeInTheDocument()
    })

    it('counts identical reasons instead of repeating them', async () => {
      mockGetLibraryDocuments.mockResolvedValue(pageOf(uploads))
      mockBulkDeleteLibraryDocuments.mockResolvedValueOnce({
        deletedDocumentIds: [],
        failures: [
          { documentId: 'doc-1', message: 'Dokument nicht gefunden' },
          { documentId: 'doc-2', message: 'Dokument nicht gefunden' },
        ],
      })
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const toolbar = await screen.findByRole('toolbar', { name: 'Sammelaktionen' })
      await screen.findByText('a.pdf')
      await user.click(screen.getByRole('checkbox', { name: 'Alle auf dieser Seite auswählen' }))
      await user.click(within(toolbar).getByRole('button', { name: 'Löschen' }))
      await answerConfirm(user, '2 ausgewählte Dokumente löschen?', 'Löschen')

      expect(
        await screen.findByText(
          '0 von 2 Dokumenten gelöscht. Nicht gelöscht — 2 Dokumente: Dokument nicht gefunden.',
        ),
      ).toBeInTheDocument()
    })

    // no lasting note on why (D20): the selection simply carries no „Löschen“
    it('offers no Löschen on a connector bestand', async () => {
      const connector = { ...managerLibrary, sourceType: 'FILESYSTEM' as const }
      mockGetLibraryDocuments.mockResolvedValue(
        pageOf(uploads.map((doc) => ({ ...doc, sourceType: 'FILESYSTEM' as const }))),
      )
      setLibraryState(connector, detailsOf(connector, { sourceType: 'FILESYSTEM' }))
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      const user = userEvent.setup()

      const toolbar = await screen.findByRole('toolbar', { name: 'Sammelaktionen' })
      await screen.findByText('a.pdf')
      await user.click(screen.getByRole('checkbox', { name: 'Alle auf dieser Seite auswählen' }))
      expect(within(toolbar).getByRole('status')).toHaveTextContent('2 ausgewählt')
      expect(within(toolbar).getByRole('button', { name: 'Feld setzen' })).toBeInTheDocument()
      expect(within(toolbar).queryByRole('button', { name: 'Löschen' })).not.toBeInTheDocument()
      expect(
        screen.queryByRole('button', { name: 'Aktionen für Dokument a.pdf' }),
      ).not.toBeInTheDocument()
    })
  })

  // ADR-0044: an installation that switched metadata off shows none of it in the library.
  describe('ohne Metadaten (Feature-Flag aus)', () => {
    const document: LibraryDocumentResponse = {
      id: 'doc-1',
      fileName: 'dienstanweisung.pdf',
      contentType: 'application/pdf',
      fileSize: 2048,
      status: 'INDEXED',
      sourceType: 'UPLOAD',
      chunkCount: 3,
      indexedAt: '2026-03-01T10:00:00Z',
      uploadedByUserId: null,
    }

    beforeEach(() => {
      useAuthStore.setState({ features: { metadata: false } })
    })

    afterEach(() => {
      useAuthStore.setState({ features: {} })
    })

    it('offers no metadata area, also not through the address', async () => {
      setLibraryState(
        managerLibrary,
        detailsOf(managerLibrary, { sourceType: 'FILESYSTEM', sourcePath: '/data' }),
      )
      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?tab=metadaten',
      })

      const tabs = await screen.findAllByRole('tab')
      expect(tabs.map((tab) => tab.textContent)).toEqual([
        'Dokumente',
        'Quelle',
        'Freigaben',
        'Zuordnungen',
      ])
      expect(screen.getByRole('tab', { name: 'Dokumente' })).toHaveAttribute(
        'aria-selected',
        'true',
      )
      expect(screen.queryByText('Metadatenfelder')).not.toBeInTheDocument()
    })

    it('shows the document list without metadata view, metadata filter and Feld setzen', async () => {
      mockGetLibraryDocuments.mockResolvedValue(pageOf([document]))
      setLibraryState(managerLibrary, detailsOf(managerLibrary))
      renderWithProviders(<LibraryDetailPage />, {
        withRouter: true,
        initialRoute: '/?missingField=document_type',
      })

      expect(await screen.findByText('dienstanweisung.pdf')).toBeInTheDocument()
      const toolbar = await screen.findByRole('toolbar', { name: 'Sammelaktionen' })
      await userEvent
        .setup()
        .click(screen.getByRole('checkbox', { name: 'Dokument dienstanweisung.pdf auswählen' }))
      expect(within(toolbar).getByRole('button', { name: 'Löschen' })).toBeInTheDocument()
      expect(within(toolbar).queryByRole('button', { name: 'Feld setzen' })).not.toBeInTheDocument()
      expect(
        screen.queryByRole('button', { name: /Metadaten von dienstanweisung\.pdf/ }),
      ).not.toBeInTheDocument()
      expect(screen.queryByRole('combobox', { name: 'Metadaten' })).not.toBeInTheDocument()
      expect(screen.queryByRole('region', { name: 'Füllgrad' })).not.toBeInTheDocument()
      expect(mockGetLibraryDocuments).toHaveBeenCalledWith(
        'library-team',
        expect.objectContaining({ missingMetadataField: null }),
      )
    })
  })

  describe('ohne externe KI-Werkzeuge (Feature-Flag aus)', () => {
    afterEach(() => {
      useAuthStore.setState({ features: {} })
    })

    // below the manager role the backend delivers no release, and the section is left out
    const releasable = {
      externalAccess: {
        libraryId: managerLibrary.id,
        state: 'NEVER_SET' as const,
        tokenCount: 0,
        maxReleaseDays: 365,
      },
    }

    it('shows the external access among the shares while the feature is on', async () => {
      setLibraryState(managerLibrary, detailsOf(managerLibrary, releasable))
      const user = userEvent.setup()
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))

      expect(await screen.findByRole('heading', { name: 'Externer Zugang' })).toBeInTheDocument()
    })

    it('names no external access in the shares, nor for a private library', async () => {
      useAuthStore.setState({ features: { externalAccess: false } })
      setLibraryState(managerLibrary, detailsOf(managerLibrary, releasable))
      const user = userEvent.setup()
      const { unmount } = renderWithProviders(<LibraryDetailPage />, { withRouter: true })

      await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))

      expect(await screen.findByRole('heading', { name: 'Suchdiagnose' })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Externer Zugang' })).not.toBeInTheDocument()
      expect(screen.queryByText(/KI-Werkzeug/)).not.toBeInTheDocument()
      unmount()

      const privateLibrary: LibraryListResponse = {
        ...managerLibrary,
        id: 'library-private-off',
        myRole: 'OWNER',
        sourceType: 'NEXTCLOUD',
        privateLibrary: true,
      }
      setLibraryState(
        privateLibrary,
        detailsOf(privateLibrary, { sourceUrl: 'https://cloud.intern.example' }),
      )
      renderWithProviders(<LibraryDetailPage />, { withRouter: true })
      await user.click(await screen.findByRole('tab', { name: 'Freigaben' }))

      expect(await screen.findByRole('heading', { name: 'Eigentümer' })).toBeInTheDocument()
      expect(screen.queryByRole('heading', { name: 'Externer Zugang' })).not.toBeInTheDocument()
      expect(screen.queryByText(/KI-Werkzeug/)).not.toBeInTheDocument()
    })
  })
})
