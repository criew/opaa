import { create } from 'zustand'
import type { AssetGrantRequest, AssetGrantResponse, AssetType } from '../types/api'
import { getAssetGrants, revokeAssetGrant, upsertAssetGrant } from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

/** The key of one asset's grant list: an asset id alone is not unique across asset types. */
export function assetKey(assetType: AssetType, assetId: string): string {
  return `${assetType}:${assetId}`
}

interface GrantState {
  grantsByAsset: Record<string, AssetGrantResponse[]>
  isLoading: boolean
  error: string | null
  reset: () => void
  loadGrants: (assetType: AssetType, assetId: string) => Promise<void>
  upsertExistingGrant: (
    assetType: AssetType,
    assetId: string,
    request: AssetGrantRequest,
  ) => Promise<void>
  revokeExistingGrant: (assetType: AssetType, assetId: string, grantId: string) => Promise<void>
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
  grantsByAsset: {},
  isLoading: false,
  error: null,

  reset: () => set({ grantsByAsset: {}, isLoading: false, error: null }),

  loadGrants: async (assetType: AssetType, assetId: string) => {
    // Captured before the await: a response arriving after a logout (resetAllStores) skips its
    // write-back instead of resurrecting the previous user's grants into the emptied store.
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const grants = await getAssetGrants(assetType, assetId)
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({
        grantsByAsset: { ...get().grantsByAsset, [assetKey(assetType, assetId)]: grants },
        isLoading: false,
      })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Berechtigungen konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  upsertExistingGrant: async (
    assetType: AssetType,
    assetId: string,
    request: AssetGrantRequest,
  ) => {
    const sessionEpoch = currentSessionEpoch()
    const updated = await upsertAssetGrant(assetType, assetId, request)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const key = assetKey(assetType, assetId)
    const existing = get().grantsByAsset[key] ?? []
    set({ grantsByAsset: { ...get().grantsByAsset, [key]: mergeGrant(existing, updated) } })
  },

  revokeExistingGrant: async (assetType: AssetType, assetId: string, grantId: string) => {
    const sessionEpoch = currentSessionEpoch()
    await revokeAssetGrant(assetType, assetId, grantId)
    if (isStaleSessionEpoch(sessionEpoch)) return
    const key = assetKey(assetType, assetId)
    const existing = get().grantsByAsset[key] ?? []
    set({
      grantsByAsset: {
        ...get().grantsByAsset,
        [key]: existing.filter((grant) => grant.id !== grantId),
      },
    })
  },
}))
