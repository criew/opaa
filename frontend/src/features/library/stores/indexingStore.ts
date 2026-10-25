import { create } from 'zustand'
import type {
  SourceTypeKey,
  IndexingRunMode,
  IndexingRunResponse,
  IndexingStatus,
  IndexingStatusResponse,
} from '@/shared/api/types'
import { triggerIndexing, getIndexingStatus, getIndexingRuns } from '../api/indexingApi'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import { sourceRegistration } from '../components/sources/registry'
import { registerSessionStore } from '@/shared/session/resettableStores'

const POLL_INTERVAL_MS = 2000

// Consecutive failed status fetches after which polling gives up and the run is shown as
// "Status unbekannt". Between failures the delay doubles from POLL_INTERVAL_MS up to
// MAX_POLL_BACKOFF_MS, so a short outage (deployment, token refresh) is ridden out.
export const MAX_CONSECUTIVE_STATUS_FAILURES = 5
const MAX_POLL_BACKOFF_MS = 30_000

// The exact German text DocumentIndexingService#executorFor
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

// A single global run status shared across every library would let a stale response for library
// A overwrite the state shown for library B after a quick switch, while A's interval silently kept
// polling and B never updated. Keyed state (the same runsByLibrary pattern as
// documentStore.documentsByLibrary) makes every response, and every poll interval, inherently
// scoped to the library it belongs to - a library that never successfully loaded a status simply
// falls back to the IDLE default below rather than showing another library's leftover state.
interface IndexingRunState {
  status: IndexingStatus
  documentCount: number
  totalDocuments: number
  documentsSkipped: number
  documentsFailed: number
  // The true count of indexed documents, including RSS attachments - equals documentCount
  // for FILESYSTEM/HTTP_DIRECTORY runs (one processed file is exactly one document), but can
  // exceed it for an RSS_FEED run whose entries carry attachments.
  documentsIndexedTotal: number
  message: string | null
  timestamp: string | null
  isPolling: boolean
  // Which wording a run uses (feed entries vs. plain document count) must
  // be decided by the library's own, unchanging sourceType - never by comparing documentCount and
  // documentsIndexedTotal, which happens to coincide for an RSS_FEED run whose entries carried no
  // attachments at all and would otherwise make the same library's label flicker from run to run.
  sourceType: SourceTypeKey | null
  // The scopes (Confluence spaces, S3 bucket/prefix) the most recent listing-assessing full sync
  // could not list completely - carried by the status response independently of which run is the
  // latest one, so the warning at the library survives incremental, webhook and event runs.
  unlistedScopeKeys: string[]
  // The category the newest run ended under, e.g. QUOTA_EXHAUSTED; null while it has none.
  failureCategory: string | null
  // Polling gave up after MAX_CONSECUTIVE_STATUS_FAILURES failed fetches. The last known status
  // is kept as is - a failed fetch says nothing about the run itself.
  statusUnknown: boolean
  // The first status request of this page view has answered (or failed); until then the IDLE
  // default above is only a placeholder.
  statusLoaded?: boolean
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
  unlistedScopeKeys: [],
  failureCategory: null,
  statusUnknown: false,
  statusLoaded: false,
}

interface IndexingState {
  runsByLibrary: Record<string, IndexingRunState>
  // The last 10 runs for a library, each with its own protocol - distinct from
  // runsByLibrary above, which only ever tracks the single current/most recent run for the
  // polling-driven "Quellkonfiguration" section.
  runHistoryByLibrary: Record<string, IndexingRunResponse[]>
  snackbar: Snackbar

  triggerIndexing: (
    libraryId: string,
    sourceType: SourceTypeKey,
    runMode?: IndexingRunMode,
  ) => Promise<void>
  loadStatus: (libraryId: string, sourceType: SourceTypeKey) => Promise<void>
  loadRunHistory: (libraryId: string) => Promise<void>
  stopPolling: (libraryId: string) => void
  // Resumes polling after it gave up, starting with an immediate fetch.
  retryStatusPolling: (libraryId: string) => void
  closeSnackbar: () => void
  reset: () => void
}

// One entry per library while it is polled. A tick schedules its successor only while its own
// entry is still the current one, so stopPolling also ends a tick that is still in flight.
interface PollHandle {
  timeoutId: ReturnType<typeof setTimeout> | null
  consecutiveFailures: number
}
const pollHandles: Record<string, PollHandle> = {}

/**
 * Formats a completed run's counts for the completion snackbar. Which wording is used is
 * decided by isRssFeed (the library's own sourceType) rather than by
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
  // documentsFailed is named explicitly, but only when it actually occurred, so a clean run's
  // message stays short.
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
      // A response arriving after a logout must not write into the now-emptied store.
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        runHistoryByLibrary: { ...get().runHistoryByLibrary, [libraryId]: response.runs },
      })
    } catch {
      // The library detail page keeps showing the current status (runsByLibrary), even
      // when the log of earlier runs could not be loaded.
    }
  },

  triggerIndexing: async (
    libraryId: string,
    sourceType: SourceTypeKey,
    runMode?: IndexingRunMode,
  ) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      // sourceType and every typed configuration field come from the library itself
      // (ADR-0018) - the trigger only ever names the library. sourceType is passed in purely for
      // this store's own wording decisions, not sent to the backend.
      const response = runMode
        ? await triggerIndexing(libraryId, runMode)
        : await triggerIndexing(libraryId)
      // A response arriving after a logout must not write into the now-emptied store, nor
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
          failureCategory: response.failureCategory ?? null,
          statusUnknown: false,
        },
        set,
        get,
      )
      get().stopPolling(libraryId)
      startPolling(libraryId, set, get)
    } catch (err) {
      // A failure arriving after a logout must not write into the now-emptied store either.
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

  loadStatus: async (libraryId: string, sourceType: SourceTypeKey) => {
    const sessionEpoch = currentSessionEpoch()
    // Reset to IDLE up front: if this library never had a status loaded before, or the fetch
    // below fails, the section must show this library's own default rather than whatever another
    // library left behind in a shared field.
    setRun(libraryId, { ...IDLE_RUN_STATE, sourceType }, set, get)
    try {
      const response = await getIndexingStatus(libraryId)
      // A response arriving after a logout must not write into the now-emptied store, nor
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
          unlistedScopeKeys: response.unlistedScopeKeys ?? [],
          failureCategory: response.failureCategory ?? null,
          statusLoaded: true,
        },
        set,
        get,
      )
      if (response.status === 'RUNNING') {
        startPolling(libraryId, set, get)
      }
    } catch {
      setRun(libraryId, { statusLoaded: true }, set, get)
      // Leaves the page on its IDLE default for this library rather than surfacing an error for a
      // status check the caller did not explicitly request.
    }
    // No run-history load here: LibraryIndexingHistorySection's own mount effect loads it.
  },

  stopPolling: (libraryId: string) => {
    const handle = pollHandles[libraryId]
    if (handle) {
      if (handle.timeoutId) clearTimeout(handle.timeoutId)
      delete pollHandles[libraryId]
    }
    setRun(libraryId, { isPolling: false }, set, get)
  },

  retryStatusPolling: (libraryId: string) => {
    get().stopPolling(libraryId)
    setRun(libraryId, { statusUnknown: false }, set, get)
    startPolling(libraryId, set, get, 0)
  },

  closeSnackbar: () => set((s) => ({ snackbar: { ...s.snackbar, open: false } })),

  reset: () => {
    Object.keys(pollHandles).forEach((libraryId) => get().stopPolling(libraryId))
    set({
      runsByLibrary: {},
      // runHistoryByLibrary caches the previous user's last 10 runs per library; left in place it
      // would stay visible to whichever user signs in next in the same tab.
      runHistoryByLibrary: {},
      snackbar: { open: false, message: '', severity: 'success' },
    })
  },
}))

registerSessionStore(useIndexingStore)

function startPolling(
  libraryId: string,
  set: (partial: Partial<IndexingState>) => void,
  get: () => IndexingState,
  firstDelayMs: number = POLL_INTERVAL_MS,
) {
  if (pollHandles[libraryId]) return
  const handle: PollHandle = { timeoutId: null, consecutiveFailures: 0 }
  pollHandles[libraryId] = handle
  setRun(libraryId, { isPolling: true }, set, get)
  scheduleTick(libraryId, handle, firstDelayMs, set, get)
}

function scheduleTick(
  libraryId: string,
  handle: PollHandle,
  delayMs: number,
  set: (partial: Partial<IndexingState>) => void,
  get: () => IndexingState,
) {
  handle.timeoutId = setTimeout(() => void runPollTick(libraryId, handle, set, get), delayMs)
}

function backoffDelayMs(consecutiveFailures: number): number {
  return Math.min(POLL_INTERVAL_MS * 2 ** consecutiveFailures, MAX_POLL_BACKOFF_MS)
}

async function runPollTick(
  libraryId: string,
  handle: PollHandle,
  set: (partial: Partial<IndexingState>) => void,
  get: () => IndexingState,
) {
  // This tick's token in the session epoch, captured before the await below. stopPolling (called
  // by reset()) only cancels the *pending* timeout - a tick already in flight when reset() runs
  // would otherwise still call setRun() once its own await resolves, writing a run status back
  // into runsByLibrary right after reset() just emptied it.
  const sessionEpoch = currentSessionEpoch()
  const isCurrent = () => !isStaleSessionEpoch(sessionEpoch) && pollHandles[libraryId] === handle
  let response: IndexingStatusResponse
  try {
    response = await getIndexingStatus(libraryId)
  } catch {
    if (!isCurrent()) return
    handle.consecutiveFailures += 1
    if (handle.consecutiveFailures >= MAX_CONSECUTIVE_STATUS_FAILURES) {
      get().stopPolling(libraryId)
      setRun(libraryId, { statusUnknown: true }, set, get)
      return
    }
    scheduleTick(libraryId, handle, backoffDelayMs(handle.consecutiveFailures), set, get)
    return
  }
  if (!isCurrent()) return
  handle.consecutiveFailures = 0
  // The run entry already carries sourceType, set by triggerIndexing/loadStatus before
  // polling ever starts - polling itself never learns it anew.
  const isRssFeed = Boolean(
    sourceRegistration(get().runsByLibrary[libraryId]?.sourceType)?.runCountsEntries,
  )
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
      unlistedScopeKeys: response.unlistedScopeKeys ?? [],
      failureCategory: response.failureCategory ?? null,
    },
    set,
    get,
  )

  if (response.status !== 'COMPLETED' && response.status !== 'FAILED') {
    scheduleTick(libraryId, handle, POLL_INTERVAL_MS, set, get)
    return
  }
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
