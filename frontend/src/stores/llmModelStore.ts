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
  updateExistingModel: (modelId: string, request: LlmModelRequest) => Promise<void>
  deleteExistingModel: (modelId: string) => Promise<void>
  activateExistingModel: (modelId: string) => Promise<void>
}

function sortModels(list: LlmModelResponse[]): LlmModelResponse[] {
  return [...list].sort((a, b) => a.displayName.localeCompare(b.displayName))
}

/**
 * Managed chat models (#759, admin API from #757) - list, create, edit, delete, activate. Modelled
 * after groupStore: session-epoch-guarded loads, actions that reload the list rather than
 * hand-patching it locally, so the list always reflects exactly what the server has.
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
    await createLlmModel(request)
    await get().loadModels()
  },

  updateExistingModel: async (modelId, request) => {
    await updateLlmModel(modelId, request)
    await get().loadModels()
  },

  deleteExistingModel: async (modelId) => {
    await deleteLlmModel(modelId)
    await get().loadModels()
  },

  activateExistingModel: async (modelId) => {
    await activateLlmModel(modelId)
    await get().loadModels()
  },
}))
