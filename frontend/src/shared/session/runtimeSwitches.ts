import { create } from 'zustand'
import type { AgentAvailabilityResponse, DocumentTemplateAvailabilityResponse } from '../api/types'
import { apiClient as client } from '../api/api'
import { useAuthStore } from './authStore'
import { recordRuntimeSwitches, type RuntimeSwitches } from './installationFeatures'

interface RuntimeSwitchState extends RuntimeSwitches {
  /** Reads the switches again, e.g. after the administration changed one. */
  refresh: () => Promise<void>
}

/**
 * The installation switches the administration changes while the installation runs (unlike the
 * feature flags of ADR-0044, fixed at start-up). Read after every sign-in and on demand; a switch
 * that could not be read counts as off, so nothing behind it is offered by mistake.
 */
export const useRuntimeSwitchStore = create<RuntimeSwitchState>((set) => ({
  documentTemplates: false,
  agents: false,
  refresh: async () => {
    const [documentTemplates, agents] = await Promise.all([
      readSwitch<DocumentTemplateAvailabilityResponse>('/v1/document-templates/availability'),
      readSwitch<AgentAvailabilityResponse>('/v1/agents/availability'),
    ])
    set({ documentTemplates, agents })
  },
}))

async function readSwitch<T extends { enabled: boolean }>(path: string): Promise<boolean> {
  try {
    const { data } = await client.get<T>(path)
    return data.enabled
  } catch {
    return false
  }
}

recordRuntimeSwitches({
  documentTemplates: useRuntimeSwitchStore.getState().documentTemplates,
  agents: useRuntimeSwitchStore.getState().agents,
})
useRuntimeSwitchStore.subscribe((state, previous) => {
  if (state.documentTemplates !== previous.documentTemplates || state.agents !== previous.agents) {
    recordRuntimeSwitches({ documentTemplates: state.documentTemplates, agents: state.agents })
  }
})

useAuthStore.subscribe((state, previous) => {
  if (state.isAuthenticated && !previous.isAuthenticated) {
    void useRuntimeSwitchStore.getState().refresh()
  }
  if (!state.isAuthenticated && previous.isAuthenticated) {
    useRuntimeSwitchStore.setState({ documentTemplates: false, agents: false })
  }
})
