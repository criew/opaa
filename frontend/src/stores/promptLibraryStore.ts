import { create } from 'zustand'
import type {
  PromptLibraryRequest,
  PromptLibraryResponse,
  PromptLibraryUpdateRequest,
  PromptRequest,
  PromptResponse,
} from '../types/api'
import {
  createPrompt,
  createPromptLibrary,
  deletePrompt,
  deletePromptLibrary,
  getPromptLibraries,
  getPromptLibrary,
  getPrompts,
  updatePrompt,
  updatePromptLibrary,
} from '../services/promptLibraryApi'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

interface PromptLibraryState {
  libraries: PromptLibraryResponse[]
  details: Record<string, PromptLibraryResponse>
  promptsByLibrary: Record<string, PromptResponse[]>
  /** Why a library's prompts could not be read - kept per library, apart from the list error. */
  promptsErrorByLibrary: Record<string, string>
  isLoading: boolean
  error: string | null
  reset: () => void
  loadLibraries: () => Promise<void>
  loadLibrary: (promptLibraryId: string) => Promise<void>
  createLibrary: (request: PromptLibraryRequest) => Promise<string>
  updateLibrary: (promptLibraryId: string, request: PromptLibraryUpdateRequest) => Promise<void>
  deleteLibrary: (promptLibraryId: string) => Promise<void>
  loadPrompts: (promptLibraryId: string) => Promise<void>
  /** Creates the prompt when `promptId` is null, replaces it otherwise. */
  savePrompt: (
    promptLibraryId: string,
    promptId: string | null,
    request: PromptRequest,
  ) => Promise<PromptResponse>
  removePrompt: (promptLibraryId: string, promptId: string) => Promise<void>
}

function byName(list: PromptLibraryResponse[]): PromptLibraryResponse[] {
  return [...list].sort((a, b) => a.name.localeCompare(b.name))
}

/** The server's own order: sortOrder ascending, then name. */
function byListOrder(list: PromptResponse[]): PromptResponse[] {
  return [...list].sort((a, b) => a.sortOrder - b.sortOrder || a.name.localeCompare(b.name))
}

/**
 * Prompt libraries and their prompts, cached per session. Every write reloads what it changes
 * from the server, so the list's `promptCount` and the detail view never disagree with the
 * server's answer. Grants are not held here - they live in `grantStore` like every asset's.
 */
export const usePromptLibraryStore = create<PromptLibraryState>((set, get) => ({
  libraries: [],
  details: {},
  promptsByLibrary: {},
  promptsErrorByLibrary: {},
  isLoading: false,
  error: null,

  reset: () =>
    set({
      libraries: [],
      details: {},
      promptsByLibrary: {},
      promptsErrorByLibrary: {},
      isLoading: false,
      error: null,
    }),

  loadLibraries: async () => {
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const libraries = byName(await getPromptLibraries())
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ libraries, isLoading: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        error:
          err instanceof Error ? err.message : 'Prompt-Bibliotheken konnten nicht geladen werden',
        isLoading: false,
      })
    }
  },

  loadLibrary: async (promptLibraryId) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const library = await getPromptLibrary(promptLibraryId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ details: { ...get().details, [promptLibraryId]: library } })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        error: err instanceof Error ? err.message : 'Prompt-Bibliothek konnte nicht geladen werden',
      })
    }
  },

  createLibrary: async (request) => {
    const sessionEpoch = currentSessionEpoch()
    const library = await createPromptLibrary(request)
    if (isStaleSessionEpoch(sessionEpoch)) return library.id
    set({ details: { ...get().details, [library.id]: library } })
    await get().loadLibraries()
    return library.id
  },

  updateLibrary: async (promptLibraryId, request) => {
    const sessionEpoch = currentSessionEpoch()
    const library = await updatePromptLibrary(promptLibraryId, request)
    if (isStaleSessionEpoch(sessionEpoch)) return
    set({ details: { ...get().details, [promptLibraryId]: library } })
    await get().loadLibraries()
  },

  deleteLibrary: async (promptLibraryId) => {
    const sessionEpoch = currentSessionEpoch()
    await deletePromptLibrary(promptLibraryId)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const details = { ...get().details }
    delete details[promptLibraryId]
    const promptsByLibrary = { ...get().promptsByLibrary }
    delete promptsByLibrary[promptLibraryId]
    set({ details, promptsByLibrary })
    await get().loadLibraries()
  },

  loadPrompts: async (promptLibraryId) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const prompts = byListOrder(await getPrompts(promptLibraryId))
      if (isStaleSessionEpoch(sessionEpoch)) return
      const promptsErrorByLibrary = { ...get().promptsErrorByLibrary }
      delete promptsErrorByLibrary[promptLibraryId]
      set({
        promptsByLibrary: { ...get().promptsByLibrary, [promptLibraryId]: prompts },
        promptsErrorByLibrary,
      })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        promptsErrorByLibrary: {
          ...get().promptsErrorByLibrary,
          [promptLibraryId]:
            err instanceof Error ? err.message : 'Prompts konnten nicht geladen werden',
        },
      })
    }
  },

  savePrompt: async (promptLibraryId, promptId, request) => {
    const saved = promptId
      ? await updatePrompt(promptLibraryId, promptId, request)
      : await createPrompt(promptLibraryId, request)
    await Promise.all([get().loadPrompts(promptLibraryId), get().loadLibrary(promptLibraryId)])
    return saved
  },

  removePrompt: async (promptLibraryId, promptId) => {
    await deletePrompt(promptLibraryId, promptId)
    await Promise.all([get().loadPrompts(promptLibraryId), get().loadLibrary(promptLibraryId)])
  },
}))
