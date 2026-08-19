import { create } from 'zustand'
import type { IndexingStatus } from '../types/api'
import { triggerIndexing, getIndexingStatus } from '../services/api'

const POLL_INTERVAL_MS = 2000

// #500 review, finding 5: the exact German text DocumentIndexingService#toIndexingSourceType
// sends for an UPLOAD library (no run type at all, 409) - matched here so triggerIndexing can show
// this specific message instead of the generic failure one, and leave status untouched rather than
// FAILED, since no run was ever started.
export const UPLOAD_LIBRARY_INDEXING_ERROR =
  'Fuer UPLOAD-Bibliotheken gibt es keinen Indizierungslauf'

type SnackbarSeverity = 'success' | 'error'

interface Snackbar {
  open: boolean
  message: string
  severity: SnackbarSeverity
}

// #481: this store used to back the admin drawer's cross-library target picker; the drawer is
// gone and every caller now already knows which library it cares about (the library detail page).
// The store keeps tracking a single "active" library's run - fine, since only one library detail
// page is ever mounted at a time - but every entry point takes libraryId explicitly instead of
// reading a selection out of drawer-only state.
interface IndexingState {
  status: IndexingStatus
  documentCount: number
  totalDocuments: number
  documentsSkipped: number
  message: string | null
  timestamp: string | null
  isPolling: boolean
  snackbar: Snackbar

  triggerIndexing: (libraryId: string) => Promise<void>
  loadStatus: (libraryId: string) => Promise<void>
  stopPolling: () => void
  closeSnackbar: () => void
}

let pollIntervalId: ReturnType<typeof setInterval> | null = null
let pollingLibraryId: string | null = null

export const useIndexingStore = create<IndexingState>((set, get) => ({
  status: 'IDLE',
  documentCount: 0,
  totalDocuments: 0,
  documentsSkipped: 0,
  message: null,
  timestamp: null,
  isPolling: false,
  snackbar: { open: false, message: '', severity: 'success' },

  triggerIndexing: async (libraryId: string) => {
    try {
      // #478: sourceType and every typed configuration field come from the library itself
      // (ADR-0018) - the trigger only ever names the library.
      const response = await triggerIndexing(libraryId)
      set({
        status: response.status,
        documentCount: response.documentCount,
        totalDocuments: response.totalDocuments,
        documentsSkipped: response.documentsSkipped,
        message: response.message,
        timestamp: response.timestamp,
      })
      get().stopPolling()
      pollingLibraryId = libraryId
      startPolling(libraryId, set, get)
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Indizierung konnte nicht gestartet werden'
      // An UPLOAD library never had a run to fail - overwriting status to FAILED would misleadingly
      // suggest one started and broke, so only the snackbar reports it, and status is left as-is.
      const isUploadLibrary = message === UPLOAD_LIBRARY_INDEXING_ERROR
      set({
        ...(isUploadLibrary ? {} : { status: 'FAILED' as const, message }),
        snackbar: { open: true, message, severity: 'error' },
      })
    }
  },

  loadStatus: async (libraryId: string) => {
    try {
      const response = await getIndexingStatus(libraryId)
      set({
        status: response.status,
        documentCount: response.documentCount,
        totalDocuments: response.totalDocuments,
        documentsSkipped: response.documentsSkipped,
        message: response.message,
        timestamp: response.timestamp,
      })
      if (response.status === 'RUNNING') {
        pollingLibraryId = libraryId
        startPolling(libraryId, set, get)
      }
    } catch {
      // Leaves the page on its IDLE default for this library rather than surfacing an error for a
      // status check the caller did not explicitly request.
    }
  },

  stopPolling: () => {
    if (pollIntervalId) {
      clearInterval(pollIntervalId)
      pollIntervalId = null
    }
    pollingLibraryId = null
    set({ isPolling: false })
  },

  closeSnackbar: () => set((s) => ({ snackbar: { ...s.snackbar, open: false } })),
}))

function startPolling(
  libraryId: string,
  set: (partial: Partial<IndexingState>) => void,
  get: () => IndexingState,
) {
  if (pollIntervalId) return
  set({ isPolling: true })

  pollIntervalId = setInterval(async () => {
    try {
      const response = await getIndexingStatus(libraryId)
      // The caller may have navigated to a different library while this request was in flight - a
      // stale response must not overwrite the state of the library now being polled (or none).
      if (pollingLibraryId !== libraryId) return
      set({
        status: response.status,
        documentCount: response.documentCount,
        totalDocuments: response.totalDocuments,
        documentsSkipped: response.documentsSkipped,
        message: response.message,
        timestamp: response.timestamp,
      })

      if (response.status === 'COMPLETED' || response.status === 'FAILED') {
        get().stopPolling()
        set({
          snackbar: {
            open: true,
            message:
              response.status === 'COMPLETED'
                ? `Indizierung abgeschlossen: ${response.documentCount} verarbeitet, ${response.documentsSkipped} übersprungen`
                : (response.message ?? 'Indizierung fehlgeschlagen'),
            severity: response.status === 'COMPLETED' ? 'success' : 'error',
          },
        })
      }
    } catch {
      get().stopPolling()
      set({
        status: 'FAILED',
        message: 'Indizierungsstatus konnte nicht abgerufen werden',
        snackbar: {
          open: true,
          message: 'Indizierungsstatus konnte nicht abgerufen werden',
          severity: 'error',
        },
      })
    }
  }, POLL_INTERVAL_MS)
}
