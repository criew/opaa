import { create } from 'zustand'
import type { AssetGrantRequest, AssetGrantResponse } from '../types/api'
import { getLibraryGrants, revokeLibraryGrant, upsertLibraryGrant } from '../services/api'

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
    set({ isLoading: true, error: null })
    try {
      const grants = await getLibraryGrants(libraryId)
      set({
        grantsByLibrary: { ...get().grantsByLibrary, [libraryId]: grants },
        isLoading: false,
      })
    } catch (err) {
      const message =
        err instanceof Error ? err.message : 'Berechtigungen konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  upsertExistingGrant: async (libraryId: string, request: AssetGrantRequest) => {
    const updated = await upsertLibraryGrant(libraryId, request)
    const existing = get().grantsByLibrary[libraryId] ?? []
    set({
      grantsByLibrary: { ...get().grantsByLibrary, [libraryId]: mergeGrant(existing, updated) },
    })
  },

  revokeExistingGrant: async (libraryId: string, grantId: string) => {
    await revokeLibraryGrant(libraryId, grantId)
    const existing = get().grantsByLibrary[libraryId] ?? []
    set({
      grantsByLibrary: {
        ...get().grantsByLibrary,
        [libraryId]: existing.filter((grant) => grant.id !== grantId),
      },
    })
  },
}))
