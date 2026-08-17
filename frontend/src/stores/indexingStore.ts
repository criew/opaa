import { create } from 'zustand'
import type { IndexingStatus, IndexingTriggerRequest, LibraryListResponse } from '../types/api'
import { triggerIndexing, getIndexingStatus, getLibraries } from '../services/api'

const POLL_INTERVAL_MS = 2000
// #419: only a library the caller may edit is offered as an indexing target.
const MIN_ROLE_TO_INDEX: LibraryListResponse['myRole'][] = ['EDITOR', 'MANAGER', 'OWNER']

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
  urlConfig: Pick<IndexingTriggerRequest, 'url' | 'proxy' | 'credentials' | 'insecureSsl'> | null
  libraries: LibraryListResponse[]
  librariesLoading: boolean
  selectedLibraryId: string | null

  triggerIndexing: () => Promise<void>
  pollStatus: () => void
  stopPolling: () => void
  toggleDrawer: () => void
  setDrawerOpen: (open: boolean) => void
  closeSnackbar: () => void
  setUrlConfig: (
    config: Pick<IndexingTriggerRequest, 'url' | 'proxy' | 'credentials' | 'insecureSsl'> | null,
  ) => void
  fetchLibraries: () => Promise<void>
  setSelectedLibraryId: (libraryId: string | null) => void
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
  urlConfig: null,
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
      const urlConfig = get().urlConfig
      const request: IndexingTriggerRequest = { libraryId, ...(urlConfig?.url ? urlConfig : {}) }
      const response = await triggerIndexing(request)
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
      set({
        status: 'FAILED',
        message: err instanceof Error ? err.message : 'Indizierung konnte nicht gestartet werden',
        snackbar: {
          open: true,
          message: 'Indizierung konnte nicht gestartet werden',
          severity: 'error',
        },
      })
    }
  },

  pollStatus: () => {
    if (get().isPolling) return

    set({ isPolling: true })

    pollIntervalId = setInterval(async () => {
      try {
        const response = await getIndexingStatus()
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
  setUrlConfig: (config) => set({ urlConfig: config }),

  fetchLibraries: async () => {
    set({ librariesLoading: true })
    try {
      const libraries = await getLibraries()
      const editable = libraries.filter((l) => MIN_ROLE_TO_INDEX.includes(l.myRole))
      set({ libraries: editable, librariesLoading: false })
    } catch {
      set({ libraries: [], librariesLoading: false })
    }
  },

  setSelectedLibraryId: (libraryId) => set({ selectedLibraryId: libraryId }),
}))
