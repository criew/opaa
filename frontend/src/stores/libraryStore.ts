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
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

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
    // #575: captured before the await below - checked again once it resolves, so a response
    // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
    // previous user's libraries into the now-emptied store.
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const libraries = sortLibraries(await getLibraries())
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ libraries, isLoading: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Bibliotheken konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  loadLibraryDetails: async (libraryId: string) => {
    const sessionEpoch = currentSessionEpoch()
    try {
      const library = await getLibrary(libraryId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ libraryDetails: { ...get().libraryDetails, [libraryId]: library } })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Bibliotheksdetails konnten nicht geladen werden'
      set({ error: message })
    }
  },

  createNewLibrary: async (request) => {
    const sessionEpoch = currentSessionEpoch()
    const library = await createLibrary(request)
    // #575: same reasoning as loadLibraries/loadLibraryDetails above - a logout in between must
    // not let the newly created library resurrect into the now-emptied store.
    if (isStaleSessionEpoch(sessionEpoch)) return library.id
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
    const sessionEpoch = currentSessionEpoch()
    await deleteLibrary(libraryId)
    // #575: loadLibraries() below already guards its own write-back - this direct set() needs the
    // same guard, otherwise a logout in between still resurrects a (stale) libraryDetails map into
    // the store reset() just cleared.
    if (isStaleSessionEpoch(sessionEpoch)) return
    const rest = { ...get().libraryDetails }
    delete rest[libraryId]
    set({ libraryDetails: rest })
    await get().loadLibraries()
  },
}))
