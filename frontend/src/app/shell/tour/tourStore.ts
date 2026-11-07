import { create } from 'zustand'
import { currentUiLabVariant } from '@/shared/uiLabVariant'

/**
 * State of the first-login tour in the UI lab (#2312). "Seen" lives in this tab's sessionStorage
 * per variant, so switching the variant in the prototype bar shows the first visit again; the
 * product keeps it per person on the server (open question in the lab report).
 */
export type ChecklistItem = 'ask' | 'evidence' | 'spaces' | 'catalog'

interface TourState {
  /** The coachmark tour or the welcome dialog is open. */
  open: boolean
  /** The first visit of this variant is over: finished, skipped or declined. */
  seen: boolean
  step: number
  checked: ChecklistItem[]
  checklistHidden: boolean
  start: () => void
  finish: () => void
  goTo: (step: number) => void
  check: (item: ChecklistItem) => void
  hideChecklist: () => void
  showChecklist: () => void
}

const seenKey = () => `opaa.lab.tour.${currentUiLabVariant() ?? 'none'}`

function readSeen(): { seen: boolean; checked: ChecklistItem[]; hidden: boolean } {
  try {
    const raw = window.sessionStorage.getItem(seenKey())
    if (!raw) return { seen: false, checked: [], hidden: false }
    const parsed = JSON.parse(raw) as { checked?: ChecklistItem[]; hidden?: boolean }
    return { seen: true, checked: parsed.checked ?? [], hidden: parsed.hidden ?? false }
  } catch {
    return { seen: false, checked: [], hidden: false }
  }
}

function writeSeen(checked: ChecklistItem[], hidden: boolean): void {
  try {
    window.sessionStorage.setItem(seenKey(), JSON.stringify({ checked, hidden }))
  } catch {
    // Without storage the tour simply shows again on the next load.
  }
}

const initial = readSeen()

export const useTourStore = create<TourState>((set, get) => ({
  open: false,
  seen: initial.seen,
  step: 0,
  checked: initial.checked,
  checklistHidden: initial.hidden,
  start: () => set({ open: true, step: 0 }),
  finish: () => {
    writeSeen(get().checked, get().checklistHidden)
    set({ open: false, seen: true, step: 0 })
  },
  goTo: (step) => set({ step }),
  check: (item) => {
    const checked = get().checked.includes(item) ? get().checked : [...get().checked, item]
    writeSeen(checked, get().checklistHidden)
    set({ checked })
  },
  hideChecklist: () => {
    writeSeen(get().checked, true)
    set({ checklistHidden: true })
  },
  showChecklist: () => {
    writeSeen(get().checked, false)
    set({ checklistHidden: false })
  },
}))
