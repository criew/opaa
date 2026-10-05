import { AxiosError } from 'axios'
import type {
  LibraryConnectionProfileRequest,
  LibraryDocumentPageResponse,
  LibraryDocumentResponse,
  LibraryFolderRenameRequest,
  LibraryFolderRequest,
  LibraryFolderResponse,
  LibraryListResponse,
  LibraryRequest,
  LibraryResponse,
  LibraryShareCapRequest,
  LibraryUpdateRequest,
  SourceConnectionTestRequest,
  SourceConnectionTestResponse,
  SourceBrowseRequest,
  SourceBrowseResponse,
  SourceTypeDescriptor,
  SourceTypeKey,
  BulkDocumentDeleteResponse,
} from '../types/api'
import { isErrorResponse, type ErrorResponse } from '../types/api'
import { apiClient as client, normalizeError } from './api'

export async function getLibraries(): Promise<LibraryListResponse[]> {
  try {
    const { data } = await client.get<LibraryListResponse[]>('/v1/libraries')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLibrary(libraryId: string): Promise<LibraryResponse> {
  try {
    const { data } = await client.get<LibraryResponse>(`/v1/libraries/${libraryId}`)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function createLibrary(request: LibraryRequest): Promise<LibraryResponse> {
  try {
    const { data } = await client.post<LibraryResponse>('/v1/libraries', request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Ends a library's own source connection; it keeps its content and rests until connected anew. */
export async function disconnectLibrarySource(libraryId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/source-connection`)
  } catch (err) {
    normalizeError(err)
  }
}

export async function testLibrarySource(
  request: SourceConnectionTestRequest,
): Promise<SourceConnectionTestResponse> {
  try {
    const { data } = await client.post<SourceConnectionTestResponse>(
      '/v1/libraries/source-test',
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** every source type the backend has a connector for (ADR-0038), ordered by key. */
export async function listSourceTypes(): Promise<SourceTypeDescriptor[]> {
  try {
    const { data } = await client.get<SourceTypeDescriptor[]>('/v1/source-types')
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * what a source offers before its configuration is saved - the spaces of a Confluence token, the
 * buckets of an S3 key; `query` carries the connector's own listing parameters.
 */
export async function browseSource(
  sourceType: SourceTypeKey,
  request: SourceBrowseRequest,
): Promise<SourceBrowseResponse> {
  try {
    const { data } = await client.post<SourceBrowseResponse>(
      `/v1/source-types/${encodeURIComponent(sourceType)}/browse`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function updateLibrary(
  libraryId: string,
  request: LibraryUpdateRequest,
): Promise<LibraryResponse> {
  try {
    const { data } = await client.put<LibraryResponse>(`/v1/libraries/${libraryId}`, request)
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Setzt die Freigabe-Obergrenze einer Konnektorbibliothek (#797) - Systemverwaltung, gesamt
 * ersetzt (kein Teil-Update). Senkt sie eine bestehende, weitere Freigabe, klemmt das Backend sie
 * im selben Aufruf sofort auf die neue Obergrenze zurück.
 */
export async function updateLibraryShareCap(
  libraryId: string,
  request: LibraryShareCapRequest,
): Promise<LibraryResponse> {
  try {
    const { data } = await client.put<LibraryResponse>(
      `/v1/libraries/${libraryId}/share-cap`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * Connects the library through a connection profile ("Zugang") of its source type - also the way
 * back after "Zugang entfernt" and out of a lock for its own address. Answers the whole library.
 */
export async function connectLibraryProfile(
  libraryId: string,
  profileId: string,
  sourceUrl?: string,
): Promise<LibraryResponse> {
  try {
    const request: LibraryConnectionProfileRequest = sourceUrl
      ? { profileId, sourceUrl }
      : { profileId }
    const { data } = await client.put<LibraryResponse>(
      `/v1/libraries/${libraryId}/connection-profile`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/** Releases the library from its profile; it keeps address and secret as its own. */
export async function disconnectLibraryProfile(libraryId: string): Promise<LibraryResponse> {
  try {
    const { data } = await client.delete<LibraryResponse>(
      `/v1/libraries/${libraryId}/connection-profile`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * `ERASURE_PENDING` (202): a private library is marked for erasure and completes once its running
 * run has ended; every other success (204) is `DELETED`.
 */
export type LibraryDeletionOutcome = 'DELETED' | 'ERASURE_PENDING'

export async function deleteLibrary(libraryId: string): Promise<LibraryDeletionOutcome> {
  try {
    const response = await client.delete(`/v1/libraries/${libraryId}`)
    return response.status === 202 ? 'ERASURE_PENDING' : 'DELETED'
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLibraryDocuments(
  libraryId: string,
  options?: {
    page?: number
    size?: number
    q?: string
    folderId?: string | null
    missingMetadataField?: string | null
  },
): Promise<LibraryDocumentPageResponse> {
  try {
    const { data } = await client.get<LibraryDocumentPageResponse>(
      `/v1/libraries/${libraryId}/documents`,
      {
        params: {
          page: options?.page,
          size: options?.size,
          // undefined/"" are both dropped by axios's default paramsSerializer, so an empty search
          // field never sends q= at all - the backend's own "blank q means unfiltered" branch
          // (KnowledgeLibraryService#listDocuments) would treat it identically either way, but
          // omitting it keeps the request itself a plain, unfiltered "list this page" call.
          q: options?.q || undefined,
          // undefined/null both mean "the library's root" to the backend (GET .../documents,
          // folderId param) - dropped here the same way q is above, rather than sent as the string
          // "null".
          folderId: options?.folderId || undefined,
          // the Pflege-Anker's list - dropped when absent, like q above.
          missingMetadataField: options?.missingMetadataField || undefined,
        },
      },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function uploadDocument(
  libraryId: string,
  file: File,
  folderId?: string | null,
  // a path relative to folderId (e.g. "Protokolle/2026") whose intermediate folders the
  // backend creates idempotently - lets a whole dragged-and-dropped/webkitdirectory-selected
  // folder tree upload one file at a time while landing under a single, shared folder chain.
  folderPath?: string | null,
): Promise<LibraryDocumentResponse> {
  try {
    const formData = new FormData()
    formData.append('file', file)
    // an empty/root folderId is simply omitted, mirroring getLibraryDocuments above - the
    // backend's own folderId form field is optional and nullable, meaning "the library's root"
    // either way.
    if (folderId) {
      formData.append('folderId', folderId)
    }
    // same "omit rather than send empty" treatment as folderId above.
    if (folderPath) {
      formData.append('folderPath', folderPath)
    }
    const { data } = await client.post<LibraryDocumentResponse>(
      `/v1/libraries/${libraryId}/documents`,
      formData,
      { headers: { 'Content-Type': 'multipart/form-data' } },
    )
    return data
  } catch (err) {
    normalizeError(err, 'upload')
  }
}

//  (Epic  Phase 3): folder CRUD for the UPLOAD-library navigation UI - the endpoints
// themselves shipped with  (ADR-0020).
export async function createLibraryFolder(
  libraryId: string,
  request: LibraryFolderRequest,
): Promise<LibraryFolderResponse> {
  try {
    const { data } = await client.post<LibraryFolderResponse>(
      `/v1/libraries/${libraryId}/folders`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function getLibraryFolder(
  libraryId: string,
  folderId: string,
): Promise<LibraryFolderResponse> {
  try {
    const { data } = await client.get<LibraryFolderResponse>(
      `/v1/libraries/${libraryId}/folders/${folderId}`,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function renameLibraryFolder(
  libraryId: string,
  folderId: string,
  request: LibraryFolderRenameRequest,
): Promise<LibraryFolderResponse> {
  try {
    const { data } = await client.patch<LibraryFolderResponse>(
      `/v1/libraries/${libraryId}/folders/${folderId}`,
      request,
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}

export async function deleteLibraryFolder(libraryId: string, folderId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/folders/${folderId}`)
  } catch (err) {
    normalizeError(err)
  }
}

// extracts the RFC 6266/5987 filename from a Content-Disposition header value, e.g.
// `inline; filename="a.pdf"; filename*=UTF-8''a.pdf`. Prefers the filename* (percent-encoded,
// UTF-8) parameter when present, since that is the one DocumentController escapes correctly for
// non-ASCII names - falling back to the plain filename parameter otherwise.
//
// Exported so api.test.ts can exercise the parsing directly against header strings (including the
// RFC 5987/Umlaut case) instead of through getDocumentContent() end-to-end: a Blob response body
// hangs against msw/node in this project's jsdom test environment, the same limitation documented
// on normalizeError in api.ts for a Blob *request* body ( review).
export function parseContentDispositionFileName(headerValue: string | undefined): string | null {
  if (!headerValue) return null
  const extended = /filename\*=UTF-8''([^;]+)/i.exec(headerValue)
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim())
    } catch {
      // Falls through to the plain filename parameter below.
    }
  }
  // (?!\*) keeps this from matching the filename* parameter's own "filename" prefix when there is
  // no ASCII filename to fall back to (e.g. decodeURIComponent above threw) - without it, a header
  // with only `filename*=UTF-8''...` would match here with `*=UTF-8''...` as the "file name" (
  // review).
  const plain = /filename(?!\*)="?([^";]+)"?/i.exec(headerValue)
  return plain ? plain[1].trim() : null
}

export interface DocumentContent {
  blob: Blob
  fileName: string | null
}

//  (review): a Blob response body (success or error) hangs against msw/node in this project's
// jsdom test environment (same undici/XHR-interceptor limitation documented on normalizeError in
// api.ts for a Blob *request* body) - so this cannot be exercised end-to-end through getDocumentContent()
// in tests. Exported and kept independent of any HTTP call so api.test.ts can construct an
// AxiosError with a plain Blob (which itself works fine in jsdom, no MSW involved) and exercise the
// mapping directly.
//
// 404 covers both "no local file for this source type" (HTTP_DIRECTORY/RSS_FEED) and "file missing
// on disk" alike, by design (see the endpoint's own OpenAPI description) - both surface as the same
// German message. Any other failure arrives with responseType 'blob' applied to its body too, so a
// non-404 ErrorResponse is a Blob rather than parsed JSON - isErrorResponse never matches a Blob,
// which would otherwise fall through to normalizeError's generic, English "HTTP <status>: ..."
// fallback. Read the blob as text and parse it the same way the JSON-response endpoints get it for
// free from axios.
export async function mapDocumentContentError(err: unknown): Promise<never> {
  if (err instanceof AxiosError && err.response?.status === 404) {
    if ((await blobErrorCode(err.response.data)) === LIBRARY_BEING_ERASED) {
      throw new Error(
        'Die Bibliothek wird gelöscht – ihre Dokumente lassen sich nicht mehr öffnen.',
        {
          cause: err,
        },
      )
    }
    throw new Error(
      'Das Originaldokument wurde nicht gefunden. Es wurde möglicherweise verschoben oder gelöscht.',
      { cause: err },
    )
  }
  if (err instanceof AxiosError && err.response?.data instanceof Blob) {
    const message = await blobErrorMessage(err.response.data)
    if (message !== null) {
      throw new Error(message, { cause: err })
    }
    throw new Error('Das Originaldokument konnte nicht geladen werden.', { cause: err })
  }
  normalizeError(err)
}

/** The `code` of the 404 for a document whose private library is marked for erasure. */
const LIBRARY_BEING_ERASED = 'LIBRARY_BEING_ERASED'

/** The ErrorResponse inside a blob body, `null` for anything else. */
async function blobErrorBody(body: unknown): Promise<ErrorResponse | null> {
  if (!(body instanceof Blob)) return null
  try {
    const parsed: unknown = JSON.parse(await body.text())
    return isErrorResponse(parsed) ? parsed : null
  } catch {
    return null
  }
}

async function blobErrorMessage(body: unknown): Promise<string | null> {
  return (await blobErrorBody(body))?.error ?? null
}

/** The `code` of an ErrorResponse inside a blob body, like `apiErrorCode` for a parsed one. */
async function blobErrorCode(body: unknown): Promise<string | null> {
  return (await blobErrorBody(body))?.code ?? null
}

// streams the original file behind an indexed document. Bearer-authenticated like every
// other endpoint here, so a plain <a href> deep link cannot reach it - see
// utils/documentContent.ts for the client-side blob-URL piece this feeds.
export async function getDocumentContent(documentId: string): Promise<DocumentContent> {
  try {
    const response = await client.get<Blob>(`/v1/documents/${documentId}/content`, {
      responseType: 'blob',
    })
    return {
      blob: response.data,
      fileName: parseContentDispositionFileName(response.headers['content-disposition']),
    }
  } catch (err) {
    return await mapDocumentContentError(err)
  }
}

export async function deleteLibraryDocument(libraryId: string, documentId: string): Promise<void> {
  try {
    await client.delete(`/v1/libraries/${libraryId}/documents/${documentId}`)
  } catch (err) {
    normalizeError(err)
  }
}

/**
 * #1943: the chosen documents of an upload library in one call - the answer names every id that
 * stayed and why, so a partial success can be reported instead of a bare failure.
 */
export async function bulkDeleteLibraryDocuments(
  libraryId: string,
  documentIds: string[],
): Promise<BulkDocumentDeleteResponse> {
  try {
    const { data } = await client.post<BulkDocumentDeleteResponse>(
      `/v1/libraries/${libraryId}/documents/bulk-delete`,
      { documentIds },
    )
    return data
  } catch (err) {
    normalizeError(err)
  }
}
