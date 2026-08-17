import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../test/test-utils'
import DocumentsPage from './DocumentsPage'
import { useAuthStore } from '../stores/authStore'
import { useLibraryStore } from '../stores/libraryStore'
import { useDocumentStore } from '../stores/documentStore'
import type { LibraryDocumentResponse, LibraryListResponse } from '../types/api'

const { mockGetLibraryDocuments, mockUploadDocument, mockDeleteLibraryDocument } = vi.hoisted(
  () => ({
    mockGetLibraryDocuments: vi.fn(async (libraryId: string) => {
      return useDocumentStore.getState().documentsByLibrary[libraryId] ?? []
    }),
    mockUploadDocument: vi.fn(),
    mockDeleteLibraryDocument: vi.fn(async () => undefined),
  }),
)

vi.mock('../services/api', async () => {
  const actual = await vi.importActual<typeof import('../services/api')>('../services/api')
  return {
    ...actual,
    getLibraries: vi.fn(async () => useLibraryStore.getState().libraries),
    getLibraryDocuments: mockGetLibraryDocuments,
    uploadDocument: mockUploadDocument,
    deleteLibraryDocument: mockDeleteLibraryDocument,
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
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const editorLibrary: LibraryListResponse = {
  id: 'library-editor',
  name: 'Rechtsquellen Soziales',
  description: 'SGB II, SGB XII',
  ownerType: 'GROUP',
  visibility: 'SHARED',
  listed: true,
  personal: false,
  myRole: 'EDITOR',
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
  createdAt: '2026-03-01T10:00:00Z',
  updatedAt: '2026-03-01T10:00:00Z',
}

const indexedDocument: LibraryDocumentResponse = {
  id: 'document-1',
  fileName: 'dienstanweisung-2024.pdf',
  contentType: 'application/pdf',
  fileSize: 1258291,
  status: 'INDEXED',
  sourceType: 'UPLOAD',
  chunkCount: 34,
  indexedAt: '2026-03-02T09:00:00Z',
  uploadedByUserId: 'mock-user-id',
}

const failedDocument: LibraryDocumentResponse = {
  id: 'document-2',
  fileName: 'vermerk.pptx',
  contentType: null,
  fileSize: 512000,
  status: 'FAILED',
  sourceType: 'UPLOAD',
  chunkCount: 0,
  indexedAt: null,
  uploadedByUserId: 'mock-user-id',
}

function setLibraries(libraries: LibraryListResponse[]) {
  useLibraryStore.setState({ libraries, libraryDetails: {}, isLoading: false, error: null })
}

function setDocuments(documentsByLibrary: Record<string, LibraryDocumentResponse[]>) {
  useDocumentStore.setState({
    documentsByLibrary,
    isLoading: false,
    error: null,
    uploadErrors: [],
    deleteError: null,
    isUploading: false,
  })
}

describe('DocumentsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    // mockUploadDocument/mockDeleteLibraryDocument carry per-test mockResolvedValueOnce/
    // mockRejectedValueOnce queues; clearAllMocks() only resets call history, not those queued
    // implementations, so a test that queues more than it consumes (or is skipped) would leak into
    // the next one otherwise.
    mockUploadDocument.mockReset()
    mockDeleteLibraryDocument.mockReset().mockResolvedValue(undefined)
    useAuthStore.setState({ user: null })
  })

  afterEach(() => {
    useDocumentStore.getState().reset()
  })

  it('shows the documents of the personal library on load', async () => {
    setLibraries([editorLibrary, personalLibrary])
    setDocuments({ 'library-personal': [indexedDocument] })
    renderWithProviders(<DocumentsPage />)

    expect(await screen.findByText('dienstanweisung-2024.pdf')).toBeInTheDocument()
  })

  it('loads the documents of a library after switching', async () => {
    setLibraries([personalLibrary, editorLibrary])
    setDocuments({
      'library-personal': [indexedDocument],
      'library-editor': [failedDocument],
    })
    renderWithProviders(<DocumentsPage />)
    const user = userEvent.setup()

    expect(await screen.findByText('dienstanweisung-2024.pdf')).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: /^bibliothek$/i }))
    await user.click(await screen.findByRole('option', { name: /rechtsquellen soziales/i }))

    expect(await screen.findByText('vermerk.pptx')).toBeInTheDocument()
    expect(screen.queryByText('dienstanweisung-2024.pdf')).not.toBeInTheDocument()
  })

  it('uploads a file into the currently displayed library and shows it in the list afterwards', async () => {
    setLibraries([personalLibrary])
    setDocuments({ 'library-personal': [] })
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
    renderWithProviders(<DocumentsPage />)
    const user = userEvent.setup()

    const file = new File(['Inhalt'], 'neues-dokument.pdf', { type: 'application/pdf' })
    const input = screen.getByLabelText(/dateien auswählen/i, { selector: 'input' })
    await user.upload(input, file)

    expect(await screen.findByText('neues-dokument.pdf')).toBeInTheDocument()
    expect(mockUploadDocument).toHaveBeenCalledWith('library-personal', file)
  })

  it('shows a German message naming the file for a rejected format, keeping the list unchanged', async () => {
    setLibraries([personalLibrary])
    setDocuments({ 'library-personal': [indexedDocument] })
    mockUploadDocument.mockRejectedValueOnce(
      new Error('Das Dateiformat wird nicht unterstuetzt. Erlaubt sind: .md, .pdf'),
    )
    renderWithProviders(<DocumentsPage />)

    // Via drag-and-drop, not userEvent.upload() on the file input: the input's accept attribute
    // (nit from the #442 review) makes browsers - and jsdom/user-event, matching that - filter an
    // .exe out of the file *picker* before a change event ever fires, but a drop is not filtered
    // that way. The backend remains authoritative regardless of path, which is exactly what this
    // test exercises.
    const file = new File(['Inhalt'], 'schadprogramm.exe', { type: 'application/octet-stream' })
    const dropzone = screen.getByRole('button', { name: /dateien hierher ziehen zum hochladen/i })
    fireEvent.drop(dropzone, { dataTransfer: { files: [file] } })

    expect(await screen.findByText(/dateiformat.*schadprogramm\.exe/is)).toBeInTheDocument()
    expect(screen.getByText('dienstanweisung-2024.pdf')).toBeInTheDocument()
    expect(screen.queryByText('schadprogramm.exe')).not.toBeInTheDocument()
  })

  it('shows a German message for an oversized file', async () => {
    setLibraries([personalLibrary])
    setDocuments({ 'library-personal': [] })
    mockUploadDocument.mockRejectedValueOnce(
      new Error('Die Datei ist zu gross. Erlaubt sind hoechstens 50 MB'),
    )
    renderWithProviders(<DocumentsPage />)
    const user = userEvent.setup()

    const file = new File(['Inhalt'], 'riesig.pdf', { type: 'application/pdf' })
    const input = screen.getByLabelText(/dateien auswählen/i, { selector: 'input' })
    await user.upload(input, file)

    expect(await screen.findByText(/zu gross/i)).toBeInTheDocument()
  })

  it('shows a German duplicate message', async () => {
    setLibraries([personalLibrary])
    setDocuments({ 'library-personal': [indexedDocument] })
    mockUploadDocument.mockRejectedValueOnce(
      new Error('Diese Datei ist bereits in dieser Bibliothek vorhanden'),
    )
    renderWithProviders(<DocumentsPage />)
    const user = userEvent.setup()

    const file = new File(['Inhalt'], 'dienstanweisung-2024.pdf', { type: 'application/pdf' })
    const input = screen.getByLabelText(/dateien auswählen/i, { selector: 'input' })
    await user.upload(input, file)

    expect(await screen.findByText(/bereits.*vorhanden/i)).toBeInTheDocument()
  })

  it('keeps the error of an earlier failed file visible after a later file in the same batch succeeds', async () => {
    setLibraries([personalLibrary])
    setDocuments({ 'library-personal': [] })
    mockUploadDocument
      .mockRejectedValueOnce(new Error('Diese Datei ist bereits in dieser Bibliothek vorhanden'))
      .mockResolvedValueOnce({
        id: 'document-second',
        fileName: 'zweite-datei.pdf',
        contentType: 'application/pdf',
        fileSize: 500,
        status: 'INDEXED',
        sourceType: 'UPLOAD',
        chunkCount: 3,
        indexedAt: '2026-03-02T09:00:00Z',
        uploadedByUserId: 'mock-user-id',
      })
    renderWithProviders(<DocumentsPage />)
    const user = userEvent.setup()

    const firstFile = new File(['Inhalt'], 'dublette.pdf', { type: 'application/pdf' })
    const secondFile = new File(['Inhalt'], 'zweite-datei.pdf', { type: 'application/pdf' })
    const input = screen.getByLabelText(/dateien auswählen/i, { selector: 'input' })
    await user.upload(input, [firstFile, secondFile])

    expect(await screen.findByText('zweite-datei.pdf')).toBeInTheDocument()
    expect(screen.getByText(/bereits.*vorhanden.*dublette\.pdf/is)).toBeInTheDocument()
  })

  it('hides the upload area for a library where the user only has VIEWER, even with an editable personal library also present', async () => {
    setLibraries([personalLibrary, viewerLibrary])
    setDocuments({
      'library-personal': [indexedDocument],
      'library-readonly': [failedDocument],
    })
    renderWithProviders(<DocumentsPage />)
    const user = userEvent.setup()

    expect(await screen.findByRole('button', { name: /dateien hochladen/i })).toBeInTheDocument()

    await user.click(screen.getByRole('combobox', { name: /^bibliothek$/i }))
    await user.click(await screen.findByRole('option', { name: /dienstanweisungen/i }))

    expect(await screen.findByText('vermerk.pptx')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /dateien hochladen/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /dokument .* löschen/i })).not.toBeInTheDocument()
    expect(screen.getByText(/nur leserechte/i)).toBeInTheDocument()
  })

  it('deletes a document after confirmation and removes it from the list', async () => {
    setLibraries([personalLibrary])
    setDocuments({ 'library-personal': [indexedDocument] })
    renderWithProviders(<DocumentsPage />)
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    await screen.findByText('dienstanweisung-2024.pdf')
    await user.click(
      screen.getByRole('button', { name: /dokument dienstanweisung-2024\.pdf löschen/i }),
    )

    await waitFor(() => {
      expect(mockDeleteLibraryDocument).toHaveBeenCalledWith('library-personal', 'document-1')
    })
    expect(screen.queryByText('dienstanweisung-2024.pdf')).not.toBeInTheDocument()
  })

  it('shows a German error and keeps the document listed when deletion fails', async () => {
    setLibraries([personalLibrary])
    setDocuments({ 'library-personal': [indexedDocument] })
    mockDeleteLibraryDocument.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Bibliothek'))
    renderWithProviders(<DocumentsPage />)
    const user = userEvent.setup()
    vi.spyOn(window, 'confirm').mockReturnValue(true)

    await screen.findByText('dienstanweisung-2024.pdf')
    await user.click(
      screen.getByRole('button', { name: /dokument dienstanweisung-2024\.pdf löschen/i }),
    )

    expect(await screen.findByText('Kein Zugriff auf diese Bibliothek')).toBeInTheDocument()
    expect(screen.getByText('dienstanweisung-2024.pdf')).toBeInTheDocument()
  })

  it('marks a FAILED document as failed', async () => {
    setLibraries([personalLibrary])
    setDocuments({ 'library-personal': [failedDocument] })
    renderWithProviders(<DocumentsPage />)

    const row = (await screen.findByText('vermerk.pptx')).closest('div')
    expect(row).toBeTruthy()
    expect(
      within(row!.parentElement as HTMLElement).getByText(/fehlgeschlagen/i),
    ).toBeInTheDocument()
  })
})
