import { AxiosError } from 'axios'
import { create } from 'zustand'
import type {
  LibraryDocumentResponse,
  LibraryFolderBreadcrumbItem,
  LibraryFolderListItem,
} from '../types/api'
import {
  createLibraryFolder,
  deleteLibraryDocument,
  deleteLibraryFolder,
  getLibraryDocuments,
  renameLibraryFolder,
  uploadDocument as uploadDocumentRequest,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

// #822 review, finding 2: normalizeError (services/api.ts) attaches the original AxiosError as
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

// The paging/search state a library's document list was last loaded with (#517) - kept alongside
// the loaded page itself so a poll tick (see startPolling) and a post-upload/-delete refresh (see
// LibraryDetailPage's onDocumentsChanged) both re-fetch the same page/query the user is currently
// looking at, instead of silently resetting them to page 0 with no search term.
interface DocumentPageState {
  page: number
  size: number
  q: string
  totalElements: number
  // #822: the folder currently being browsed (folder navigation, ADR-0020/#821) - null means the
  // library's root, mirroring the backend's own folderId semantics (GET/POST .../documents).
  folderId: string | null
}

const defaultPageState: DocumentPageState = {
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  q: '',
  totalElements: 0,
  folderId: null,
}

interface DocumentState {
  documentsByLibrary: Record<string, LibraryDocumentResponse[]>
  pageStateByLibrary: Record<string, DocumentPageState>
  // #822: the requested folder's direct subfolders / ancestor chain, as returned alongside the
  // folder-scoped documents page (LibraryDocumentPageResponse.folders/breadcrumb). Both empty at
  // the root and whenever a search (q) is active - search is always bibliotheksweit (ADR-0020,
  // Entscheidung 4).
  foldersByLibrary: Record<string, LibraryFolderListItem[]>
  breadcrumbByLibrary: Record<string, LibraryFolderBreadcrumbItem[]>
  isLoading: boolean
  error: string | null
  // #822: set instead of `error` when a requested folderId turned out to be unknown/foreign (404) -
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
    options?: { page?: number; size?: number; q?: string; folderId?: string | null },
  ) => Promise<void>
  uploadNewDocument: (libraryId: string, file: File, folderPath?: string) => Promise<void>
  removeDocument: (libraryId: string, documentId: string) => Promise<void>
  createFolder: (libraryId: string, name: string, parentFolderId?: string | null) => Promise<void>
  renameFolder: (libraryId: string, folderId: string, name: string) => Promise<void>
  removeFolder: (libraryId: string, folderId: string) => Promise<void>
  // #823 review, Befund 2: appends a message to uploadErrors without going through
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
    await runLoadDocuments(libraryId, options, set, get, false)
  },

  uploadNewDocument: async (libraryId: string, file: File, folderPath?: string) => {
    const sessionEpoch = currentSessionEpoch()
    const folderId = get().pageStateByLibrary[libraryId]?.folderId ?? null
    set({ isUploading: true })
    try {
      // #822: uploads land in the currently open folder, mirroring GET .../documents' own scoping.
      // #823: folderPath (if given) is relative to that same folder - its intermediate folders are
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
      // to. #823 review, Befund 5a: includes folderPath (if any) rather than the bare file name -
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
    // #517 code review, finding 2 (Szenario C): the uploaded file was only ever prepended to the
    // locally cached page before, regardless of whether it actually belongs on the page the user
    // is looking at (an active search term it does not match, or a page that was already full) -
    // totalElements never moved either. Reloading the current page from the server is the only way
    // to know whether/where the new document actually landed.
    await reloadCurrentPage(libraryId, get)
  },

  createFolder: async (libraryId: string, name: string, parentFolderId?: string | null) => {
    const sessionEpoch = currentSessionEpoch()
    // #822: creates the new folder directly under the folder currently being browsed, unless the
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
    // #517 code review, finding 2 (Szenario A/B): filtering the row out of the locally cached page
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

// The actual implementation behind the public loadDocuments action - pulled out to a module-level
// function (mirroring reloadCurrentPage/startPolling below) so the folder-not-found fallback can
// recurse into it directly with isFolderFallbackRetry=true (#822 review, finding 5), rather than
// through get().loadDocuments(), which would immediately clear the very
// folderNotFoundMessage this call is in the middle of setting.
async function runLoadDocuments(
  libraryId: string,
  options: { page?: number; size?: number; q?: string; folderId?: string | null } | undefined,
  set: (partial: Partial<DocumentState>) => void,
  get: () => DocumentState,
  isFolderFallbackRetry: boolean,
) {
  // #575: captured before the await below - checked again once it resolves, so a response
  // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
  // previous user's documents into the now-emptied store, and does not start a poll interval
  // whose ticks would keep writing into it afterwards.
  const sessionEpoch = currentSessionEpoch()
  const previous = get().pageStateByLibrary[libraryId] ?? defaultPageState
  const page = options?.page ?? previous.page
  const size = options?.size ?? previous.size
  const q = options?.q ?? previous.q
  // #822: an explicit `folderId` key (even null, meaning "navigate to the root") always wins over
  // the previously loaded folder - only its *absence* from options falls back to `previous`. A
  // plain `options?.folderId ?? previous.folderId` could not tell "navigate to root" (null) apart
  // from "keep browsing the same folder" (undefined).
  const folderId =
    options && Object.prototype.hasOwnProperty.call(options, 'folderId')
      ? (options.folderId ?? null)
      : previous.folderId

  set({ isLoading: true, error: null })
  try {
    const response = await getLibraryDocuments(libraryId, { page, size, q, folderId })
    if (isStaleSessionEpoch(sessionEpoch)) return
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
        },
      },
      foldersByLibrary: { ...get().foldersByLibrary, [libraryId]: response.folders },
      breadcrumbByLibrary: { ...get().breadcrumbByLibrary, [libraryId]: response.breadcrumb },
      isLoading: false,
      // #822 review, finding 5: a genuinely new successful load means whichever folder used to be
      // missing is no longer relevant - reset the hint so it does not keep showing once the user
      // has navigated elsewhere. Suppressed for the fallback's own retry call itself (see the catch
      // block below): that call's success is what *produces* the hint, so resetting it here too
      // would erase it before it was ever shown.
      ...(isFolderFallbackRetry ? {} : { folderNotFoundMessage: null }),
    })
    if (hasPendingDocument(response.items)) {
      startPolling(libraryId, set, get)
    } else {
      get().stopPolling(libraryId)
    }
  } catch (err) {
    if (isStaleSessionEpoch(sessionEpoch)) return
    // #822: GET .../documents validates a given folderId regardless of q and rejects an
    // unknown/foreign one with 404 (ADR-0020) - rather than surface that as a dead "loading
    // failed" error, fall back to the library's root exactly once (folderId is null on the retry,
    // so this branch cannot loop) and let the caller show a recoverable hint instead.
    //
    // #822 review, finding 2: scoped to an actual 404 - any other failure (500, network outage)
    // must not bounce the caller out of the folder they were looking at, it must surface as a
    // normal error like every other endpoint here.
    if (folderId !== null && isNotFoundError(err)) {
      set({ folderNotFoundMessage: 'Der Ordner wurde nicht gefunden. Zurück zur Wurzelebene.' })
      await runLoadDocuments(libraryId, { ...options, folderId: null, page: 0 }, set, get, true)
      return
    }
    const message = err instanceof Error ? err.message : 'Dokumente konnten nicht geladen werden'
    set({ error: message, isLoading: false })
  }
}

// Re-fetches the page/search a library's document list is currently showing (#517 code review,
// finding 2) - shared by uploadNewDocument/removeDocument so a mutation is always followed by
// server truth instead of a local array splice/unshift that can drift from totalElements or the
// active search filter.
async function reloadCurrentPage(libraryId: string, get: () => DocumentState) {
  const pageState = get().pageStateByLibrary[libraryId] ?? defaultPageState
  await get().loadDocuments(libraryId, {
    page: pageState.page,
    size: pageState.size,
    q: pageState.q,
    folderId: pageState.folderId,
  })
}

function startPolling(
  libraryId: string,
  set: (partial: Partial<DocumentState>) => void,
  get: () => DocumentState,
) {
  if (pollIntervalIds[libraryId]) return

  pollIntervalIds[libraryId] = setInterval(async () => {
    // #575: this tick's token in the session epoch, captured before the await below.
    // clearInterval (see stopPolling, called by reset()) only stops *future* ticks - a tick
    // already in flight when reset() runs would otherwise still write into documentsByLibrary
    // once its own await resolves, right after reset() just emptied it.
    const sessionEpoch = currentSessionEpoch()
    try {
      const pageState = get().pageStateByLibrary[libraryId] ?? defaultPageState
      const response = await getLibraryDocuments(libraryId, {
        page: pageState.page,
        size: pageState.size,
        q: pageState.q,
        folderId: pageState.folderId,
      })
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        documentsByLibrary: { ...get().documentsByLibrary, [libraryId]: response.items },
        pageStateByLibrary: {
          ...get().pageStateByLibrary,
          [libraryId]: { ...pageState, totalElements: response.totalElements },
        },
        foldersByLibrary: { ...get().foldersByLibrary, [libraryId]: response.folders },
        breadcrumbByLibrary: { ...get().breadcrumbByLibrary, [libraryId]: response.breadcrumb },
      })
      if (!hasPendingDocument(response.items)) {
        get().stopPolling(libraryId)
      }
    } catch {
      get().stopPolling(libraryId)
    }
  }, POLL_INTERVAL_MS)
}
