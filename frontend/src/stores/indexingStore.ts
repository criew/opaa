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

// #506 review, finding 1: a single global run status used to be shared across every library, so a
// stale response for library A could overwrite the state currently shown for library B after a
// quick switch, and an already-running interval for A silently kept polling while B never updated.
// Keyed state (the same runsByLibrary pattern as documentStore.documentsByLibrary) makes every
// response, and every poll interval, inherently scoped to the library it belongs to - a library
// that never successfully loaded a status simply falls back to the IDLE default below rather than
// showing another library's leftover state.
interface IndexingRunState {
  status: IndexingStatus
  documentCount: number
  totalDocuments: number
  documentsSkipped: number
  message: string | null
  timestamp: string | null
  isPolling: boolean
}

export const IDLE_RUN_STATE: IndexingRunState = {
  status: 'IDLE',
  documentCount: 0,
  totalDocuments: 0,
  documentsSkipped: 0,
  message: null,
  timestamp: null,
  isPolling: false,
}

interface IndexingState {
  runsByLibrary: Record<string, IndexingRunState>
  snackbar: Snackbar

  triggerIndexing: (libraryId: string) => Promise<void>
  loadStatus: (libraryId: string) => Promise<void>
  stopPolling: (libraryId: string) => void
  closeSnackbar: () => void
}

const pollIntervalIds: Record<string, ReturnType<typeof setInterval>> = {}

function setRun(
  libraryId: string,
  patch: Partial<IndexingRunState>,
  set: (partial: Partial<IndexingState>) => void,
  get: () => IndexingState,
) {
  const current = get().runsByLibrary[libraryId] ?? IDLE_RUN_STATE
  set({
    runsByLibrary: { ...get().runsByLibrary, [libraryId]: { ...current, ...patch } },
  })
}

export const useIndexingStore = create<IndexingState>((set, get) => ({
  runsByLibrary: {},
  snackbar: { open: false, message: '', severity: 'success' },

  triggerIndexing: async (libraryId: string) => {
    try {
      // #478: sourceType and every typed configuration field come from the library itself
      // (ADR-0018) - the trigger only ever names the library.
      const response = await triggerIndexing(libraryId)
      setRun(
        libraryId,
        {
          status: response.status,
          documentCount: response.documentCount,
          totalDocuments: response.totalDocuments,
          documentsSkipped: response.documentsSkipped,
          message: response.message,
          timestamp: response.timestamp,
        },
        set,
        get,
      )
      get().stopPolling(libraryId)
      startPolling(libraryId, set, get)
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Indizierung konnte nicht gestartet werden'
      // An UPLOAD library never had a run to fail - overwriting status to FAILED would misleadingly
      // suggest one started and broke, so only the snackbar reports it, and status is left as-is.
      const isUploadLibrary = message === UPLOAD_LIBRARY_INDEXING_ERROR
      if (!isUploadLibrary) {
        setRun(libraryId, { status: 'FAILED', message }, set, get)
      }
      set({ snackbar: { open: true, message, severity: 'error' } })
    }
  },

  loadStatus: async (libraryId: string) => {
    // Reset to IDLE up front: if this library never had a status loaded before, or the fetch
    // below fails, the section must show this library's own default rather than whatever another
    // library left behind in a shared field.
    setRun(libraryId, IDLE_RUN_STATE, set, get)
    try {
      const response = await getIndexingStatus(libraryId)
      setRun(
        libraryId,
        {
          status: response.status,
          documentCount: response.documentCount,
          totalDocuments: response.totalDocuments,
          documentsSkipped: response.documentsSkipped,
          message: response.message,
          timestamp: response.timestamp,
        },
        set,
        get,
      )
      if (response.status === 'RUNNING') {
        startPolling(libraryId, set, get)
      }
    } catch {
      // Leaves the page on its IDLE default for this library rather than surfacing an error for a
      // status check the caller did not explicitly request.
    }
  },

  stopPolling: (libraryId: string) => {
    const intervalId = pollIntervalIds[libraryId]
    if (intervalId) {
      clearInterval(intervalId)
      delete pollIntervalIds[libraryId]
    }
    setRun(libraryId, { isPolling: false }, set, get)
  },

  closeSnackbar: () => set((s) => ({ snackbar: { ...s.snackbar, open: false } })),
}))

function startPolling(
  libraryId: string,
  set: (partial: Partial<IndexingState>) => void,
  get: () => IndexingState,
) {
  if (pollIntervalIds[libraryId]) return
  setRun(libraryId, { isPolling: true }, set, get)

  pollIntervalIds[libraryId] = setInterval(async () => {
    try {
      const response = await getIndexingStatus(libraryId)
      setRun(
        libraryId,
        {
          status: response.status,
          documentCount: response.documentCount,
          totalDocuments: response.totalDocuments,
          documentsSkipped: response.documentsSkipped,
          message: response.message,
          timestamp: response.timestamp,
        },
        set,
        get,
      )

      if (response.status === 'COMPLETED' || response.status === 'FAILED') {
        get().stopPolling(libraryId)
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
      get().stopPolling(libraryId)
      setRun(
        libraryId,
        { status: 'FAILED', message: 'Indizierungsstatus konnte nicht abgerufen werden' },
        set,
        get,
      )
      set({
        snackbar: {
          open: true,
          message: 'Indizierungsstatus konnte nicht abgerufen werden',
          severity: 'error',
        },
      })
    }
  }, POLL_INTERVAL_MS)
}
