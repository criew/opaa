import type { OidcProviderResponse } from '@/shared/api/types'

/** The three states the provider list and its legend speak of (ADR-0025). */
export type ProviderState = 'reachable' | 'unreachable' | 'disabled'

export function providerState(provider: OidcProviderResponse): ProviderState {
  if (!provider.enabled) return 'disabled'
  return provider.registryState === 'READY' ? 'reachable' : 'unreachable'
}

export const PROVIDER_STATE_LABEL: Record<ProviderState, string> = {
  reachable: 'Erreichbar',
  unreachable: 'Nicht erreichbar',
  disabled: 'Deaktiviert',
}

/**
 * Whether the token decoder could be built is decided by each backend instance itself, so a
 * reachable or unreachable state describes the instance that answered this page (ADR-0053,
 * Entscheidung 12); a disabled provider is disabled everywhere.
 */
export function isPerInstance(state: ProviderState): boolean {
  return state !== 'disabled'
}

export const PER_INSTANCE_NOTE = 'auf dieser Instanz'
