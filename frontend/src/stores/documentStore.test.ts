import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDocumentStore } from './documentStore'
import type { LibraryDocumentResponse } from '../types/api'

const { mockGetLibraryDocuments, mockUploadDocument, mockDeleteLibraryDocument } = vi.hoisted(
  () => ({
    mockGetLibraryDocuments: vi.fn(),
    mockUploadDocument: vi.fn(),
    mockDeleteLibraryDocument: vi.fn(),
  }),
)

vi.mock('../services/api', () => ({
  getLibraryDocuments: mockGetLibraryDocuments,
  uploadDocument: mockUploadDocument,
  deleteLibraryDocument: mockDeleteLibraryDocument,
}))

const indexedDocument: LibraryDocumentResponse = {
  id: 'document-1',
  fileName: 'dienstanweisung-2024.pdf',
  contentType: 'application/pdf',
  fileSize: 1000,
  status: 'INDEXED',
  sourceType: 'UPLOAD',
  chunkCount: 12,
  indexedAt: '2026-03-01T10:00:00Z',
  uploadedByUserId: 'mock-user-id',
}

const pendingDocument: LibraryDocumentResponse = {
  ...indexedDocument,
  id: 'document-2',
  status: 'PENDING',
  chunkCount: 0,
  indexedAt: null,
}

describe('documentStore', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useDocumentStore.getState().reset()
  })

  afterEach(() => {
    useDocumentStore.getState().reset()
    vi.useRealTimers()
  })

  it('loads documents for a library', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce([indexedDocument])

    await useDocumentStore.getState().loadDocuments('library-1')

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([indexedDocument])
    expect(useDocumentStore.getState().isLoading).toBe(false)
    expect(useDocumentStore.getState().error).toBeNull()
  })

  it('shows a German error message when loading fails', async () => {
    mockGetLibraryDocuments.mockRejectedValueOnce(new Error('Zugriff verweigert'))

    await useDocumentStore.getState().loadDocuments('library-1')

    expect(useDocumentStore.getState().error).toBe('Zugriff verweigert')
  })

  it('prepends an uploaded document to the existing list', async () => {
    useDocumentStore.setState({ documentsByLibrary: { 'library-1': [indexedDocument] } })
    mockUploadDocument.mockResolvedValueOnce(pendingDocument)

    await useDocumentStore.getState().uploadNewDocument('library-1', new File(['x'], 'x.pdf'))

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([
      pendingDocument,
      indexedDocument,
    ])
    expect(useDocumentStore.getState().isUploading).toBe(false)
  })

  it('names the file in the upload error message and rethrows', async () => {
    mockUploadDocument.mockRejectedValueOnce(new Error('Diese Datei ist bereits vorhanden'))
    const file = new File(['x'], 'doppelt.pdf')

    await expect(useDocumentStore.getState().uploadNewDocument('library-1', file)).rejects.toThrow()

    expect(useDocumentStore.getState().uploadError).toBe(
      'Diese Datei ist bereits vorhanden (Datei: doppelt.pdf)',
    )
    expect(useDocumentStore.getState().isUploading).toBe(false)
  })

  it('removes a document after successful deletion', async () => {
    useDocumentStore.setState({ documentsByLibrary: { 'library-1': [indexedDocument] } })
    mockDeleteLibraryDocument.mockResolvedValueOnce(undefined)

    await useDocumentStore.getState().removeDocument('library-1', 'document-1')

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([])
  })

  it('polls until no document is PENDING anymore, then stops', async () => {
    vi.useFakeTimers()
    mockGetLibraryDocuments.mockResolvedValueOnce([pendingDocument])

    await useDocumentStore.getState().loadDocuments('library-1')
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([pendingDocument])

    mockGetLibraryDocuments.mockResolvedValueOnce([indexedDocument])
    await vi.advanceTimersByTimeAsync(3000)

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([indexedDocument])
  })
})
