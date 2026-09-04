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
  getSearchPermissionProfiles,
  getSearchStatus,
  runMetadataBackfillBatch,
  runSearchDiagnosis,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/**
 * Sequence of the latest loadDocumentChunks call: an answer to an earlier call is dropped, so two
 * quick clicks never leave the page showing the document that was asked for first.
 */
let latestDocumentChunksRequest = 0

/** Documents per backfill call; small enough that a pause takes effect within seconds. */
const METADATA_BACKFILL_BATCH_SIZE = 10

/**
 * The state of one library's core-metadata backfill as this page drives it (#1067): the page loops
 * batch calls while `running`; pausing clears the flag and the loop stops after the batch in
 * flight - nothing server-side keeps running, the next start simply calls again.
 */
export interface MetadataBackfillRun {
  running: boolean
  done: boolean
  processedDocuments: number
  markedForNextRun: number
  skippedDocuments: number
  error: string | null
}

const NEW_RUN: MetadataBackfillRun = {
  running: false,
  done: false,
  processedDocuments: 0,
  markedForNextRun: 0,
  skippedDocuments: 0,
  error: null,
}

interface SearchAdminState {
  status: SearchStatusResponse | null
  profiles: SearchPermissionProfileResponse[]
  statusError: string | null
  diagnosis: SearchDiagnosisResponse | null
  diagnosisError: string | null
  isRunningDiagnosis: boolean
  documentChunks: DocumentChunksResponse | null
  documentChunksError: string | null
  isLoadingDocumentChunks: boolean
  metadataBackfillRuns: Record<string, MetadataBackfillRun>
  reset: () => void
  loadStatus: () => Promise<void>
  runDiagnosis: (request: SearchDiagnosisRequest) => Promise<void>
  loadDocumentChunks: (documentId: string) => Promise<void>
  startMetadataBackfill: (libraryId: string) => Promise<void>
  pauseMetadataBackfill: (libraryId: string) => void
}

const EMPTY: Omit<
  SearchAdminState,
  | 'reset'
  | 'loadStatus'
  | 'runDiagnosis'
  | 'loadDocumentChunks'
  | 'startMetadataBackfill'
  | 'pauseMetadataBackfill'
> = {
  status: null,
  profiles: [],
  statusError: null,
  diagnosis: null,
  diagnosisError: null,
  isRunningDiagnosis: false,
  documentChunks: null,
  documentChunksError: null,
  isLoadingDocumentChunks: false,
  metadataBackfillRuns: {},
}

/**
 * The state of the "Suche & Indexierung" administration page (#1053). Read-only except for the
 * core-metadata backfill (#1067), which is an explicit, library-wise start on this page.
 *
 * A diagnosis result of a run in someone else's rights context is never persisted anywhere -
 * it lives in this store for as long as the page is open and is dropped on sign-out like every
 * other session-scoped cache.
 */
export const useSearchAdminStore = create<SearchAdminState>((set, get) => {
  function updateRun(libraryId: string, patch: Partial<MetadataBackfillRun>) {
    set((state) => ({
      metadataBackfillRuns: {
        ...state.metadataBackfillRuns,
        [libraryId]: { ...(state.metadataBackfillRuns[libraryId] ?? NEW_RUN), ...patch },
      },
    }))
  }

  return {
    ...EMPTY,

    reset: () => set({ ...EMPTY }),

    loadStatus: async () => {
      const sessionEpoch = currentSessionEpoch()
      try {
        const [status, profiles] = await Promise.all([
          getSearchStatus(),
          getSearchPermissionProfiles(),
        ])
        if (isStaleSessionEpoch(sessionEpoch)) return
        set({ status, profiles, statusError: null })
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

    /**
     * Calls the batch endpoint until it reports done or the run is paused, refreshing the status
     * table after every batch so the counters move while the run lasts. A second start while a run
     * is in flight is ignored - one loop per library.
     */
    startMetadataBackfill: async (libraryId) => {
      if (get().metadataBackfillRuns[libraryId]?.running) return
      const sessionEpoch = currentSessionEpoch()
      updateRun(libraryId, { running: true, done: false, error: null })
      while (get().metadataBackfillRuns[libraryId]?.running) {
        try {
          const result = await runMetadataBackfillBatch({
            libraryId,
            batchSize: METADATA_BACKFILL_BATCH_SIZE,
          })
          if (isStaleSessionEpoch(sessionEpoch)) return
          const previous = get().metadataBackfillRuns[libraryId] ?? NEW_RUN
          updateRun(libraryId, {
            processedDocuments: previous.processedDocuments + result.processedDocuments,
            markedForNextRun: previous.markedForNextRun + result.markedForNextRun,
            skippedDocuments: previous.skippedDocuments + result.skippedDocuments,
            done: result.done,
          })
          const status = await getSearchStatus()
          if (isStaleSessionEpoch(sessionEpoch)) return
          set({ status, statusError: null })
          if (result.done) {
            updateRun(libraryId, { running: false })
            return
          }
        } catch (err) {
          if (isStaleSessionEpoch(sessionEpoch)) return
          updateRun(libraryId, {
            running: false,
            error: err instanceof Error ? err.message : 'Nachrüsten fehlgeschlagen',
          })
          return
        }
      }
    },

    pauseMetadataBackfill: (libraryId) => {
      if (!get().metadataBackfillRuns[libraryId]?.running) return
      updateRun(libraryId, { running: false })
    },
  }
})
