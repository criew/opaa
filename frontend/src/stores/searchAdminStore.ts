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
  runSearchDiagnosis,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/**
 * Sequence of the latest loadDocumentChunks call: an answer to an earlier call is dropped, so two
 * quick clicks never leave the page showing the document that was asked for first.
 */
let latestDocumentChunksRequest = 0

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
  reset: () => void
  loadStatus: () => Promise<void>
  runDiagnosis: (request: SearchDiagnosisRequest) => Promise<void>
  loadDocumentChunks: (documentId: string) => Promise<void>
}

const EMPTY: Omit<
  SearchAdminState,
  'reset' | 'loadStatus' | 'runDiagnosis' | 'loadDocumentChunks'
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
}

/**
 * The read-only state of the "Suche & Indexierung" administration page (#1053).
 *
 * A diagnosis result of a run in someone else's rights context is never persisted anywhere -
 * it lives in this store for as long as the page is open and is dropped on sign-out like every
 * other session-scoped cache.
 */
export const useSearchAdminStore = create<SearchAdminState>((set) => ({
  ...EMPTY,

  reset: () => set({ ...EMPTY }),

  loadStatus: async () => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const [status, context] = await Promise.all([getSearchStatus(), getSearchDiagnosisContext()])
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
}))
