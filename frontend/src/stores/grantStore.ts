import { create } from 'zustand'
import type { AssetGrantRequest, AssetGrantResponse } from '../types/api'
import { getLibraryGrants, revokeLibraryGrant, upsertLibraryGrant } from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

interface GrantState {
  grantsByLibrary: Record<string, AssetGrantResponse[]>
  isLoading: boolean
  error: string | null
  reset: () => void
  loadGrants: (libraryId: string) => Promise<void>
  upsertExistingGrant: (libraryId: string, request: AssetGrantRequest) => Promise<void>
  revokeExistingGrant: (libraryId: string, grantId: string) => Promise<void>
}

// AssetGrantService#upsertGrant is idempotent per subject (see the OpenAPI operation summary): the
// response either replaces an existing grant for that subject or adds a new one. Matching by id
// mirrors that: a fresh grant gets appended, an unchanged-subject update replaces the same row
// in place rather than creating a duplicate.
function mergeGrant(
  existing: AssetGrantResponse[],
  updated: AssetGrantResponse,
): AssetGrantResponse[] {
  const index = existing.findIndex((grant) => grant.id === updated.id)
  if (index === -1) {
    return [...existing, updated]
  }
  const next = [...existing]
  next[index] = updated
  return next
}

export const useGrantStore = create<GrantState>((set, get) => ({
  grantsByLibrary: {},
  isLoading: false,
  error: null,

  reset: () => set({ grantsByLibrary: {}, isLoading: false, error: null }),

  loadGrants: async (libraryId: string) => {
    // #575: captured before the await below - checked again once it resolves, so a response
    // arriving after a logout (resetAllStores) skips its write-back instead of resurrecting the
    // previous user's grants into the now-emptied store.
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const grants = await getLibraryGrants(libraryId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        grantsByLibrary: { ...get().grantsByLibrary, [libraryId]: grants },
        isLoading: false,
      })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Berechtigungen konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  upsertExistingGrant: async (libraryId: string, request: AssetGrantRequest) => {
    const sessionEpoch = currentSessionEpoch()
    const updated = await upsertLibraryGrant(libraryId, request)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const existing = get().grantsByLibrary[libraryId] ?? []
    set({
      grantsByLibrary: { ...get().grantsByLibrary, [libraryId]: mergeGrant(existing, updated) },
    })
  },

  revokeExistingGrant: async (libraryId: string, grantId: string) => {
    const sessionEpoch = currentSessionEpoch()
    await revokeLibraryGrant(libraryId, grantId)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const existing = get().grantsByLibrary[libraryId] ?? []
    set({
      grantsByLibrary: {
        ...get().grantsByLibrary,
        [libraryId]: existing.filter((grant) => grant.id !== grantId),
      },
    })
  },
}))
