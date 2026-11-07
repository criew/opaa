import { create } from 'zustand'
import { registerSessionStore } from '@/shared/session/resettableStores'

interface QuickTourState {
  open: boolean
  /** Index of the current station; only meaningful while open. */
  step: number
  start: () => void
  goTo: (step: number) => void
  close: () => void
  reset: () => void
}

/**
 * Whether the quick tour is open and at which station - in this tab only. Nothing of it reaches the
 * server or storage: a reload ends the tour, and so does the end of the session.
 */
export const useQuickTourStore = create<QuickTourState>((set) => ({
  open: false,
  step: 0,
  start: () => set({ open: true, step: 0 }),
  goTo: (step) => set({ step }),
  close: () => set({ open: false, step: 0 }),
  reset: () => set({ open: false, step: 0 }),
}))

registerSessionStore(useQuickTourStore)
