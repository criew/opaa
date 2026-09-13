import type { OidcProviderResponse } from '../../types/api'

/** The three states the provider list and its legend speak of (ADR-0025, #1333). */
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
