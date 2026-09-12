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
  /**
   * `acknowledgeLastProvider` travels to the backend only for the last enabled OIDC provider -
   * see {@link deleteOidcProvider} (ADR-0033, Entscheidung 4).
   */
  deleteExistingProvider: (providerId: string, acknowledgeLastProvider?: boolean) => Promise<void>
  setProviderEnabled: (
    providerId: string,
    enabled: boolean,
    acknowledgeLastProvider?: boolean,
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

  deleteExistingProvider: async (providerId, acknowledgeLastProvider = false) => {
    await deleteOidcProvider(providerId, acknowledgeLastProvider)
    set({ providers: get().providers.filter((p) => p.id !== providerId) })
  },

  setProviderEnabled: async (providerId, enabled, acknowledgeLastProvider = false) => {
    const updated = await setOidcProviderEnabled(providerId, enabled, acknowledgeLastProvider)
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
   * Getauscht wird mit dem nächsten **OIDC**-Nachbarn, nicht mit dem nächsten Eintrag der Liste
   * (Review-Runde 1, MEDIUM 5): Die `LOCAL`-Zeile steht mit in `oidc_providers` und kann zwischen
   * zwei Anbietern liegen. Ein Tausch mit ihr hätte die sichtbare Reihenfolge der Anbieterseite
   * nicht verändert - ein verschluckter Klick plus ein Reihenfolge-Audit ohne Wirkung. Ihre eigene
   * Position bleibt unberührt, und die gesendete Liste enthält weiter alle Zeilen, weil die API
   * die vollständige Reihenfolge erwartet.
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
