import { create } from 'zustand'
import type {
  DocumentChunksResponse,
  SearchDiagnosisRequest,
  SearchDiagnosisResponse,
  SearchPermissionProfileResponse,
  SearchStatusResponse,
} from '../types/api'
import {
  getDocumentChunks,
  getSearchDiagnosisContext,
  getSearchStatus,
  runContextPrefixRerunBatch,
  runMetadataBackfillBatch,
  runSearchDiagnosis,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/**
 * Sequence of the latest loadDocumentChunks call: an answer to an earlier call is dropped, so two
 * quick clicks never leave the page showing the document that was asked for first.
 */
let latestDocumentChunksRequest = 0

/** Documents per batch call; small enough that a pause takes effect within seconds. */
const LIBRARY_BATCH_SIZE = 50

/**
 * Consecutive batches that advanced nothing before the loop gives up regardless of `done` - a
 * server that keeps answering "not done, nothing advanced" must not keep this page polling forever.
 */
const MAX_BATCHES_WITHOUT_PROGRESS = 3

/** Hard ceiling on batch calls per start, so no defect on either side can turn into an endless loop. */
const MAX_BATCHES_PER_START = 1000

export const BATCH_RUN_STALLED_MESSAGE =
  'Der Lauf wurde angehalten: Mehrere Chargen nacheinander haben kein Dokument vorangebracht.'

/**
 * The state of one library's chargen run as this page drives it - the core-metadata backfill
 * (#1067) and the Kontextpräfix-Nachlauf (#1072) alike: the page loops batch calls while
 * `running`; pausing clears the flag and the loop stops after the batch in flight - nothing
 * server-side keeps running, the next start simply calls again.
 */
export interface LibraryBatchRun {
  running: boolean
  done: boolean
  processedDocuments: number
  markedForNextRun: number
  skippedDocuments: number
  error: string | null
}

const NEW_RUN: LibraryBatchRun = {
  running: false,
  done: false,
  processedDocuments: 0,
  markedForNextRun: 0,
  skippedDocuments: 0,
  error: null,
}

/** What one batch call reports; `markedForNextRun` is 0 for a run that has no such outcome. */
interface BatchOutcome {
  processedDocuments: number
  markedForNextRun: number
  skippedDocuments: number
  done: boolean
}

interface SearchAdminState {
  status: SearchStatusResponse | null
  profiles: SearchPermissionProfileResponse[]
  personContextAvailable: boolean
  personContextHint: string
  statusError: string | null
  diagnosis: SearchDiagnosisResponse | null
  diagnosisError: string | null
  isRunningDiagnosis: boolean
  documentChunks: DocumentChunksResponse | null
  documentChunksError: string | null
  isLoadingDocumentChunks: boolean
  metadataBackfillRuns: Record<string, LibraryBatchRun>
  contextPrefixRuns: Record<string, LibraryBatchRun>
  reset: () => void
  loadStatus: () => Promise<void>
  runDiagnosis: (request: SearchDiagnosisRequest) => Promise<void>
  loadDocumentChunks: (documentId: string) => Promise<void>
  startMetadataBackfill: (libraryId: string) => Promise<void>
  pauseMetadataBackfill: (libraryId: string) => void
  startContextPrefixRerun: (libraryId: string) => Promise<void>
  pauseContextPrefixRerun: (libraryId: string) => void
}

const EMPTY: Omit<
  SearchAdminState,
  | 'reset'
  | 'loadStatus'
  | 'runDiagnosis'
  | 'loadDocumentChunks'
  | 'startMetadataBackfill'
  | 'pauseMetadataBackfill'
  | 'startContextPrefixRerun'
  | 'pauseContextPrefixRerun'
> = {
  status: null,
  profiles: [],
  personContextAvailable: false,
  personContextHint: '',
  statusError: null,
  diagnosis: null,
  diagnosisError: null,
  isRunningDiagnosis: false,
  documentChunks: null,
  documentChunksError: null,
  isLoadingDocumentChunks: false,
  metadataBackfillRuns: {},
  contextPrefixRuns: {},
}

/**
 * The state of the "Suche & Indexierung" administration page (#1053). Read-only except for the
 * two chargen runs - the core-metadata backfill (#1067) and the Kontextpraefix-Nachlauf (#1072) -
 * each an explicit, library-wise start on this page.
 *
 * A diagnosis result of a run in someone else's rights context is never persisted anywhere -
 * it lives in this store for as long as the page is open and is dropped on sign-out like every
 * other session-scoped cache.
 */
export const useSearchAdminStore = create<SearchAdminState>((set, get) => {
  type RunsKey = 'metadataBackfillRuns' | 'contextPrefixRuns'

  function updateRun(key: RunsKey, libraryId: string, patch: Partial<LibraryBatchRun>) {
    set((state) => ({
      [key]: { ...state[key], [libraryId]: { ...(state[key][libraryId] ?? NEW_RUN), ...patch } },
    }))
  }

  /**
   * Calls a batch endpoint until it reports done or the run is paused, refreshing the status table
   * after every batch so the counters move while the run lasts. A second start while a run is in
   * flight is ignored - one loop per library and kind.
   */
  async function driveBatches(
    key: RunsKey,
    libraryId: string,
    batch: () => Promise<BatchOutcome>,
    failureMessage: string,
  ) {
    if (get()[key][libraryId]?.running) return
    const sessionEpoch = currentSessionEpoch()
    updateRun(key, libraryId, { running: true, done: false, error: null })
    let batches = 0
    let batchesWithoutProgress = 0
    while (get()[key][libraryId]?.running) {
      try {
        const result = await batch()
        if (isStaleSessionEpoch(sessionEpoch)) return
        const previous = get()[key][libraryId] ?? NEW_RUN
        updateRun(key, libraryId, {
          processedDocuments: previous.processedDocuments + result.processedDocuments,
          markedForNextRun: previous.markedForNextRun + result.markedForNextRun,
          skippedDocuments: previous.skippedDocuments + result.skippedDocuments,
          done: result.done,
        })
        const status = await getSearchStatus()
        if (isStaleSessionEpoch(sessionEpoch)) return
        set({ status, statusError: null })
        if (result.done) {
          updateRun(key, libraryId, { running: false })
          return
        }
        batches += 1
        batchesWithoutProgress =
          result.processedDocuments + result.markedForNextRun > 0 ? 0 : batchesWithoutProgress + 1
        if (
          batchesWithoutProgress >= MAX_BATCHES_WITHOUT_PROGRESS ||
          batches >= MAX_BATCHES_PER_START
        ) {
          updateRun(key, libraryId, { running: false, error: BATCH_RUN_STALLED_MESSAGE })
          return
        }
      } catch (err) {
        if (isStaleSessionEpoch(sessionEpoch)) return
        updateRun(key, libraryId, {
          running: false,
          error: err instanceof Error ? err.message : failureMessage,
        })
        return
      }
    }
  }

  return {
    ...EMPTY,

    reset: () => set({ ...EMPTY }),

    loadStatus: async () => {
      const sessionEpoch = currentSessionEpoch()
      try {
        const [status, context] = await Promise.all([
          getSearchStatus(),
          getSearchDiagnosisContext(),
        ])
        if (isStaleSessionEpoch(sessionEpoch)) return
        set({
          status,
          profiles: context.permissionProfiles,
          personContextAvailable: context.personContextAvailable,
          personContextHint: context.personContextHint,
          statusError: null,
        })
      } catch (err) {
        if (isStaleSessionEpoch(sessionEpoch)) return
        set({
          statusError: err instanceof Error ? err.message : 'Status konnte nicht geladen werden',
        })
      }
    },

    runDiagnosis: async (request) => {
      const sessionEpoch = currentSessionEpoch()
      set({ isRunningDiagnosis: true, diagnosis: null, diagnosisError: null })
      try {
        const diagnosis = await runSearchDiagnosis(request)
        if (isStaleSessionEpoch(sessionEpoch)) return
        set({ diagnosis, isRunningDiagnosis: false })
      } catch (err) {
        if (isStaleSessionEpoch(sessionEpoch)) return
        set({
          diagnosisError: err instanceof Error ? err.message : 'Diagnose fehlgeschlagen',
          isRunningDiagnosis: false,
        })
      }
    },

    loadDocumentChunks: async (documentId) => {
      const sessionEpoch = currentSessionEpoch()
      const request = ++latestDocumentChunksRequest
      const isSuperseded = () =>
        isStaleSessionEpoch(sessionEpoch) || request !== latestDocumentChunksRequest
      set({ isLoadingDocumentChunks: true, documentChunks: null, documentChunksError: null })
      try {
        const documentChunks = await getDocumentChunks(documentId)
        if (isSuperseded()) return
        set({ documentChunks, isLoadingDocumentChunks: false })
      } catch (err) {
        if (isSuperseded()) return
        set({
          documentChunksError:
            err instanceof Error ? err.message : 'Chunks konnten nicht geladen werden',
          isLoadingDocumentChunks: false,
        })
      }
    },

    startMetadataBackfill: (libraryId) =>
      driveBatches(
        'metadataBackfillRuns',
        libraryId,
        () => runMetadataBackfillBatch({ libraryId, batchSize: LIBRARY_BATCH_SIZE }),
        'Nachrüsten fehlgeschlagen',
      ),

    pauseMetadataBackfill: (libraryId) => {
      if (!get().metadataBackfillRuns[libraryId]?.running) return
      updateRun('metadataBackfillRuns', libraryId, { running: false })
    },

    startContextPrefixRerun: (libraryId) =>
      driveBatches(
        'contextPrefixRuns',
        libraryId,
        async () => ({
          ...(await runContextPrefixRerunBatch({ libraryId, batchSize: LIBRARY_BATCH_SIZE })),
          markedForNextRun: 0,
        }),
        'Neu-Einbetten fehlgeschlagen',
      ),

    pauseContextPrefixRerun: (libraryId) => {
      if (!get().contextPrefixRuns[libraryId]?.running) return
      updateRun('contextPrefixRuns', libraryId, { running: false })
    },
  }
})
