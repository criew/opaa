import { create } from 'zustand'
import { apiClient } from '../api/api'
import type { AgentAvailabilityResponse } from '../api/types'
import { recordAgentsEnabled } from './installationFeatures'
import { registerSessionStore } from './resettableStores'

interface AgentAvailabilityState {
  /** The installation's agents switch; `null` until the first answer of this session. */
  enabled: boolean | null
  reset: () => void
  /** Asks the backend; a failure counts as switched off. */
  load: () => Promise<void>
  /** Takes over a value the administration just saved, without asking again. */
  adopt: (enabled: boolean) => void
}

/**
 * The agents switch (runtime, in the database, not a feature flag): off, the type, its right and
 * its pages are offered nowhere. Unlike a feature flag, absent means off.
 */
export const useAgentAvailability = create<AgentAvailabilityState>((set) => ({
  enabled: null,
  reset: () => set({ enabled: null }),
  load: async () => {
    try {
      const { data } = await apiClient.get<AgentAvailabilityResponse>('/v1/agents/availability')
      set({ enabled: data.enabled })
    } catch {
      set({ enabled: false })
    }
  },
  adopt: (enabled) => set({ enabled }),
}))

registerSessionStore(useAgentAvailability)

recordAgentsEnabled(useAgentAvailability.getState().enabled === true)
useAgentAvailability.subscribe((state) => recordAgentsEnabled(state.enabled === true))
