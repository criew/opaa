import { create } from 'zustand'
import type { IndexingStatus } from '../types/api'
import { triggerIndexing, getIndexingStatus } from '../services/api'

const POLL_INTERVAL_MS = 2000

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
  message: string | null
  timestamp: string | null
  isPolling: boolean
  drawerOpen: boolean
  snackbar: Snackbar

  triggerIndexing: () => Promise<void>
  pollStatus: () => void
  stopPolling: () => void
  toggleDrawer: () => void
  setDrawerOpen: (open: boolean) => void
  closeSnackbar: () => void
}

let pollIntervalId: ReturnType<typeof setInterval> | null = null

export const useIndexingStore = create<IndexingState>((set, get) => ({
  status: 'IDLE',
  documentCount: 0,
  totalDocuments: 0,
  message: null,
  timestamp: null,
  isPolling: false,
  drawerOpen: false,
  snackbar: { open: false, message: '', severity: 'success' },

  triggerIndexing: async () => {
    try {
      const response = await triggerIndexing()
      set({
        status: response.status,
        documentCount: response.documentCount,
        totalDocuments: response.totalDocuments,
        message: response.message,
        timestamp: response.timestamp,
      })
      get().pollStatus()
    } catch (err) {
      set({
        status: 'FAILED',
        message: err instanceof Error ? err.message : 'Failed to trigger indexing',
        snackbar: {
          open: true,
          message: 'Failed to trigger indexing',
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
                  ? `Indexing completed: ${response.documentCount} documents processed`
                  : (response.message ?? 'Indexing failed'),
              severity: response.status === 'COMPLETED' ? 'success' : 'error',
            },
          })
        }
      } catch {
        get().stopPolling()
        set({
          status: 'FAILED',
          message: 'Failed to fetch indexing status',
          snackbar: {
            open: true,
            message: 'Failed to fetch indexing status',
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
}))
