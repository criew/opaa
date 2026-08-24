import { AxiosError } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDocumentStore } from './documentStore'
import { resetAllStores } from './resettableStores'
import type { LibraryDocumentPageResponse, LibraryDocumentResponse } from '../types/api'

// #822 review, finding 2: normalizeError (services/api.ts) attaches the original AxiosError as
// `cause` - mirrors that shape here so isNotFoundError (documentStore.ts) has something real to
// distinguish a 404 from any other failure, the same way api.test.ts's axiosErrorWithResponse
// stands in for a real AxiosError elsewhere in this codebase.
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

const {
  mockGetLibraryDocuments,
  mockUploadDocument,
  mockDeleteLibraryDocument,
  mockCreateLibraryFolder,
  mockRenameLibraryFolder,
  mockDeleteLibraryFolder,
} = vi.hoisted(() => ({
  mockGetLibraryDocuments: vi.fn(),
  mockUploadDocument: vi.fn(),
  mockDeleteLibraryDocument: vi.fn(),
  mockCreateLibraryFolder: vi.fn(),
  mockRenameLibraryFolder: vi.fn(),
  mockDeleteLibraryFolder: vi.fn(),
}))

vi.mock('../services/api', () => ({
  getLibraryDocuments: mockGetLibraryDocuments,
  uploadDocument: mockUploadDocument,
  deleteLibraryDocument: mockDeleteLibraryDocument,
  createLibraryFolder: mockCreateLibraryFolder,
  renameLibraryFolder: mockRenameLibraryFolder,
  deleteLibraryFolder: mockDeleteLibraryFolder,
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
      folderId: null,
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
      folderId: null,
    })
    expect(useDocumentStore.getState().pageStateByLibrary['library-1']).toEqual({
      page: 2,
      size: 5,
      q: 'dienst',
      totalElements: 42,
      folderId: null,
    })
  })

  it('passes folderId through to the API and stores the requested folder', async () => {
    mockGetLibraryDocuments.mockResolvedValueOnce(page([indexedDocument]))

    await useDocumentStore.getState().loadDocuments('library-1', { folderId: 'folder-1' })

    expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-1', {
      page: 0,
      size: 20,
      q: '',
      folderId: 'folder-1',
    })
    expect(useDocumentStore.getState().pageStateByLibrary['library-1'].folderId).toBe('folder-1')
  })

  it('falls back to the root and surfaces a hint when a folderId is unknown/foreign (404)', async () => {
    mockGetLibraryDocuments.mockRejectedValueOnce(notFoundError())
    mockGetLibraryDocuments.mockResolvedValueOnce(page([indexedDocument]))

    await useDocumentStore.getState().loadDocuments('library-1', { folderId: 'unknown-folder' })

    expect(mockGetLibraryDocuments).toHaveBeenNthCalledWith(2, 'library-1', {
      page: 0,
      size: 20,
      q: '',
      folderId: null,
    })
    expect(useDocumentStore.getState().pageStateByLibrary['library-1'].folderId).toBeNull()
    expect(useDocumentStore.getState().folderNotFoundMessage).not.toBeNull()
    expect(useDocumentStore.getState().error).toBeNull()
  })

  // #822 review, finding 2: a bare 404 message string is not enough evidence to bounce a caller
  // out of the folder they were looking at - only an actual AxiosError-carried 404 status may
  // trigger the root fallback. Anything else (500, network outage, or - as here - an Error that
  // merely happens to read "nicht gefunden" without a 404 cause) must surface as a normal error
  // and leave the requested folder alone.
  it('does not fall back to the root on a non-404 failure, even one that reads "nicht gefunden"', async () => {
    mockGetLibraryDocuments.mockRejectedValueOnce(new Error('Ordner nicht gefunden'))

    await useDocumentStore.getState().loadDocuments('library-1', { folderId: 'folder-1' })

    expect(mockGetLibraryDocuments).toHaveBeenCalledTimes(1)
    expect(useDocumentStore.getState().folderNotFoundMessage).toBeNull()
    expect(useDocumentStore.getState().error).toBe('Ordner nicht gefunden')
    expect(useDocumentStore.getState().isLoading).toBe(false)
  })

  it('resets a stale folderNotFoundMessage once a load succeeds', async () => {
    useDocumentStore.setState({ folderNotFoundMessage: 'Der Ordner wurde nicht gefunden.' })
    mockGetLibraryDocuments.mockResolvedValueOnce(page([indexedDocument]))

    await useDocumentStore.getState().loadDocuments('library-1')

    expect(useDocumentStore.getState().folderNotFoundMessage).toBeNull()
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
      pageStateByLibrary: {
        'library-1': { page: 1, size: 5, q: 'dienst', totalElements: 6, folderId: null },
      },
    })
    mockUploadDocument.mockResolvedValueOnce(pendingDocument)
    mockGetLibraryDocuments.mockResolvedValueOnce(
      page([indexedDocument, pendingDocument], { page: 1, size: 5, totalElements: 7 }),
    )

    await useDocumentStore.getState().uploadNewDocument('library-1', new File(['x'], 'x.pdf'))

    // #823: uploadDocumentRequest's signature grew a trailing folderPath - undefined here, since
    // no folderPath was passed to uploadNewDocument.
    expect(mockUploadDocument).toHaveBeenCalledWith('library-1', expect.any(File), null, undefined)
    expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-1', {
      page: 1,
      size: 5,
      q: 'dienst',
      folderId: null,
    })
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([
      indexedDocument,
      pendingDocument,
    ])
    expect(useDocumentStore.getState().pageStateByLibrary['library-1'].totalElements).toBe(7)
    expect(useDocumentStore.getState().isUploading).toBe(false)
  })

  it('passes a given folderPath through to the request layer, relative to the open folder (#823)', async () => {
    useDocumentStore.setState({
      pageStateByLibrary: {
        'library-1': { page: 0, size: 20, q: '', totalElements: 0, folderId: 'folder-bestand' },
      },
    })
    mockUploadDocument.mockResolvedValueOnce(pendingDocument)
    mockGetLibraryDocuments.mockResolvedValueOnce(page([pendingDocument]))

    await useDocumentStore
      .getState()
      .uploadNewDocument('library-1', new File(['x'], 'protokoll.pdf'), 'Protokolle/2026')

    expect(mockUploadDocument).toHaveBeenCalledWith(
      'library-1',
      expect.any(File),
      'folder-bestand',
      'Protokolle/2026',
    )
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

  it('qualifies the file name with folderPath in the upload error message (#823 review, Befund 5a)', async () => {
    // Disambiguates two same-named files from different subfolders of a dragged-and-dropped tree
    // - without folderPath, both would produce the identical uploadErrors message, which is both
    // ambiguous to read and a duplicate React key where the list is rendered.
    mockUploadDocument.mockRejectedValueOnce(new Error('Diese Datei ist bereits vorhanden'))
    const file = new File(['x'], 'protokoll.pdf')

    await expect(
      useDocumentStore.getState().uploadNewDocument('library-1', file, 'Protokolle/2026'),
    ).rejects.toThrow()

    expect(useDocumentStore.getState().uploadErrors).toEqual([
      'Diese Datei ist bereits vorhanden (Datei: Protokolle/2026/protokoll.pdf)',
    ])
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

  it('reportUploadError appends a message without going through a file upload', () => {
    // #823 review, Befund 2: used by the folder-upload paths (LibraryDetailPage) for a
    // batch-level, client-side rejection (unsupported format, unreadable file) that names no
    // single uploadNewDocument call.
    useDocumentStore.getState().reportUploadError('3 Dateien wurden übersprungen.')

    expect(useDocumentStore.getState().uploadErrors).toEqual(['3 Dateien wurden übersprungen.'])

    useDocumentStore.getState().reportUploadError('1 Datei konnte nicht gelesen werden.')

    expect(useDocumentStore.getState().uploadErrors).toEqual([
      '3 Dateien wurden übersprungen.',
      '1 Datei konnte nicht gelesen werden.',
    ])
  })

  it('reloads the current page from the server after a successful deletion', async () => {
    useDocumentStore.setState({
      documentsByLibrary: { 'library-1': [indexedDocument] },
      pageStateByLibrary: {
        'library-1': { page: 0, size: 20, q: '', totalElements: 2, folderId: null },
      },
    })
    mockDeleteLibraryDocument.mockResolvedValueOnce(undefined)
    mockGetLibraryDocuments.mockResolvedValueOnce(page([], { totalElements: 1 }))

    await useDocumentStore.getState().removeDocument('library-1', 'document-1')

    expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-1', {
      page: 0,
      size: 20,
      q: '',
      folderId: null,
    })
    expect(useDocumentStore.getState().documentsByLibrary['library-1']).toEqual([])
    expect(useDocumentStore.getState().pageStateByLibrary['library-1'].totalElements).toBe(1)
    expect(useDocumentStore.getState().deleteError).toBeNull()
  })

  it('steps back a page when deleting empties the last page (Szenario A)', async () => {
    useDocumentStore.setState({
      documentsByLibrary: { 'library-1': [indexedDocument] },
      pageStateByLibrary: {
        'library-1': { page: 2, size: 1, q: '', totalElements: 21, folderId: null },
      },
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
      folderId: null,
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

  describe('folder CRUD (#822)', () => {
    it('creates a folder under the currently open folder and reloads the current page', async () => {
      useDocumentStore.setState({
        pageStateByLibrary: {
          'library-1': { page: 0, size: 20, q: '', totalElements: 0, folderId: 'folder-parent' },
        },
      })
      mockCreateLibraryFolder.mockResolvedValueOnce({
        id: 'folder-new',
        libraryId: 'library-1',
        parentFolderId: 'folder-parent',
        name: 'Archiv',
        documentCount: 0,
        createdAt: '2026-03-01T10:00:00Z',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(page([]))

      await useDocumentStore.getState().createFolder('library-1', 'Archiv')

      expect(mockCreateLibraryFolder).toHaveBeenCalledWith('library-1', {
        name: 'Archiv',
        parentFolderId: 'folder-parent',
      })
      expect(mockGetLibraryDocuments).toHaveBeenCalledWith('library-1', {
        page: 0,
        size: 20,
        q: '',
        folderId: 'folder-parent',
      })
      expect(useDocumentStore.getState().folderError).toBeNull()
    })

    it('surfaces a 409 name conflict as folderError and rethrows without reloading', async () => {
      mockCreateLibraryFolder.mockRejectedValueOnce(
        new Error('Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene'),
      )

      await expect(
        useDocumentStore.getState().createFolder('library-1', 'Protokolle'),
      ).rejects.toThrow()

      expect(useDocumentStore.getState().folderError).toBe(
        'Ein Ordner mit diesem Namen existiert bereits auf dieser Ebene',
      )
      expect(mockGetLibraryDocuments).not.toHaveBeenCalled()
    })

    it('renames a folder and reloads the current page', async () => {
      mockRenameLibraryFolder.mockResolvedValueOnce({
        id: 'folder-1',
        libraryId: 'library-1',
        parentFolderId: null,
        name: 'Protokolle 2026',
        documentCount: 3,
        createdAt: '2026-03-01T10:00:00Z',
      })
      mockGetLibraryDocuments.mockResolvedValueOnce(page([]))

      await useDocumentStore.getState().renameFolder('library-1', 'folder-1', 'Protokolle 2026')

      expect(mockRenameLibraryFolder).toHaveBeenCalledWith('library-1', 'folder-1', {
        name: 'Protokolle 2026',
      })
      expect(useDocumentStore.getState().folderError).toBeNull()
    })

    it('deletes a folder and reloads the current page', async () => {
      mockDeleteLibraryFolder.mockResolvedValueOnce(undefined)
      mockGetLibraryDocuments.mockResolvedValueOnce(page([]))

      await useDocumentStore.getState().removeFolder('library-1', 'folder-1')

      expect(mockDeleteLibraryFolder).toHaveBeenCalledWith('library-1', 'folder-1')
      expect(mockGetLibraryDocuments).toHaveBeenCalled()
      expect(useDocumentStore.getState().folderError).toBeNull()
    })
  })
})
