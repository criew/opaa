import { create } from 'zustand'
import type { LibraryDocumentResponse } from '../types/api'
import {
  deleteLibraryDocument,
  getLibraryDocuments,
  uploadDocument as uploadDocumentRequest,
} from '../services/api'

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
}

const defaultPageState: DocumentPageState = {
  page: 0,
  size: DEFAULT_PAGE_SIZE,
  q: '',
  totalElements: 0,
}

interface DocumentState {
  documentsByLibrary: Record<string, LibraryDocumentResponse[]>
  pageStateByLibrary: Record<string, DocumentPageState>
  isLoading: boolean
  error: string | null
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
    options?: { page?: number; size?: number; q?: string },
  ) => Promise<void>
  uploadNewDocument: (libraryId: string, file: File) => Promise<void>
  removeDocument: (libraryId: string, documentId: string) => Promise<void>
  clearUploadErrors: () => void
  clearDeleteError: () => void
  stopPolling: (libraryId: string) => void
}

const pollIntervalIds: Record<string, ReturnType<typeof setInterval>> = {}

function hasPendingDocument(documents: LibraryDocumentResponse[] | undefined): boolean {
  return (documents ?? []).some((doc) => doc.status === 'PENDING')
}

export const useDocumentStore = create<DocumentState>((set, get) => ({
  documentsByLibrary: {},
  pageStateByLibrary: {},
  isLoading: false,
  error: null,
  uploadErrors: [],
  deleteError: null,
  isUploading: false,

  reset: () => {
    Object.keys(pollIntervalIds).forEach((libraryId) => get().stopPolling(libraryId))
    set({
      documentsByLibrary: {},
      pageStateByLibrary: {},
      isLoading: false,
      error: null,
      uploadErrors: [],
      deleteError: null,
      isUploading: false,
    })
  },

  loadDocuments: async (libraryId, options) => {
    const previous = get().pageStateByLibrary[libraryId] ?? defaultPageState
    const page = options?.page ?? previous.page
    const size = options?.size ?? previous.size
    const q = options?.q ?? previous.q

    set({ isLoading: true, error: null })
    try {
      const response = await getLibraryDocuments(libraryId, { page, size, q })
      set({
        documentsByLibrary: { ...get().documentsByLibrary, [libraryId]: response.items },
        pageStateByLibrary: {
          ...get().pageStateByLibrary,
          [libraryId]: {
            page: response.page,
            size: response.size,
            q,
            totalElements: response.totalElements,
          },
        },
        isLoading: false,
      })
      if (hasPendingDocument(response.items)) {
        startPolling(libraryId, set, get)
      } else {
        get().stopPolling(libraryId)
      }
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Dokumente konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  uploadNewDocument: async (libraryId: string, file: File) => {
    set({ isUploading: true })
    try {
      await uploadDocumentRequest(libraryId, file)
      set({ isUploading: false })
    } catch (err) {
      const rawMessage =
        err instanceof Error ? err.message : 'Datei konnte nicht hochgeladen werden'
      // Names the concerned file alongside the backend's German reason (format, size, dedup) - the
      // backend message alone does not repeat which of possibly several dropped files it refers to.
      const message = `${rawMessage} (Datei: ${file.name})`
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

  removeDocument: async (libraryId: string, documentId: string) => {
    try {
      await deleteLibraryDocument(libraryId, documentId)
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Dokument konnte nicht gelöscht werden'
      set({ deleteError: message })
      throw err
    }
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

  stopPolling: (libraryId: string) => {
    const intervalId = pollIntervalIds[libraryId]
    if (intervalId) {
      clearInterval(intervalId)
      delete pollIntervalIds[libraryId]
    }
  },
}))

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
  })
}

function startPolling(
  libraryId: string,
  set: (partial: Partial<DocumentState>) => void,
  get: () => DocumentState,
) {
  if (pollIntervalIds[libraryId]) return

  pollIntervalIds[libraryId] = setInterval(async () => {
    try {
      const pageState = get().pageStateByLibrary[libraryId] ?? defaultPageState
      const response = await getLibraryDocuments(libraryId, {
        page: pageState.page,
        size: pageState.size,
        q: pageState.q,
      })
      set({
        documentsByLibrary: { ...get().documentsByLibrary, [libraryId]: response.items },
        pageStateByLibrary: {
          ...get().pageStateByLibrary,
          [libraryId]: { ...pageState, totalElements: response.totalElements },
        },
      })
      if (!hasPendingDocument(response.items)) {
        get().stopPolling(libraryId)
      }
    } catch {
      get().stopPolling(libraryId)
    }
  }, POLL_INTERVAL_MS)
}
