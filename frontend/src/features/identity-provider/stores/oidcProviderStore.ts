import { create } from 'zustand'
import type { OidcProviderRequest, OidcProviderResponse } from '@/shared/api/types'
import {
  createOidcProvider,
  deleteOidcProvider,
  getOidcProviders,
  makeOidcProviderDefault,
  reorderOidcProviders,
  setOidcProviderEnabled,
  updateOidcProvider,
  type ProviderShutdownConfirmations,
} from '../api/identityProviderApi'
import { currentSessionEpoch, isStaleSessionEpoch } from '@/shared/session/sessionEpoch'
import { registerSessionStore } from '@/shared/session/resettableStores'

interface OidcProviderState {
  providers: OidcProviderResponse[]
  isLoading: boolean
  error: string | null
  reset: () => void
  loadProviders: () => Promise<void>
  createNewProvider: (request: OidcProviderRequest) => Promise<OidcProviderResponse>
  updateExistingProvider: (
    providerId: string,
    request: OidcProviderRequest,
  ) => Promise<OidcProviderResponse>
  /** The confirmations travel only where set - see {@link deleteOidcProvider}. */
  deleteExistingProvider: (
    providerId: string,
    confirmations?: ProviderShutdownConfirmations,
  ) => Promise<void>
  setProviderEnabled: (
    providerId: string,
    enabled: boolean,
    confirmations?: ProviderShutdownConfirmations,
  ) => Promise<OidcProviderResponse>
  makeProviderDefault: (providerId: string) => Promise<OidcProviderResponse>
  /** Moves the provider one position up or down in the sign-in page order. */
  moveProvider: (providerId: string, direction: 'up' | 'down') => Promise<void>
}

function sortProviders(list: OidcProviderResponse[]): OidcProviderResponse[] {
  return [...list].sort(
    (a, b) => a.sortOrder - b.sortOrder || a.displayName.localeCompare(b.displayName),
  )
}

/**
 * Identity providers (ADR-0025, admin API) - list, create, edit, enable/disable,
 * default, order, delete. Like {@link useLlmModelStore}, every mutation patches `providers`
 * from the server's own response instead of reloading, so an open card survives its own action.
 */
export const useOidcProviderStore = create<OidcProviderState>((set, get) => ({
  providers: [],
  isLoading: false,
  error: null,

  reset: () => set({ providers: [], isLoading: false, error: null }),

  loadProviders: async () => {
    const sessionEpoch = currentSessionEpoch()
    set({ isLoading: true, error: null })
    try {
      const providers = sortProviders(await getOidcProviders())
      if (isStaleSessionEpoch(sessionEpoch)) return
      set({ providers, isLoading: false })
    } catch (err) {
      if (isStaleSessionEpoch(sessionEpoch)) return
      const message =
        err instanceof Error ? err.message : 'Identitätsanbieter konnten nicht geladen werden'
      set({ error: message, isLoading: false })
    }
  },

  createNewProvider: async (request) => {
    const created = await createOidcProvider(request)
    set({ providers: sortProviders([...get().providers, created]) })
    return created
  },

  updateExistingProvider: async (providerId, request) => {
    const updated = await updateOidcProvider(providerId, request)
    set({
      providers: sortProviders(get().providers.map((p) => (p.id === providerId ? updated : p))),
    })
    return updated
  },

  deleteExistingProvider: async (providerId, confirmations = {}) => {
    await deleteOidcProvider(providerId, confirmations)
    set({ providers: get().providers.filter((p) => p.id !== providerId) })
  },

  setProviderEnabled: async (providerId, enabled, confirmations = {}) => {
    const updated = await setOidcProviderEnabled(providerId, enabled, confirmations)
    set({ providers: get().providers.map((p) => (p.id === providerId ? updated : p)) })
    return updated
  },

  makeProviderDefault: async (providerId) => {
    // the response carries the new default only; the previous default lost the flag in the same
    // backend transaction, so the local list mirrors that in one step
    const updated = await makeOidcProviderDefault(providerId)
    set({
      providers: get().providers.map((p) =>
        p.id === providerId ? updated : { ...p, isDefault: false },
      ),
    })
    return updated
  },

  /*
   * The swap is with the next **OIDC** neighbour, not with the next entry of the list:
   * the `LOCAL` row is part of `oidc_providers` as well and can lie between
   * two providers. A swap with it would not have changed the visible order of the provider page
   * - a swallowed click plus an order audit without effect. Its own
   * position stays untouched, and the list sent still contains all rows, because the API
   * expects the complete order.
   */
  moveProvider: async (providerId, direction) => {
    const ordered = sortProviders(get().providers)
    const index = ordered.findIndex((p) => p.id === providerId)
    if (index < 0 || ordered[index].providerType !== 'OIDC') return
    const step = direction === 'up' ? -1 : 1
    let target = index + step
    while (target >= 0 && target < ordered.length && ordered[target].providerType !== 'OIDC') {
      target += step
    }
    if (target < 0 || target >= ordered.length) return
    const next = ordered.map((p) => p.id)
    next[index] = ordered[target].id
    next[target] = ordered[index].id
    const providers = await reorderOidcProviders(next)
    set({ providers: sortProviders(providers) })
  },
}))

registerSessionStore(useOidcProviderStore)
