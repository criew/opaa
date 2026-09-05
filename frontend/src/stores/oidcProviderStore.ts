import { create } from 'zustand'
import type { OidcProviderRequest, OidcProviderResponse } from '../types/api'
import {
  createOidcProvider,
  deleteOidcProvider,
  getOidcProviders,
  makeOidcProviderDefault,
  reorderOidcProviders,
  setOidcProviderEnabled,
  updateOidcProvider,
} from '../services/api'
import { currentSessionEpoch, isStaleSessionEpoch } from './sessionEpoch'

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
  deleteExistingProvider: (providerId: string) => Promise<void>
  setProviderEnabled: (providerId: string, enabled: boolean) => Promise<OidcProviderResponse>
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
 * Identity providers (ADR-0025, admin API from #1329) - list, create, edit, enable/disable,
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

  deleteExistingProvider: async (providerId) => {
    await deleteOidcProvider(providerId)
    set({ providers: get().providers.filter((p) => p.id !== providerId) })
  },

  setProviderEnabled: async (providerId, enabled) => {
    const updated = await setOidcProviderEnabled(providerId, enabled)
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

  moveProvider: async (providerId, direction) => {
    const ordered = sortProviders(get().providers).map((p) => p.id)
    const index = ordered.indexOf(providerId)
    const target = direction === 'up' ? index - 1 : index + 1
    if (index < 0 || target < 0 || target >= ordered.length) return
    const next = [...ordered]
    next[index] = ordered[target]
    next[target] = ordered[index]
    const providers = await reorderOidcProviders(next)
    set({ providers: sortProviders(providers) })
  },
}))
