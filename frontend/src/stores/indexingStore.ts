import { create } from 'zustand'
import type { DocumentSourceType, IndexingStatus } from '../types/api'
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
  documentsFailed: number
  // #518: the true count of indexed documents, including RSS attachments - equals documentCount
  // for FILESYSTEM/HTTP_DIRECTORY runs (one processed file is exactly one document), but can
  // exceed it for an RSS_FEED run whose entries carry attachments.
  documentsIndexedTotal: number
  message: string | null
  timestamp: string | null
  isPolling: boolean
  // #518 review, finding 1: which wording a run uses (feed entries vs. plain document count) must
  // be decided by the library's own, unchanging sourceType - never by comparing documentCount and
  // documentsIndexedTotal, which happens to coincide for an RSS_FEED run whose entries carried no
  // attachments at all and would otherwise make the same library's label flicker from run to run.
  sourceType: DocumentSourceType | null
}

export const IDLE_RUN_STATE: IndexingRunState = {
  status: 'IDLE',
  documentCount: 0,
  totalDocuments: 0,
  documentsSkipped: 0,
  documentsFailed: 0,
  documentsIndexedTotal: 0,
  message: null,
  timestamp: null,
  isPolling: false,
  sourceType: null,
}

interface IndexingState {
  runsByLibrary: Record<string, IndexingRunState>
  snackbar: Snackbar

  triggerIndexing: (libraryId: string, sourceType: DocumentSourceType) => Promise<void>
  loadStatus: (libraryId: string, sourceType: DocumentSourceType) => Promise<void>
  stopPolling: (libraryId: string) => void
  closeSnackbar: () => void
  reset: () => void
}

const pollIntervalIds: Record<string, ReturnType<typeof setInterval>> = {}

/**
 * Formats a completed run's counts for the completion snackbar (#518). Which wording is used is
 * decided by isRssFeed (the library's own sourceType, #518 review finding 1) rather than by
 * comparing documentsIndexedTotal to documentCount - an RSS_FEED run whose entries carried no
 * attachments would otherwise wrongly fall back to the FILESYSTEM/HTTP_DIRECTORY wording.
 */
function formatCompletionMessage(
  response: {
    documentCount: number
    totalDocuments: number
    documentsSkipped: number
    documentsFailed: number
    documentsIndexedTotal: number
  },
  isRssFeed: boolean,
): string {
  // #518 review, finding 3: documentsFailed used to be visible only inside the backend's own
  // free-text message, never in the client-built one - named explicitly here, but only when it
  // actually occurred, so a clean run's message stays as short as before.
  const failedSuffix =
    response.documentsFailed > 0 ? `, davon ${response.documentsFailed} fehlgeschlagen` : ''
  if (isRssFeed) {
    return (
      `Indizierung abgeschlossen: ${response.totalDocuments} Feed-Einträge, ` +
      `${response.documentsSkipped} übersprungen, ${response.documentCount} indiziert ` +
      `(${response.documentsIndexedTotal} Dokumente insgesamt)${failedSuffix}`
    )
  }
  return `Indizierung abgeschlossen: ${response.documentCount} verarbeitet, ${response.documentsSkipped} übersprungen${failedSuffix}`
}

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

  triggerIndexing: async (libraryId: string, sourceType: DocumentSourceType) => {
    try {
      // #478: sourceType and every typed configuration field come from the library itself
      // (ADR-0018) - the trigger only ever names the library. sourceType is passed in purely for
      // this store's own wording decisions (#518 review, finding 1), not sent to the backend.
      const response = await triggerIndexing(libraryId)
      setRun(
        libraryId,
        {
          status: response.status,
          documentCount: response.documentCount,
          totalDocuments: response.totalDocuments,
          documentsSkipped: response.documentsSkipped,
          documentsFailed: response.documentsFailed,
          documentsIndexedTotal: response.documentsIndexedTotal,
          message: response.message,
          timestamp: response.timestamp,
          sourceType,
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
        setRun(libraryId, { status: 'FAILED', message, sourceType }, set, get)
      }
      set({ snackbar: { open: true, message, severity: 'error' } })
    }
  },

  loadStatus: async (libraryId: string, sourceType: DocumentSourceType) => {
    // Reset to IDLE up front: if this library never had a status loaded before, or the fetch
    // below fails, the section must show this library's own default rather than whatever another
    // library left behind in a shared field.
    setRun(libraryId, { ...IDLE_RUN_STATE, sourceType }, set, get)
    try {
      const response = await getIndexingStatus(libraryId)
      setRun(
        libraryId,
        {
          status: response.status,
          documentCount: response.documentCount,
          totalDocuments: response.totalDocuments,
          documentsSkipped: response.documentsSkipped,
          documentsFailed: response.documentsFailed,
          documentsIndexedTotal: response.documentsIndexedTotal,
          message: response.message,
          timestamp: response.timestamp,
          sourceType,
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

  reset: () => {
    Object.keys(pollIntervalIds).forEach((libraryId) => get().stopPolling(libraryId))
    set({
      runsByLibrary: {},
      snackbar: { open: false, message: '', severity: 'success' },
    })
  },
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
      // The run entry already carries sourceType, set by triggerIndexing/loadStatus before
      // polling ever starts (#518 review, finding 1) - polling itself never learns it anew.
      const isRssFeed = get().runsByLibrary[libraryId]?.sourceType === 'RSS_FEED'
      setRun(
        libraryId,
        {
          status: response.status,
          documentCount: response.documentCount,
          totalDocuments: response.totalDocuments,
          documentsSkipped: response.documentsSkipped,
          documentsFailed: response.documentsFailed,
          documentsIndexedTotal: response.documentsIndexedTotal,
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
                ? formatCompletionMessage(response, isRssFeed)
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
