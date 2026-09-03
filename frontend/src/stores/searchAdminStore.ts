import { create } from 'zustand'
import type {
  SearchDiagnosisRequest,
  SearchDiagnosisResponse,
  SearchPermissionProfileResponse,
  SearchStatusResponse,
} from '../types/api'
import { getSearchPermissionProfiles, getSearchStatus, runSearchDiagnosis } from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

interface SearchAdminState {
  status: SearchStatusResponse | null
  profiles: SearchPermissionProfileResponse[]
  statusError: string | null
  diagnosis: SearchDiagnosisResponse | null
  diagnosisError: string | null
  isRunningDiagnosis: boolean
  reset: () => void
  loadStatus: () => Promise<void>
  runDiagnosis: (request: SearchDiagnosisRequest) => Promise<void>
}

const EMPTY: Omit<SearchAdminState, 'reset' | 'loadStatus' | 'runDiagnosis'> = {
  status: null,
  profiles: [],
  statusError: null,
  diagnosis: null,
  diagnosisError: null,
  isRunningDiagnosis: false,
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
}))
