import { create } from 'zustand'
import type { MetadataFilterOptionsResponse } from '../types/api'
import { getMetadataFilterOptions } from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

export interface MetadataFilterScope {
  chatId: string | null
  useKnowledge: boolean
  libraryIds: string[]
}

interface MetadataFilterOptionsState {
  /** The options of the scope they were last loaded for, null before the first load. */
  options: MetadataFilterOptionsResponse | null
  /** The scope `options` describes - a component compares it with the current scope before
   * trusting the numbers, exactly like spaceStore's libraryAssociationsSpaceId (#783). */
  optionsScopeKey: string | null
  isLoading: boolean
  error: string | null
  loadOptions: (scope: MetadataFilterScope) => Promise<void>
  reset: () => void
}

export function metadataFilterScopeKey(scope: MetadataFilterScope): string {
  return JSON.stringify([scope.chatId, scope.useKnowledge, [...scope.libraryIds].sort()])
}

let loadSequence = 0

/**
 * #1070: the Füllstand per field and the values occurring in the caller's search scope - loaded
 * from the backend in the caller's own rights context, never computed here. Reloaded whenever the
 * scope changes or the filter popover opens, so the numbers describe the bestand the next
 * question would search.
 */
export const useMetadataFilterOptionsStore = create<MetadataFilterOptionsState>((set) => ({
  options: null,
  optionsScopeKey: null,
  isLoading: false,
  error: null,

  loadOptions: async (scope) => {
    const sessionEpoch = currentSessionEpoch()
    const requestId = ++loadSequence
    set({ isLoading: true, error: null })
    try {
      const options = await getMetadataFilterOptions(scope)
      if (isStaleSessionEpoch(sessionEpoch) || requestId !== loadSequence) return
      set({ options, optionsScopeKey: metadataFilterScopeKey(scope), isLoading: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch) || requestId !== loadSequence) return
      const message =
        err instanceof Error ? err.message : 'Filteroptionen konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  reset: () => {
    loadSequence++
    set({ options: null, optionsScopeKey: null, isLoading: false, error: null })
  },
}))
