import { AxiosError } from 'axios'
import { create } from 'zustand'
import type {
  BulkDocumentDeleteResponse,
  LibraryDocumentResponse,
  LibraryFolderBreadcrumbItem,
  LibraryFolderListItem,
} from '@/shared/api/types'
import {
  bulkDeleteLibraryDocuments,
  createLibraryFolder,
  deleteLibraryDocument,
  deleteLibraryFolder,
  getLibraryDocuments,
  renameLibraryFolder,
  uploadDocument as uploadDocumentRequest,
} from '../api/libraryApi'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import { registerSessionStore } from '@/shared/session/resettableStores'

// normalizeError (shared/api/api.ts) attaches the original AxiosError as
// `cause` - this reaches into that to tell a real 404 (unknown/foreign folderId, ADR-0020) apart
// from any other failure (500, network outage), which must surface as a normal error instead of
// silently bouncing the caller back to the library's root.
function isNotFoundError(err: unknown): boolean {
  return (
    err instanceof Error && err.cause instanceof AxiosError && err.cause.response?.status === 404
  )
}

const POLL_INTERVAL_MS = 3000
export const DEFAULT_PAGE_SIZE = 20

// The paging/search state a library's document list was last loaded with - kept alongside
// the loaded page itself so a poll tick (see startPolling) and a post-upload/-delete refresh (see
// LibraryDetailPage's onDocumentsChanged) both re-fetch the same page/query the user is currently
// looking at, instead of silently resetting them to page 0 with no search term.
export interface DocumentPageState {
  page: number
  size: number
  q: string
  totalElements: number
  // The folder currently being browsed (folder navigation, ADR-0020/#821) - null means the
  // library's root, mirroring the backend's own folderId semantics (GET/POST .../documents).
  folderId: string | null
  // The Pflege-Anker's filter - a core field key whose empty documents are listed, or null
  // for the unfiltered list. Like q, it makes the list bibliotheksweit.
  missingMetadataField: string | null
}

const defaultPageState: DocumentPageState = {
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  q: '',
  totalElements: 0,
  folderId: null,
  missingMetadataField: null,
}

interface DocumentState {
  documentsByLibrary: Record<string, LibraryDocumentResponse[]>
  pageStateByLibrary: Record<string, DocumentPageState>
  // The requested folder's direct subfolders / ancestor chain, as returned alongside the
  // folder-scoped documents page (LibraryDocumentPageResponse.folders/breadcrumb). Both empty at
  // the root and whenever a search (q) is active - search is always bibliotheksweit (ADR-0020,
  // Entscheidung 4).
  foldersByLibrary: Record<string, LibraryFolderListItem[]>
  breadcrumbByLibrary: Record<string, LibraryFolderBreadcrumbItem[]>
  isLoading: boolean
  error: string | null
  // Set instead of `error` when a requested folderId turned out to be unknown/foreign (404) -
  // loadDocuments falls back to the library's root on its own and surfaces this hint rather than a
  // dead "loading failed" state, so a stale bookmark or a deleted folder's deep link recovers
  // silently into a valid view.
  folderNotFoundMessage: string | null
  folderError: string | null
  // A list rather than a single message: uploadNewDocument is called once per file from a
  // multi-file drop/selection, and a failure on one file must not erase - or be erased by - the
  // outcome of another file in the same batch. Cleared once, up front, by the caller starting a
  // new batch (see LibraryDetailPage's handleFiles), not by uploadNewDocument itself.
  uploadErrors: string[]
  deleteError: string | null
  isUploading: boolean

  reset: () => void
  loadDocuments: (
    libraryId: string,
    options?: {
      page?: number
      size?: number
      q?: string
      folderId?: string | null
      missingMetadataField?: string | null
    },
  ) => Promise<void>
  uploadNewDocument: (libraryId: string, file: File, folderPath?: string) => Promise<void>
  removeDocument: (libraryId: string, documentId: string) => Promise<void>
  /**
   * The chosen documents in one call - resolves to the per-id outcome, or undefined when
   * the session turned over while the call was in flight.
   */
  removeDocuments: (
    libraryId: string,
    documentIds: string[],
  ) => Promise<BulkDocumentDeleteResponse | undefined>
  createFolder: (libraryId: string, name: string, parentFolderId?: string | null) => Promise<void>
  renameFolder: (libraryId: string, folderId: string, name: string) => Promise<void>
  removeFolder: (libraryId: string, folderId: string) => Promise<void>
  // Appends a message to uploadErrors without going through
  // uploadNewDocument itself - used for a batch-level rejection (e.g. files skipped client-side
  // for an unsupported format before ever reaching the backend) that names no single file.
  reportUploadError: (message: string) => void
  clearUploadErrors: () => void
  clearDeleteError: () => void
  clearFolderError: () => void
  clearFolderNotFoundMessage: () => void
  stopPolling: (libraryId: string) => void
}

const pollIntervalIds: Record<string, ReturnType<typeof setInterval>> = {}

type LoadOptions = {
  page?: number
  size?: number
  q?: string
  folderId?: string | null
  missingMetadataField?: string | null
}

type RequestedPageState = Omit<DocumentPageState, 'totalElements'>

// The newest list request per library that has not settled yet. Only the request holding the
// current sequence number may write its response (or error) back; anything older is discarded.
// Its params are also the base for the next request's omitted options, so a page change during a
// running search keeps the requested search term rather than the last loaded one.
let loadSequence = 0
const latestRequestByLibrary: Record<string, { sequence: number; params: RequestedPageState }> = {}

function isLatestRequest(libraryId: string, sequence: number): boolean {
  return latestRequestByLibrary[libraryId]?.sequence === sequence
}

function settleRequest(libraryId: string, sequence: number) {
  if (isLatestRequest(libraryId, sequence)) delete latestRequestByLibrary[libraryId]
}

function hasPendingDocument(documents: LibraryDocumentResponse[] | undefined): boolean {
  return (documents ?? []).some((doc) => doc.status === 'PENDING')
}

export const useDocumentStore = create<DocumentState>((set, get) => ({
  documentsByLibrary: {},
  pageStateByLibrary: {},
  foldersByLibrary: {},
  breadcrumbByLibrary: {},
  isLoading: false,
  error: null,
  folderNotFoundMessage: null,
  folderError: null,
  uploadErrors: [],
  deleteError: null,
  isUploading: false,

  reset: () => {
    Object.keys(pollIntervalIds).forEach((libraryId) => get().stopPolling(libraryId))
    Object.keys(latestRequestByLibrary).forEach((libraryId) => {
      delete latestRequestByLibrary[libraryId]
    })
    set({
      documentsByLibrary: {},
      pageStateByLibrary: {},
      foldersByLibrary: {},
      breadcrumbByLibrary: {},
      isLoading: false,
      error: null,
      folderNotFoundMessage: null,
      folderError: null,
      uploadErrors: [],
      deleteError: null,
      isUploading: false,
    })
  },

  loadDocuments: async (libraryId, options) => {
    await runLoadDocuments(libraryId, options, set, get, { isFolderFallbackRetry: false })
  },

  uploadNewDocument: async (libraryId: string, file: File, folderPath?: string) => {
    const sessionEpoch = currentSessionEpoch()
    const folderId = get().pageStateByLibrary[libraryId]?.folderId ?? null
    set({ isUploading: true })
    try {
      // Uploads land in the currently open folder, mirroring GET .../documents' own scoping.
      // folderPath (if given) is relative to that same folder - its intermediate folders are
      // created idempotently by the backend, letting a whole dragged-and-dropped or
      // webkitdirectory-selected folder tree land under one shared folder chain instead of a
      // duplicate per file.
      await uploadDocumentRequest(libraryId, file, folderId, folderPath)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ isUploading: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) throw err
      const rawMessage =
        err instanceof Error ? err.message : 'Datei konnte nicht hochgeladen werden'
      // Names the concerned file alongside the backend's German reason (format, size, dedup) - the
      // backend message alone does not repeat which of possibly several dropped files it refers
      // to. Includes folderPath (if any) rather than the bare file name -
      // a folder upload can easily carry two same-named files from different subfolders (e.g.
      // "2025/protokoll.pdf" and "2026/protokoll.pdf"), which would otherwise produce two
      // identical uploadErrors messages - duplicate React keys where they are rendered
      // (LibraryDetailPage's uploadErrors Alert, keyed by message) as well as being genuinely
      // ambiguous to a person reading the list.
      const fileLabel = folderPath ? `${folderPath}/${file.name}` : file.name
      const message = `${rawMessage} (Datei: ${fileLabel})`
      set({ uploadErrors: [...get().uploadErrors, message], isUploading: false })
      throw err
    }
    // A local prepend cannot know whether the uploaded file belongs on the page the user is looking
    // at (an active search term it does not match, or a page that was already full), nor move
    // totalElements. Reloading the current page from the server is the only way to know
    // whether/where the new document actually landed.
    await reloadCurrentPage(libraryId, get)
  },

  createFolder: async (libraryId: string, name: string, parentFolderId?: string | null) => {
    const sessionEpoch = currentSessionEpoch()
    // Creates the new folder directly under the folder currently being browsed, unless the
    // caller passes its own parentFolderId.
    const parent =
      parentFolderId !== undefined
        ? parentFolderId
        : (get().pageStateByLibrary[libraryId]?.folderId ?? null)
    try {
      await createLibraryFolder(libraryId, { name, parentFolderId: parent })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) throw err
      const message = err instanceof Error ? err.message : 'Ordner konnte nicht angelegt werden'
      set({ folderError: message })
      throw err
    }
    if (isStaleSessionEpoch(sessionEpoch)) return
    set({ folderError: null })
    await reloadCurrentPage(libraryId, get)
  },

  renameFolder: async (libraryId: string, folderId: string, name: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      await renameLibraryFolder(libraryId, folderId, { name })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) throw err
      const message = err instanceof Error ? err.message : 'Ordner konnte nicht umbenannt werden'
      set({ folderError: message })
      throw err
    }
    if (isStaleSessionEpoch(sessionEpoch)) return
    set({ folderError: null })
    await reloadCurrentPage(libraryId, get)
  },

  removeFolder: async (libraryId: string, folderId: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      await deleteLibraryFolder(libraryId, folderId)
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) throw err
      const message = err instanceof Error ? err.message : 'Ordner konnte nicht gelöscht werden'
      set({ folderError: message })
      throw err
    }
    if (isStaleSessionEpoch(sessionEpoch)) return
    set({ folderError: null })
    await reloadCurrentPage(libraryId, get)
  },

  reportUploadError: (message: string) => {
    set({ uploadErrors: [...get().uploadErrors, message] })
  },

  removeDocument: async (libraryId: string, documentId: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      await deleteLibraryDocument(libraryId, documentId)
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) throw err
      const message = err instanceof Error ? err.message : 'Dokument konnte nicht gelöscht werden'
      set({ deleteError: message })
      throw err
    }
    if (isStaleSessionEpoch(sessionEpoch)) return
    set({ deleteError: null })
    // Filtering the row out of the locally cached page
    // alone leaves totalElements and the neighbouring pages stale - a full page loses its would-be
    // last entry, and deleting a page's only document empties it without ever dropping back to a
    // page that still has content. Reloading from the server, then stepping back a page if this
    // one is now empty, keeps both in sync with what the backend actually holds.
    await reloadCurrentPage(libraryId, get)
    const pageState = get().pageStateByLibrary[libraryId]
    const stillEmpty = (get().documentsByLibrary[libraryId] ?? []).length === 0
    if (stillEmpty && pageState && pageState.page > 0) {
      await get().loadDocuments(libraryId, { page: pageState.page - 1 })
    }
  },

  removeDocuments: async (libraryId: string, documentIds: string[]) => {
    const sessionEpoch = currentSessionEpoch()
    let result
    try {
      result = await bulkDeleteLibraryDocuments(libraryId, documentIds)
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) throw err
      const message = err instanceof Error ? err.message : 'Dokumente konnten nicht gelöscht werden'
      set({ deleteError: message })
      throw err
    }
    if (isStaleSessionEpoch(sessionEpoch)) return result
    set({ deleteError: null })
    // Same reasoning as removeDocument above: totalElements and the neighbouring pages are stale
    // after a delete, and a page emptied by it must fall back to one that still has content.
    await reloadCurrentPage(libraryId, get)
    const pageState = get().pageStateByLibrary[libraryId]
    const stillEmpty = (get().documentsByLibrary[libraryId] ?? []).length === 0
    if (stillEmpty && pageState && pageState.page > 0) {
      await get().loadDocuments(libraryId, { page: pageState.page - 1 })
    }
    return result
  },

  clearUploadErrors: () => set({ uploadErrors: [] }),
  clearDeleteError: () => set({ deleteError: null }),
  clearFolderError: () => set({ folderError: null }),
  clearFolderNotFoundMessage: () => set({ folderNotFoundMessage: null }),

  stopPolling: (libraryId: string) => {
    const intervalId = pollIntervalIds[libraryId]
    if (intervalId) {
      clearInterval(intervalId)
      delete pollIntervalIds[libraryId]
    }
  },
}))

registerSessionStore(useDocumentStore)

// The actual implementation behind the public loadDocuments action - pulled out to a module-level
// function (mirroring reloadCurrentPage/startPolling below) so the folder-not-found fallback can
// recurse into it directly with isFolderFallbackRetry=true, rather than
// through get().loadDocuments(), which would immediately clear the very
// folderNotFoundMessage this call is in the middle of setting.
//
// A background load (the poll tick) leaves isLoading and error untouched and stops polling on a
// failure instead of surfacing it.
async function runLoadDocuments(
  libraryId: string,
  options: LoadOptions | undefined,
  set: (partial: Partial<DocumentState>) => void,
  get: () => DocumentState,
  mode: { isFolderFallbackRetry: boolean; background?: boolean },
) {
  // Captured before the await below - checked again once it resolves, so a response
  // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
  // previous user's documents into the now-emptied store, and does not start a poll interval
  // whose ticks would keep writing into it afterwards.
  const sessionEpoch = currentSessionEpoch()
  const previous: RequestedPageState =
    latestRequestByLibrary[libraryId]?.params ??
    get().pageStateByLibrary[libraryId] ??
    defaultPageState
  const page = options?.page ?? previous.page
  const size = options?.size ?? previous.size
  const q = options?.q ?? previous.q
  // An explicit `folderId` key (even null, meaning "navigate to the root") always wins over
  // the previously loaded folder - only its *absence* from options falls back to `previous`. A
  // plain `options?.folderId ?? previous.folderId` could not tell "navigate to root" (null) apart
  // from "keep browsing the same folder" (undefined).
  const folderId =
    options && Object.prototype.hasOwnProperty.call(options, 'folderId')
      ? (options.folderId ?? null)
      : previous.folderId
  // Same "explicit key wins, even as null" rule as folderId above: null clears the filter.
  const missingMetadataField =
    options && Object.prototype.hasOwnProperty.call(options, 'missingMetadataField')
      ? (options.missingMetadataField ?? null)
      : previous.missingMetadataField

  const sequence = ++loadSequence
  latestRequestByLibrary[libraryId] = {
    sequence,
    params: { page, size, q, folderId, missingMetadataField },
  }
  if (!mode.background) set({ isLoading: true, error: null })
  try {
    const response = await getLibraryDocuments(libraryId, {
      page,
      size,
      q,
      folderId,
      missingMetadataField,
    })
    if (isStaleSessionEpoch(sessionEpoch) || !isLatestRequest(libraryId, sequence)) return
    settleRequest(libraryId, sequence)
    set({
      documentsByLibrary: { ...get().documentsByLibrary, [libraryId]: response.items },
      pageStateByLibrary: {
        ...get().pageStateByLibrary,
        [libraryId]: {
          page: response.page,
          size: response.size,
          q,
          totalElements: response.totalElements,
          folderId,
          missingMetadataField,
        },
      },
      foldersByLibrary: { ...get().foldersByLibrary, [libraryId]: response.folders },
      breadcrumbByLibrary: { ...get().breadcrumbByLibrary, [libraryId]: response.breadcrumb },
      ...(mode.background ? {} : { isLoading: false }),
      // A genuinely new successful load means whichever folder was missing before is no longer
      // relevant - reset the hint so it does not keep showing once the user
      // has navigated elsewhere. Suppressed for the fallback's own retry call itself (see the catch
      // block below): that call's success is what *produces* the hint, so resetting it here too
      // would erase it before it was ever shown.
      ...(mode.isFolderFallbackRetry || mode.background ? {} : { folderNotFoundMessage: null }),
    })
    if (hasPendingDocument(response.items)) {
      startPolling(libraryId, set, get)
    } else {
      get().stopPolling(libraryId)
    }
  } catch (err) {
    if (isStaleSessionEpoch(sessionEpoch) || !isLatestRequest(libraryId, sequence)) return
    settleRequest(libraryId, sequence)
    if (mode.background) {
      get().stopPolling(libraryId)
      return
    }
    // GET .../documents validates a given folderId regardless of q and rejects an
    // unknown/foreign one with 404 (ADR-0020) - rather than surface that as a dead "loading
    // failed" error, fall back to the library's root exactly once (folderId is null on the retry,
    // so this branch cannot loop) and let the caller show a recoverable hint instead.
    //
    // Scoped to an actual 404 - any other failure (500, network outage)
    // must not bounce the caller out of the folder they were looking at, it must surface as a
    // normal error like every other endpoint here.
    if (folderId !== null && isNotFoundError(err)) {
      set({ folderNotFoundMessage: 'Der Ordner wurde nicht gefunden. Zurück zur Wurzelebene.' })
      await runLoadDocuments(libraryId, { ...options, folderId: null, page: 0 }, set, get, {
        isFolderFallbackRetry: true,
      })
      return
    }
    const message = err instanceof Error ? err.message : 'Dokumente konnten nicht geladen werden'
    set({ error: message, isLoading: false })
  }
}

// Re-fetches the page/search a library's document list is currently showing - shared by
// uploadNewDocument/removeDocument so a mutation is always followed by server truth instead of a
// local array splice/unshift that can drift from totalElements or the active search filter.
// Omitted options resolve to the newest requested view, so a reload never reverts a page or
// search change that is still in flight.
async function reloadCurrentPage(libraryId: string, get: () => DocumentState) {
  await get().loadDocuments(libraryId, {})
}

function startPolling(
  libraryId: string,
  set: (partial: Partial<DocumentState>) => void,
  get: () => DocumentState,
) {
  if (pollIntervalIds[libraryId]) return

  // A tick re-requests exactly the view the newest load asked for, through the same path. It is
  // skipped while another request for this library is still running - that request refreshes the
  // list itself, and a tick started after it would discard its response.
  pollIntervalIds[libraryId] = setInterval(() => {
    if (latestRequestByLibrary[libraryId]) return
    void runLoadDocuments(libraryId, {}, set, get, {
      isFolderFallbackRetry: false,
      background: true,
    })
  }, POLL_INTERVAL_MS)
}
