import { create } from 'zustand'
import type { DocumentTemplateAvailabilityResponse } from '../api/types'
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
  refresh: async () => {
    try {
      const { data } = await client.get<DocumentTemplateAvailabilityResponse>(
        '/v1/document-templates/availability',
      )
      set({ documentTemplates: data.enabled })
    } catch {
      set({ documentTemplates: false })
    }
  },
}))

recordRuntimeSwitches({ documentTemplates: useRuntimeSwitchStore.getState().documentTemplates })
useRuntimeSwitchStore.subscribe((state, previous) => {
  if (state.documentTemplates !== previous.documentTemplates) {
    recordRuntimeSwitches({ documentTemplates: state.documentTemplates })
  }
})

useAuthStore.subscribe((state, previous) => {
  if (state.isAuthenticated && !previous.isAuthenticated) {
    void useRuntimeSwitchStore.getState().refresh()
  }
  if (!state.isAuthenticated && previous.isAuthenticated) {
    useRuntimeSwitchStore.setState({ documentTemplates: false })
  }
})
