import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDocumentStore } from './documentStore'
import { resetAllStores } from './resettableStores'
import type { LibraryDocumentPageResponse, LibraryDocumentResponse } from '../types/api'

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

function page(
  items: LibraryDocumentResponse[],
  overrides?: Partial<LibraryDocumentPageResponse>,
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
    mockGetLibraryDocuments.mockResolvedValueOnce(page([indexedDocument]))

    await useDocumentStore.getState().loadDocuments('library-1')

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([indexedDocument])
    expect(useDocumentStore.getState().pageStateByLibrary['library-1']).toEqual({
      page: 0,
      size: 20,
      q: '',
      totalElements: 1,
    })
    expect(useDocumentStore.getState().isLoading).toBe(false)
    expect(useDocumentStore.getState().error).toBeNull()
  })

  it('passes page/size/q through to the API and stores the response paging state', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce(
      page([indexedDocument], { page: 2, size: 5, totalElements: 42 }),
    )

    await useDocumentStore.getState().loadDocuments('library-1', { page: 2, size: 5, q: 'dienst' })

    expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-1', {
      page: 2,
      size: 5,
      q: 'dienst',
    })
    expect(useDocumentStore.getState().pageStateByLibrary['library-1']).toEqual({
      page: 2,
      size: 5,
      q: 'dienst',
      totalElements: 42,
    })
  })

  it('shows a German error message when loading fails', async () => {
    mockGetLibraryDocuments.mockRejectedValueOnce(new Error('Zugriff verweigert'))

    await useDocumentStore.getState().loadDocuments('library-1')

    expect(useDocumentStore.getState().error).toBe('Zugriff verweigert')
  })

  it('reloads the current page from the server after a successful upload', async () => {
    // #517 code review, finding 2 (Szenario C): a local prepend cannot know whether the new
    // document actually belongs on the page/search the user is looking at - only the server can.
    useDocumentStore.setState({
      documentsByLibrary: { 'library-1': [indexedDocument] },
      pageStateByLibrary: { 'library-1': { page: 1, size: 5, q: 'dienst', totalElements: 6 } },
    })
    mockUploadDocument.mockResolvedValueOnce(pendingDocument)
    mockGetLibraryDocuments.mockResolvedValueOnce(
      page([indexedDocument, pendingDocument], { page: 1, size: 5, totalElements: 7 }),
    )

    await useDocumentStore.getState().uploadNewDocument('library-1', new File(['x'], 'x.pdf'))

    expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-1', {
      page: 1,
      size: 5,
      q: 'dienst',
    })
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([
      indexedDocument,
      pendingDocument,
    ])
    expect(useDocumentStore.getState().pageStateByLibrary['library-1'].totalElements).toBe(7)
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

  it('reloads the current page from the server after a successful deletion', async () => {
    useDocumentStore.setState({
      documentsByLibrary: { 'library-1': [indexedDocument] },
      pageStateByLibrary: { 'library-1': { page: 0, size: 20, q: '', totalElements: 2 } },
    })
    mockDeleteLibraryDocument.mockResolvedValueOnce(undefined)
    mockGetLibraryDocuments.mockResolvedValueOnce(page([], { totalElements: 1 }))

    await useDocumentStore.getState().removeDocument('library-1', 'document-1')

    expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-1', {
      page: 0,
      size: 20,
      q: '',
    })
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([])
    expect(useDocumentStore.getState().pageStateByLibrary['library-1'].totalElements).toBe(1)
    expect(useDocumentStore.getState().deleteError).toBeNull()
  })

  it('steps back a page when deleting empties the last page (Szenario A)', async () => {
    useDocumentStore.setState({
      documentsByLibrary: { 'library-1': [indexedDocument] },
      pageStateByLibrary: { 'library-1': { page: 2, size: 1, q: '', totalElements: 21 } },
    })
    mockDeleteLibraryDocument.mockResolvedValueOnce(undefined)
    // The reload of the now-deleted page 2 comes back empty (nothing left to show there)...
    mockGetLibraryDocuments.mockResolvedValueOnce(page([], { page: 2, size: 1, totalElements: 20 }))
    // ...so removeDocument steps back to page 1, which still has content.
    const previousPageDocument = { ...indexedDocument, id: 'document-3' }
    mockGetLibraryDocuments.mockResolvedValueOnce(
      page([previousPageDocument], { page: 1, size: 1, totalElements: 20 }),
    )

    await useDocumentStore.getState().removeDocument('library-1', 'document-1')

    expect(mockGetLibraryDocuments).toHaveBeenNthCalledWith(2, 'library-1', {
      page: 1,
      size: 1,
      q: '',
    })
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([
      previousPageDocument,
    ])
    expect(useDocumentStore.getState().pageStateByLibrary['library-1'].page).toBe(1)
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
    mockGetLibraryDocuments.mockResolvedValueOnce(page([pendingDocument]))

    await useDocumentStore.getState().loadDocuments('library-1')
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([pendingDocument])

    mockGetLibraryDocuments.mockResolvedValueOnce(page([indexedDocument]))
    await vi.advanceTimersByTimeAsync(3000)

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([indexedDocument])
  })

  it('stops every running poll interval on reset, not just the last-loaded library', async () => {
    vi.useFakeTimers()
    mockGetLibraryDocuments.mockResolvedValueOnce(page([pendingDocument]))
    await useDocumentStore.getState().loadDocuments('library-1')
    mockGetLibraryDocuments.mockResolvedValueOnce(page([pendingDocument]))
    await useDocumentStore.getState().loadDocuments('library-2')

    useDocumentStore.getState().reset()

    mockGetLibraryDocuments.mockClear()
    await vi.advanceTimersByTimeAsync(10000)
    expect(mockGetLibraryDocuments).not.toHaveBeenCalled()
  })

  // #575: found while systematically checking the resettableStores registry for further
  // unguarded async set() paths beyond the ones the issue named explicitly.
  it('a loadDocuments response arriving after a session reset does not resurrect documents', async () => {
    mockGetLibraryDocuments.mockImplementationOnce(async () => {
      resetAllStores()
      return page([indexedDocument])
    })

    await useDocumentStore.getState().loadDocuments('library-1')

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toBeUndefined()
    expect(useDocumentStore.getState().isLoading).toBe(false)
  })

  it('a poll tick response arriving after a session reset does not resurrect documents', async () => {
    vi.useFakeTimers()
    mockGetLibraryDocuments.mockResolvedValueOnce(page([pendingDocument]))
    await useDocumentStore.getState().loadDocuments('library-1')
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([pendingDocument])

    // The reset happens *inside* the tick's own request - by the time it resolves and the tick's
    // continuation runs, resetAllStores() has already bumped the session epoch. clearInterval (via
    // reset()'s stopPolling loop) only stops *future* ticks, so this proves the in-flight one is
    // still guarded.
    mockGetLibraryDocuments.mockImplementationOnce(async () => {
      resetAllStores()
      return page([indexedDocument])
    })
    await vi.advanceTimersByTimeAsync(3000)

    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toBeUndefined()
  })
})
