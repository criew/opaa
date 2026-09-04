import { create } from 'zustand'
import type {
  DocumentSourceType,
  IndexingRunMode,
  IndexingRunResponse,
  IndexingStatus,
} from '../types/api'
import { triggerIndexing, getIndexingStatus, getIndexingRuns } from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

const POLL_INTERVAL_MS = 2000

// #500 review, finding 5: the exact German text DocumentIndexingService#toIndexingSourceType
// sends for an UPLOAD library (no run type at all, 409) - matched here so triggerIndexing can show
// this specific message instead of the generic failure one, and leave status untouched rather than
// FAILED, since no run was ever started.
export const UPLOAD_LIBRARY_INDEXING_ERROR =
  'Für UPLOAD-Bibliotheken gibt es keinen Indizierungslauf'

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
  // #513: the last 10 runs for a library, each with its own protocol - distinct from
  // runsByLibrary above, which only ever tracks the single current/most recent run for the
  // polling-driven "Quellkonfiguration" section.
  runHistoryByLibrary: Record<string, IndexingRunResponse[]>
  snackbar: Snackbar

  triggerIndexing: (
    libraryId: string,
    sourceType: DocumentSourceType,
    runMode?: IndexingRunMode,
  ) => Promise<void>
  loadStatus: (libraryId: string, sourceType: DocumentSourceType) => Promise<void>
  loadRunHistory: (libraryId: string) => Promise<void>
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
  runHistoryByLibrary: {},
  snackbar: { open: false, message: '', severity: 'success' },

  loadRunHistory: async (libraryId: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const response = await getIndexingRuns(libraryId)
      // #575: a response arriving after a logout must not write into the now-emptied store.
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        runHistoryByLibrary: { ...get().runHistoryByLibrary, [libraryId]: response.runs },
      })
    } catch {
      // Die Bibliotheks-Detailseite zeigt weiterhin den aktuellen Status (runsByLibrary) an, auch
      // wenn das Protokoll früherer Läufe nicht geladen werden konnte.
    }
  },

  triggerIndexing: async (
    libraryId: string,
    sourceType: DocumentSourceType,
    runMode?: IndexingRunMode,
  ) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      // #478: sourceType and every typed configuration field come from the library itself
      // (ADR-0018) - the trigger only ever names the library. sourceType is passed in purely for
      // this store's own wording decisions (#518 review, finding 1), not sent to the backend.
      const response = runMode
        ? await triggerIndexing(libraryId, runMode)
        : await triggerIndexing(libraryId)
      // #575: a response arriving after a logout must not write into the now-emptied store, nor
      // start a poll interval whose ticks would keep writing into it afterwards.
      if (isStaleSessionEpoch(sessionEpoch)) return
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
      // #575: a failure arriving after a logout must not write into the now-emptied store either.
      if (isStaleSessionEpoch(sessionEpoch)) return
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
    const sessionEpoch = currentSessionEpoch()
    // Reset to IDLE up front: if this library never had a status loaded before, or the fetch
    // below fails, the section must show this library's own default rather than whatever another
    // library left behind in a shared field.
    setRun(libraryId, { ...IDLE_RUN_STATE, sourceType }, set, get)
    try {
      const response = await getIndexingStatus(libraryId)
      // #575: a response arriving after a logout must not write into the now-emptied store, nor
      // start a poll interval whose ticks would keep writing into it afterwards.
      if (isStaleSessionEpoch(sessionEpoch)) return
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
    // #604 review, nit (c): LibraryIndexingHistorySection's own mount effect already loads the
    // run history - a second call here duplicated that request on every mount without ever
    // being the one either component actually depended on.
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
      // #575 review: pre-existing gap from #513 - runHistoryByLibrary caches the previous user's
      // last 10 runs per library and was never cleared here, leaving it visible to whichever user
      // signs in next in the same tab until loadRunHistory happens to overwrite it.
      runHistoryByLibrary: {},
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
    // #575: this tick's token in the session epoch, captured before the await below. clearInterval
    // (see stopPolling, called by reset()) only stops *future* ticks - a tick already in flight
    // when reset() runs would otherwise still call setRun() once its own await resolves, writing a
    // run status back into runsByLibrary right after reset() just emptied it.
    const sessionEpoch = currentSessionEpoch()
    try {
      const response = await getIndexingStatus(libraryId)
      if (isStaleSessionEpoch(sessionEpoch)) return
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
        void get().loadRunHistory(libraryId)
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
      if (isStaleSessionEpoch(sessionEpoch)) return
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
