import { http, HttpResponse } from 'msw'
import {
  mockLibraries,
  mockLibraryDetails,
  mockLibraryDocuments,
  mockLibraryFolders,
  resetMockLibraryDocuments,
  resetMockLibraryFolders,
} from './libraryFixtures'
import { mockDocumentMetadata, resetMockDocumentMetadata } from './libraryMetadataFixtures'
import type {
  SourceTypeKey,
  DocumentStatus,
  LibraryDocumentResponse,
  LibraryFolderBreadcrumbItem,
  LibraryFolderListItem,
} from '../types/api'
import { countMockFolderDocuments } from './libraryFolderHandlers'
import { canManageMockLibrary } from './libraryHandlers'

// Mirrors what the registered DocumentFormats admit (DocumentFormat#admittedFormats,
// backend/src/main/java/io/opaa/format) - kept as a literal list here rather than
// importing across the frontend/backend boundary.
const SUPPORTED_DOCUMENT_EXTENSIONS = [
  '.csv',
  '.doc',
  '.docx',
  '.eml',
  '.html',
  '.md',
  '.msg',
  '.odp',
  '.ods',
  '.odt',
  '.pdf',
  '.pptx',
  '.txt',
  '.xlsx',
]
const MAX_UPLOAD_SIZE_BYTES = 50 * 1024 * 1024
const documentPollCounts = new Map<string, number>()
// Upload ids that should resolve to FAILED, not INDEXED, the next time the documents
// GET handler below advances them past PENDING - see the POST handler's isEmptyContent check.
const documentsPendingFailure = new Set<string>()
const EMPTY_CONTENT_ERROR_MESSAGE = 'Aus der Datei konnte kein Text extrahiert werden'

export function resetDocumentMockState() {
  documentPollCounts.clear()
  documentsPendingFailure.clear()
  resetMockLibraryDocuments()
  resetMockLibraryFolders()
  resetMockDocumentMetadata()
}

function listMockSubfolders(libraryId: string, folderId: string | null): LibraryFolderListItem[] {
  return (mockLibraryFolders[libraryId] ?? [])
    .filter((folder) => (folder.parentFolderId ?? null) === folderId)
    .map((folder) => ({
      id: folder.id,
      name: folder.name,
      documentCount: countMockFolderDocuments(libraryId, folder.id),
    }))
}

function buildMockBreadcrumb(
  libraryId: string,
  folderId: string | null,
): LibraryFolderBreadcrumbItem[] {
  const folders = mockLibraryFolders[libraryId] ?? []
  const chain: LibraryFolderBreadcrumbItem[] = []
  let current = folderId
  while (current) {
    const folder = folders.find((f) => f.id === current)
    if (!folder) break
    chain.unshift({ id: folder.id, name: folder.name })
    current = folder.parentFolderId
  }
  return chain
}

//  review, finding 6b: the real backend derives folderPath from the full folder chain (e.g.
// "Protokolle/2026"), not just the immediate folder's own name - reuses buildMockBreadcrumb above
// so the two never drift apart.
//
// Exported so handlers.test.ts can exercise this directly rather than through a real multipart
// upload request: a File/Blob request body hangs indefinitely against msw/node in this project's
// jsdom test environment (see the block comment on the documents describe block in that file).
export function buildMockFolderPath(libraryId: string, folderId: string | null): string | null {
  if (!folderId) return null
  const chain = buildMockBreadcrumb(libraryId, folderId)
  return chain.length > 0 ? chain.map((item) => item.name).join('/') : null
}

// mirrors LibraryFolderService#resolveOrCreateFolderPath - idempotently materializes the
// folder chain a dragged-and-dropped/webkitdirectory-selected upload's folderPath describes,
// relative to baseFolderId, reusing an existing folder of the same name at each level rather than
// creating a duplicate.
//
// Exported for the same reason as buildMockFolderPath above: handlers.test.ts cannot exercise a
// real multipart upload request in this project's jsdom test environment.
// Mirrors LibraryFolderService's MAX_DEPTH ( review, Befund 5d) - root counts as depth 1.
const MOCK_MAX_FOLDER_DEPTH = 10

export function resolveOrCreateMockFolderPath(
  libraryId: string,
  baseFolderId: string | null,
  folderPath: string,
): { folderId: string | null } | { error: string; status: number } {
  const segments = folderPath.split('/').filter((segment) => segment.trim() !== '')

  //  review, Befund 1/5d: every segment (and the resulting depth) is validated in this own
  // upfront pass, before any folder is created - mirrors LibraryFolderService#
  // resolveOrCreateFolderPath's identical two-pass structure (validate the whole chain, then
  // materialize it), so an invalid later segment or a depth overrun never leaves an earlier,
  // valid segment's folder behind.
  const names: string[] = []
  let depth = baseFolderId ? buildMockBreadcrumb(libraryId, baseFolderId).length : 0
  for (const rawSegment of segments) {
    const name = rawSegment.trim()
    // Mirrors LibraryFolderService#validatePathSegment: trimmed, no further separator, no
    // relative-path traversal segment.
    if (name.length === 0 || name.length > 255) {
      return { error: 'name darf höchstens 255 Zeichen umfassen', status: 400 }
    }
    if (name.includes('\\')) {
      return { error: 'Ordnername darf kein "\\" enthalten', status: 400 }
    }
    if (name === '..' || name === '.') {
      return { error: 'Ordnername darf nicht ".." oder "." lauten', status: 400 }
    }
    depth += 1
    if (depth > MOCK_MAX_FOLDER_DEPTH) {
      return {
        error: `Die Ordnerstruktur ist zu tief verschachtelt (maximal ${MOCK_MAX_FOLDER_DEPTH} Ebenen)`,
        status: 400,
      }
    }
    names.push(name)
  }

  let parentFolderId = baseFolderId
  for (const name of names) {
    const existing = mockLibraryFolders[libraryId] ?? []
    let folder = existing.find(
      (f) => (f.parentFolderId ?? null) === parentFolderId && f.name === name,
    )
    if (!folder) {
      folder = {
        id: `folder-${crypto.randomUUID().slice(0, 8)}`,
        libraryId,
        parentFolderId,
        name,
        createdAt: new Date().toISOString(),
      }
      mockLibraryFolders[libraryId] = [...existing, folder]
    }
    parentFolderId = folder.id
  }
  return { folderId: parentFolderId }
}

export const libraryDocumentHandlers = [
  http.get('/api/v1/libraries/:libraryId/documents', ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    const allDocuments = mockLibraryDocuments[libraryId] ?? []
    // Simulates the indexing pipeline resolving a freshly uploaded document after a couple of
    // polls, mirroring the INDEXING_POLL_STEPS pattern of the indexing handlers - lets tests exercise the "PENDING
    // until the list refresh settles" acceptance criterion without staying PENDING forever.
    allDocuments.forEach((doc) => {
      if (doc.status !== 'PENDING') return
      const pollCount = (documentPollCounts.get(doc.id) ?? 0) + 1
      documentPollCounts.set(doc.id, pollCount)
      if (pollCount >= 2) {
        if (documentsPendingFailure.delete(doc.id)) {
          doc.status = 'FAILED'
          doc.errorMessage = EMPTY_CONTENT_ERROR_MESSAGE
        } else {
          doc.status = 'INDEXED'
          doc.chunkCount = 12
          doc.indexedAt = new Date().toISOString()
        }
      }
    })

    // Mirrors LibraryController#listDocuments / KnowledgeLibraryService#listDocuments: page/
    // size/q query params, a case-insensitive substring match on fileName, and the paged response
    // envelope { items, page, size, totalElements }.
    const url = new URL(request.url)
    const q = url.searchParams.get('q')
    const page = Number(url.searchParams.get('page') ?? '0')
    const size = Number(url.searchParams.get('size') ?? '20')
    const folderIdParam = url.searchParams.get('folderId')
    const missingMetadataField = url.searchParams.get('missingMetadataField')

    // folderId is validated with or without q, mirroring GET .../folders/{folderId}'s own
    // unknown/foreign-folder 404 (ADR-0020).
    if (
      folderIdParam &&
      !(mockLibraryFolders[libraryId] ?? []).some((folder) => folder.id === folderIdParam)
    ) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }

    //  (ADR-0022, Entscheidung 5): attachments (parentDocumentId set) never page, sort or
    // count on their own - paging operates on top-level documents, and each returned top-level
    // document brings its complete (transitive) attachment subtree along, right after itself.
    const descendantsOf = (parentId: string): LibraryDocumentResponse[] =>
      allDocuments
        .filter((doc) => doc.parentDocumentId === parentId)
        .flatMap((child) => [child, ...descendantsOf(child.id)])
    const topLevelDocuments = allDocuments.filter((doc) => !doc.parentDocumentId)

    let filtered: typeof allDocuments
    let folders: LibraryFolderListItem[]
    let breadcrumb: LibraryFolderBreadcrumbItem[]
    let responseFolderId: string | null

    if (missingMetadataField) {
      // the Pflege-Anker's list - bibliotheksweit like a search, one entry per document row
      // without a value for the field (a "kein Wert ermittelbar" mark is not empty), attachments
      // in their own right rather than grouped, so the list length equals the anchor's number.
      const isEmptyFor = (doc: LibraryDocumentResponse) =>
        ((mockDocumentMetadata[doc.id] ?? []).find(
          (field) => field.fieldKey === missingMetadataField,
        )?.state ?? 'EMPTY') === 'EMPTY'
      const matching = allDocuments.filter(
        (doc) => (!q || doc.fileName.toLowerCase().includes(q.toLowerCase())) && isEmptyFor(doc),
      )
      return HttpResponse.json({
        items: matching.slice(page * size, page * size + size),
        page,
        size,
        totalElements: matching.length,
        folderId: null,
        folders: [],
        breadcrumb: [],
      })
    } else if (q) {
      // Search is always bibliotheksweit, regardless of folderId (ADR-0020, Entscheidung 4) -
      // folders/breadcrumb stay empty, folderId is echoed back as null. A hit on an attachment's
      // file name surfaces its top-level parent with the whole group.
      const matches = (doc: LibraryDocumentResponse) =>
        doc.fileName.toLowerCase().includes(q.toLowerCase())
      filtered = topLevelDocuments.filter(
        (doc) => matches(doc) || descendantsOf(doc.id).some(matches),
      )
      folders = []
      breadcrumb = []
      responseFolderId = null
    } else {
      responseFolderId = folderIdParam
      filtered = topLevelDocuments.filter((doc) => (doc.folderId ?? null) === responseFolderId)
      folders = listMockSubfolders(libraryId, responseFolderId)
      breadcrumb = buildMockBreadcrumb(libraryId, responseFolderId)
    }
    const items = filtered
      .slice(page * size, page * size + size)
      .flatMap((doc) => [doc, ...descendantsOf(doc.id)])

    return HttpResponse.json({
      items,
      page,
      size,
      totalElements: filtered.length,
      folderId: responseFolderId,
      folders,
      breadcrumb,
    })
  }),

  http.post('/api/v1/libraries/:libraryId/documents', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    if (!mockLibraryDetails[libraryId]) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    // Mirrors LibraryDocumentService#requireUploadLibrary (ADR-0018 Entscheidung 1): only a
    // UPLOAD library accepts manually uploaded files - a connector library's content comes
    // exclusively from its own indexing run.
    if (mockLibraryDetails[libraryId]?.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        {
          error:
            'Diese Bibliothek ist eine Konnektorbibliothek und akzeptiert keine manuellen Uploads',
        },
        { status: 409 },
      )
    }
    const formData = await request.formData()
    const file = formData.get('file')
    if (!(file instanceof File) || file.size === 0) {
      return HttpResponse.json({ error: 'Datei ist erforderlich' }, { status: 400 })
    }
    // an omitted/empty folderId means the library's root, mirroring GET on this same path.
    const folderIdField = formData.get('folderId')
    const folderId = typeof folderIdField === 'string' && folderIdField ? folderIdField : null
    if (
      folderId &&
      !(mockLibraryFolders[libraryId] ?? []).some((folder) => folder.id === folderId)
    ) {
      return HttpResponse.json({ error: 'Ordner nicht gefunden' }, { status: 404 })
    }
    // folderPath (if given) is relative to folderId - its intermediate folders are created
    // idempotently, mirroring LibraryDocumentService#uploadDocument/LibraryFolderService#
    // resolveOrCreateFolderPath.
    const folderPathField = formData.get('folderPath')
    const folderPath = typeof folderPathField === 'string' ? folderPathField : ''
    let effectiveFolderId = folderId
    if (folderPath.trim() !== '') {
      const resolved = resolveOrCreateMockFolderPath(libraryId, folderId, folderPath)
      if ('error' in resolved) {
        return HttpResponse.json({ error: resolved.error }, { status: resolved.status })
      }
      effectiveFolderId = resolved.folderId
    }
    if (file.size > MAX_UPLOAD_SIZE_BYTES) {
      return HttpResponse.json(
        {
          error: `Die Datei ist zu groß. Erlaubt sind höchstens ${MAX_UPLOAD_SIZE_BYTES / (1024 * 1024)} MB`,
        },
        { status: 413 },
      )
    }
    const lowerCasedName = file.name.toLowerCase()
    if (!SUPPORTED_DOCUMENT_EXTENSIONS.some((ext) => lowerCasedName.endsWith(ext))) {
      return HttpResponse.json(
        {
          error: `Das Dateiformat wird nicht unterstützt. Erlaubt sind: ${SUPPORTED_DOCUMENT_EXTENSIONS.join(', ')}`,
        },
        { status: 400 },
      )
    }
    // Mirrors DocumentIngestService#processUploadedFileAsync finding no extractable content
    //: since the upload endpoint moved off the request thread, this is no longer a
    // synchronous 422 - the row is returned PENDING like any other upload and only turns FAILED
    // once the (simulated) asynchronous processing below resolves it, with the same German
    // errorMessage the real endpoint records.
    const textContent = await file.text()
    const isEmptyContent = textContent.trim() === ''
    const existing = mockLibraryDocuments[libraryId] ?? []
    // Mirrors LibraryDocumentService#uploadDocument: dedup is scoped per library and keyed on
    // content, approximated here by file name since MSW fixtures do not carry a real checksum.
    if (existing.some((doc) => doc.fileName === file.name)) {
      return HttpResponse.json(
        { error: 'Diese Datei ist bereits in dieser Bibliothek vorhanden' },
        { status: 409 },
      )
    }
    const documentId = `document-${crypto.randomUUID().slice(0, 8)}`
    const document: (typeof existing)[number] = {
      id: documentId,
      fileName: file.name,
      contentType: file.type || null,
      fileSize: file.size,
      status: 'PENDING' as DocumentStatus,
      sourceType: 'UPLOAD' as SourceTypeKey,
      chunkCount: 0,
      indexedAt: null,
      uploadedByUserId: 'mock-user-id',
      folderId: effectiveFolderId,
      folderPath: buildMockFolderPath(libraryId, effectiveFolderId),
    }
    if (isEmptyContent) {
      // Resolved to FAILED, not INDEXED, the next time this document is polled (see the
      // documents GET handler below) - mirrors DocumentIngestService#processUploadedFileAsync
      // finding an empty parse result.
      documentsPendingFailure.add(documentId)
    }
    mockLibraryDocuments[libraryId] = [document, ...existing]
    const detail = mockLibraryDetails[libraryId]
    if (detail) {
      detail.documentCount = (detail.documentCount ?? 0) + 1
    }
    const listEntry = mockLibraries.find((item) => item.id === libraryId)
    if (listEntry) {
      listEntry.documentCount = (listEntry.documentCount ?? 0) + 1
    }
    return HttpResponse.json(document, { status: 201 })
  }),

  // #1943: Sammellöschen - jede Id für sich, Erfolge und Fehlschläge getrennt gemeldet.
  http.post('/api/v1/libraries/:libraryId/documents/bulk-delete', async ({ params, request }) => {
    const libraryId = String(params.libraryId)
    const existing = mockLibraryDocuments[libraryId]
    const detail = mockLibraryDetails[libraryId]
    if (!detail || !existing) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    if (detail.sourceType !== 'UPLOAD') {
      return HttpResponse.json(
        { error: 'Diese Bibliothek verwaltet ihren Bestand über ihre Quelle' },
        { status: 409 },
      )
    }
    const body = (await request.json()) as { documentIds?: string[] }
    const requested = [...new Set(body.documentIds ?? [])]
    const deletedDocumentIds: string[] = []
    const failures: { documentId: string; message: string }[] = []
    for (const documentId of requested) {
      const idx = existing.findIndex((doc) => doc.id === documentId)
      if (idx < 0) {
        failures.push({ documentId, message: 'Dokument nicht gefunden' })
        continue
      }
      existing.splice(idx, 1)
      deletedDocumentIds.push(documentId)
    }
    const listEntry = mockLibraries.find((item) => item.id === libraryId)
    for (const target of [detail, listEntry]) {
      if (target && (target.documentCount ?? 0) > 0) {
        target.documentCount = Math.max(0, (target.documentCount ?? 0) - deletedDocumentIds.length)
      }
    }
    return HttpResponse.json({ deletedDocumentIds, failures })
  }),

  http.delete('/api/v1/libraries/:libraryId/documents/:documentId', ({ params }) => {
    const libraryId = String(params.libraryId)
    const documentId = String(params.documentId)
    const existing = mockLibraryDocuments[libraryId]
    if (!mockLibraryDetails[libraryId] || !existing) {
      return HttpResponse.json({ error: 'Bibliothek nicht gefunden' }, { status: 404 })
    }
    if (!canManageMockLibrary(libraryId)) {
      return HttpResponse.json({ error: 'Kein Zugriff auf diese Bibliothek' }, { status: 403 })
    }
    const idx = existing.findIndex((doc) => doc.id === documentId)
    if (idx < 0) {
      return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
    }
    existing.splice(idx, 1)
    const detail = mockLibraryDetails[libraryId]
    if (detail && (detail.documentCount ?? 0) > 0) {
      detail.documentCount = (detail.documentCount ?? 0) - 1
    }
    const listEntry = mockLibraries.find((item) => item.id === libraryId)
    if (listEntry && (listEntry.documentCount ?? 0) > 0) {
      listEntry.documentCount = (listEntry.documentCount ?? 0) - 1
    }
    return new HttpResponse(null, { status: 204 })
  }),

  // streams a document's original file - mirrors DocumentController's own 404 for a document
  // whose sourceType carries no local file (HTTP_DIRECTORY/RSS_FEED) or that cannot be found across
  // every mocked library at all. UPLOAD/FILESYSTEM answer with a small fake payload plus the same
  // Content-Disposition shape the real endpoint sets, so getDocumentContent's filename parsing has
  // something realistic to exercise against.
  http.get('/api/v1/documents/:documentId/content', ({ params }) => {
    const documentId = String(params.documentId)
    const document = Object.values(mockLibraryDocuments)
      .flat()
      .find((doc) => doc.id === documentId)
    if (!document || (document.sourceType !== 'UPLOAD' && document.sourceType !== 'FILESYSTEM')) {
      return HttpResponse.json({ error: 'Dokument nicht gefunden' }, { status: 404 })
    }
    const libraryId = Object.entries(mockLibraryDocuments).find(([, documents]) =>
      documents.includes(document),
    )?.[0]
    if (libraryId && mockLibraryDetails[libraryId]?.erasureRequestedAt) {
      return HttpResponse.json(
        { error: 'Die Bibliothek wird gelöscht', code: 'LIBRARY_BEING_ERASED' },
        { status: 404 },
      )
    }
    const contentType = document.contentType ?? 'application/octet-stream'
    return new HttpResponse(new Blob(['mock file content'], { type: contentType }), {
      status: 200,
      headers: {
        'Content-Type': contentType,
        'Content-Disposition': `inline; filename="${document.fileName}"`,
      },
    })
  }),
]
