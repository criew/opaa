import { create } from 'zustand'
import type { EmbeddingInfoResponse, LlmModelRequest, LlmModelResponse } from '../types/api'
import {
  activateLlmModel,
  createLlmModel,
  deleteLlmModel,
  getEmbeddingInfo,
  getLlmModels,
  updateLlmModel,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

interface LlmModelState {
  models: LlmModelResponse[]
  embeddingInfo: EmbeddingInfoResponse | null
  isLoading: boolean
  error: string | null
  reset: () => void
  loadModels: () => Promise<void>
  loadEmbeddingInfo: () => Promise<void>
  createNewModel: (request: LlmModelRequest) => Promise<void>
  updateExistingModel: (modelId: string, request: LlmModelRequest) => Promise<LlmModelResponse>
  deleteExistingModel: (modelId: string) => Promise<void>
  activateExistingModel: (modelId: string) => Promise<void>
}

function sortModels(list: LlmModelResponse[]): LlmModelResponse[] {
  return [...list].sort((a, b) => a.displayName.localeCompare(b.displayName))
}

/**
 * Managed chat models (#759, admin API from #757) - list, create, edit, delete, activate.
 *
 * **Mutations patch `models` locally from the server's own response, they never call {@link
 * loadModels} again (#759 review).** `loadModels` sets `isLoading: true` while it runs, and
 * `LlmModelManagementPage` swaps the whole list for a "wird geladen" message whenever that flag is
 * set - so a full reload after every save/activate/delete briefly unmounted every
 * `LlmModelCard`, silently collapsing whichever panel the person was just looking at (and, for
 * save, dropping the confirmation it had just produced) a moment after the action that caused it.
 * Each mutation below already gets back everything it needs from its own response (or, for
 * delete, needs nothing back at all) to keep `models` correct without paying that price.
 */
export const useLlmModelStore = create<LlmModelState>((set, get) => ({
  models: [],
  embeddingInfo: null,
  isLoading: false,
  error: null,

  reset: () => set({ models: [], embeddingInfo: null, isLoading: false, error: null }),

  loadModels: async () => {
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const models = sortModels(await getLlmModels())
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ models, isLoading: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message = err instanceof Error ? err.message : 'Modelle konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  loadEmbeddingInfo: async () => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const embeddingInfo = await getEmbeddingInfo()
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ embeddingInfo })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Einbettungskonfiguration konnte nicht geladen werden'
      set({ error: message })
    }
  },

  createNewModel: async (request) => {
    const created = await createLlmModel(request)
    set({ models: sortModels([...get().models, created]) })
  },

  updateExistingModel: async (modelId, request) => {
    // Returned directly to the caller, which re-seeds its draft from it (#759 review) - the panel
    // stays open and any visible test result or save confirmation survives the round trip.
    const updated = await updateLlmModel(modelId, request)
    set({
      models: sortModels(get().models.map((m) => (m.id === modelId ? updated : m))),
    })
    return updated
  },

  deleteExistingModel: async (modelId) => {
    await deleteLlmModel(modelId)
    set({ models: get().models.filter((m) => m.id !== modelId) })
  },

  activateExistingModel: async (modelId) => {
    // The response only carries the newly activated model; every other model is deactivated in
    // the same backend transaction (see LlmModelService#activateModel's Javadoc), so the local
    // update mirrors that by turning every other entry's `active` off in the same step.
    const activated = await activateLlmModel(modelId)
    set({
      models: get().models.map((m) => (m.id === modelId ? activated : { ...m, active: false })),
    })
  },
}))
