import { create } from 'zustand'
import { registerSessionStore } from '@/shared/session/resettableStores'

interface QuickTourState {
  open: boolean
  /** Index of the current station; only meaningful while open. */
  step: number
  /** The person has moved on from the first station since opening; changes are announced. */
  paged: boolean
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
  paged: false,
  start: () => set({ open: true, step: 0, paged: false }),
  goTo: (step) => set({ step, paged: true }),
  close: () => set({ open: false, step: 0, paged: false }),
  reset: () => set({ open: false, step: 0, paged: false }),
}))

registerSessionStore(useQuickTourStore)
