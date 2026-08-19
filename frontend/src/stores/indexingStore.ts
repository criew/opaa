import { create } from 'zustand'
import type { IndexingStatus, LibraryListResponse } from '../types/api'
import { triggerIndexing, getIndexingStatus, getLibraries } from '../services/api'

const POLL_INTERVAL_MS = 2000
// #419: only a library the caller may edit is offered as an indexing target.
const MIN_ROLE_TO_INDEX: LibraryListResponse['myRole'][] = ['EDITOR', 'MANAGER', 'OWNER']

// #500 review, finding 5: the exact German text DocumentIndexingService#toIndexingSourceType
// sends for an UPLOAD library (no run type at all, 409) - matched here so triggerIndexing can show
// this specific message instead of the generic failure one, and leave status untouched rather than
// FAILED, since no run was ever started. The GET-libraries list does not yet carry sourceType
// (#500 review), so the target picker cannot filter UPLOAD libraries out in advance - this is the
// smallest fix that does not require widening the list endpoint's response shape.
export const UPLOAD_LIBRARY_INDEXING_ERROR =
  'Fuer UPLOAD-Bibliotheken gibt es keinen Indizierungslauf'

type SnackbarSeverity = 'success' | 'error'

interface Snackbar {
  open: boolean
  message: string
  severity: SnackbarSeverity
}

interface IndexingState {
  status: IndexingStatus
  documentCount: number
  totalDocuments: number
  documentsSkipped: number
  message: string | null
  timestamp: string | null
  isPolling: boolean
  drawerOpen: boolean
  snackbar: Snackbar
  libraries: LibraryListResponse[]
  librariesLoading: boolean
  selectedLibraryId: string | null

  triggerIndexing: () => Promise<void>
  pollStatus: () => void
  stopPolling: () => void
  toggleDrawer: () => void
  setDrawerOpen: (open: boolean) => void
  closeSnackbar: () => void
  fetchLibraries: () => Promise<void>
  setSelectedLibraryId: (libraryId: string | null) => void
  fetchStatus: (libraryId: string) => Promise<void>
}

let pollIntervalId: ReturnType<typeof setInterval> | null = null

export const useIndexingStore = create<IndexingState>((set, get) => ({
  status: 'IDLE',
  documentCount: 0,
  totalDocuments: 0,
  documentsSkipped: 0,
  message: null,
  timestamp: null,
  isPolling: false,
  drawerOpen: false,
  snackbar: { open: false, message: '', severity: 'success' },
  libraries: [],
  librariesLoading: false,
  selectedLibraryId: null,

  triggerIndexing: async () => {
    const libraryId = get().selectedLibraryId
    if (!libraryId) {
      set({
        snackbar: {
          open: true,
          message: 'Bitte eine Zielbibliothek auswählen',
          severity: 'error',
        },
      })
      return
    }

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
      get().pollStatus()
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

  pollStatus: () => {
    if (get().isPolling) return

    const libraryId = get().selectedLibraryId
    if (!libraryId) return

    set({ isPolling: true })

    pollIntervalId = setInterval(async () => {
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
  },

  stopPolling: () => {
    if (pollIntervalId) {
      clearInterval(pollIntervalId)
      pollIntervalId = null
    }
    set({ isPolling: false })
  },

  toggleDrawer: () => set((s) => ({ drawerOpen: !s.drawerOpen })),
  setDrawerOpen: (open) => set({ drawerOpen: open }),
  closeSnackbar: () => set((s) => ({ snackbar: { ...s.snackbar, open: false } })),

  fetchLibraries: async () => {
    set({ librariesLoading: true })
    try {
      const libraries = await getLibraries()
      const editable = libraries.filter((l) => MIN_ROLE_TO_INDEX.includes(l.myRole))
      const previousSelection = get().selectedLibraryId
      // A selection that no longer appears in the freshly loaded list (revoked grant, or the
      // list came back empty) must not survive - otherwise the trigger stays enabled against a
      // library the user can no longer see, running straight into a 403/404 (PR #431 review,
      // nit 5).
      const stillValid = editable.some((l) => l.id === previousSelection)
      set({ libraries: editable, librariesLoading: false })
      if (!stillValid) {
        get().setSelectedLibraryId(null)
      }
    } catch {
      set({ libraries: [], librariesLoading: false })
      get().setSelectedLibraryId(null)
    }
  },

  setSelectedLibraryId: (libraryId) => {
    // Status/progress belong to exactly one library at a time. Without resetting here, switching
    // the selection while a run is in flight would freeze the drawer on the previous library's
    // status (isRunning, progress, ...) instead of reflecting the newly selected one.
    get().stopPolling()
    set({
      selectedLibraryId: libraryId,
      status: 'IDLE',
      documentCount: 0,
      totalDocuments: 0,
      documentsSkipped: 0,
      message: null,
      timestamp: null,
    })
    if (libraryId) {
      get().fetchStatus(libraryId)
    }
  },

  fetchStatus: async (libraryId) => {
    try {
      const response = await getIndexingStatus(libraryId)
      // The selection may have changed again while this request was in flight - a stale response
      // must not overwrite the state of the library the user has since switched to.
      if (get().selectedLibraryId !== libraryId) return
      set({
        status: response.status,
        documentCount: response.documentCount,
        totalDocuments: response.totalDocuments,
        documentsSkipped: response.documentsSkipped,
        message: response.message,
        timestamp: response.timestamp,
      })
      if (response.status === 'RUNNING') {
        get().pollStatus()
      }
    } catch {
      // Leave the drawer on its IDLE default for this library rather than surfacing an error for
      // a status check the user did not explicitly request.
    }
  },
}))
