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

  it('names the file in the upload error message, appends it to uploadErrors and rethrows', async () => {
    mockUploadDocument.mockRejectedValueOnce(new Error('Diese Datei ist bereits vorhanden'))
    const file = new File(['x'], 'doppelt.pdf')

    await expect(useDocumentStore.getState().uploadNewDocument('library-1', file)).rejects.toThrow()

    expect(useDocumentStore.getState().uploadErrors).toEqual([
      'Diese Datei ist bereits vorhanden (Datei: doppelt.pdf)',
    ])
    expect(useDocumentStore.getState().isUploading).toBe(false)
  })

  it('keeps an earlier upload error when a later file in the same batch succeeds', async () => {
    mockUploadDocument.mockRejectedValueOnce(new Error('Ursache A'))
    await expect(
      useDocumentStore.getState().uploadNewDocument('library-1', new File(['x'], 'a.pdf')),
    ).rejects.toThrow()

    mockUploadDocument.mockResolvedValueOnce(indexedDocument)
    await useDocumentStore.getState().uploadNewDocument('library-1', new File(['x'], 'b.pdf'))

    expect(useDocumentStore.getState().uploadErrors).toEqual(['Ursache A (Datei: a.pdf)'])
  })

  it('collects errors from multiple failed files in the same batch instead of overwriting', async () => {
    mockUploadDocument
      .mockRejectedValueOnce(new Error('Ursache A'))
      .mockRejectedValueOnce(new Error('Ursache B'))

    await expect(
      useDocumentStore.getState().uploadNewDocument('library-1', new File(['x'], 'a.pdf')),
    ).rejects.toThrow()
    await expect(
      useDocumentStore.getState().uploadNewDocument('library-1', new File(['x'], 'b.pdf')),
    ).rejects.toThrow()

    expect(useDocumentStore.getState().uploadErrors).toEqual([
      'Ursache A (Datei: a.pdf)',
      'Ursache B (Datei: b.pdf)',
    ])
  })

  it('clears uploadErrors only via clearUploadErrors', async () => {
    mockUploadDocument.mockRejectedValueOnce(new Error('Ursache A'))
    await expect(
      useDocumentStore.getState().uploadNewDocument('library-1', new File(['x'], 'a.pdf')),
    ).rejects.toThrow()
    expect(useDocumentStore.getState().uploadErrors).toHaveLength(1)

    useDocumentStore.getState().clearUploadErrors()

    expect(useDocumentStore.getState().uploadErrors).toEqual([])
  })

  it('removes a document after successful deletion', async () => {
    useDocumentStore.setState({ documentsByLibrary: { 'library-1': [indexedDocument] } })
    mockDeleteLibraryDocument.mockResolvedValueOnce(undefined)

    await useDocumentStore.getState().removeDocument('library-1', 'document-1')

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([])
    expect(useDocumentStore.getState().deleteError).toBeNull()
  })

  it('shows a German error and keeps the document listed when deletion fails', async () => {
    useDocumentStore.setState({ documentsByLibrary: { 'library-1': [indexedDocument] } })
    mockDeleteLibraryDocument.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Bibliothek'))

    await expect(
      useDocumentStore.getState().removeDocument('library-1', 'document-1'),
    ).rejects.toThrow()

    expect(useDocumentStore.getState().deleteError).toBe('Kein Zugriff auf diese Bibliothek')
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([indexedDocument])
  })

  it('clears deleteError only via clearDeleteError', async () => {
    mockDeleteLibraryDocument.mockRejectedValueOnce(new Error('Kein Zugriff auf diese Bibliothek'))
    await expect(
      useDocumentStore.getState().removeDocument('library-1', 'document-1'),
    ).rejects.toThrow()
    expect(useDocumentStore.getState().deleteError).not.toBeNull()

    useDocumentStore.getState().clearDeleteError()

    expect(useDocumentStore.getState().deleteError).toBeNull()
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

  it('stops every running poll interval on reset, not just the last-loaded library', async () => {
    vi.useFakeTimers()
    mockGetLibraryDocuments.mockResolvedValueOnce([pendingDocument])
    await useDocumentStore.getState().loadDocuments('library-1')
    mockGetLibraryDocuments.mockResolvedValueOnce([pendingDocument])
    await useDocumentStore.getState().loadDocuments('library-2')

    useDocumentStore.getState().reset()

    mockGetLibraryDocuments.mockClear()
    await vi.advanceTimersByTimeAsync(10000)
    expect(mockGetLibraryDocuments).not.toHaveBeenCalled()
  })
})
