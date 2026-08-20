import { create } from 'zustand'
import type {
  LibraryListResponse,
  LibraryRequest,
  LibraryResponse,
  LibraryUpdateRequest,
} from '../types/api'
import {
  createLibrary,
  deleteLibrary,
  getLibrary,
  getLibraries,
  updateLibrary,
} from '../services/api'

interface LibraryState {
  libraries: LibraryListResponse[]
  libraryDetails: Record<string, LibraryResponse>
  isLoading: boolean
  error: string | null
  reset: () => void
  loadLibraries: () => Promise<void>
  loadLibraryDetails: (libraryId: string) => Promise<void>
  createNewLibrary: (request: LibraryRequest) => Promise<string>
  updateExistingLibrary: (libraryId: string, request: LibraryUpdateRequest) => Promise<void>
  deleteExistingLibrary: (libraryId: string) => Promise<void>
}

function sortLibraries(list: LibraryListResponse[]): LibraryListResponse[] {
  return [...list].sort((a, b) => a.name.localeCompare(b.name))
}

export const useLibraryStore = create<LibraryState>((set, get) => ({
  libraries: [],
  libraryDetails: {},
  isLoading: false,
  error: null,

  reset: () => set({ libraries: [], libraryDetails: {}, isLoading: false, error: null }),

  loadLibraries: async () => {
    set({ isLoading: true, error: null })
    try {
      const libraries = sortLibraries(await getLibraries())
      set({ libraries, isLoading: false })
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Bibliotheken konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  loadLibraryDetails: async (libraryId: string) => {
    try {
      const library = await getLibrary(libraryId)
      set({ libraryDetails: { ...get().libraryDetails, [libraryId]: library } })
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Bibliotheksdetails konnten nicht geladen werden'
      set({ error: message })
    }
  },

  createNewLibrary: async (request) => {
    const library = await createLibrary(request)
    // Caches the full LibraryResponse right away, so the overview can show the newly created
    // library without an extra round trip.
    set({ libraryDetails: { ...get().libraryDetails, [library.id]: library } })
    await get().loadLibraries()
    return library.id
  },

  updateExistingLibrary: async (libraryId, request) => {
    await updateLibrary(libraryId, request)
    await Promise.all([get().loadLibraries(), get().loadLibraryDetails(libraryId)])
  },

  deleteExistingLibrary: async (libraryId) => {
    await deleteLibrary(libraryId)
    const rest = { ...get().libraryDetails }
    delete rest[libraryId]
    set({ libraryDetails: rest })
    await get().loadLibraries()
  },
}))
