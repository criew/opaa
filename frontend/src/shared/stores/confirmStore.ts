import type { ReactNode } from 'react'
import { create } from 'zustand'

/**
 * How grave the action is. It decides the signal color of the dialog's edge and whether the
 * confirming button is the resting focus - nothing else.
 */
export type ConfirmTone = 'danger' | 'caution' | 'neutral'

export interface ConfirmOptions {
  /** The question, as a question - short, with the object named. */
  question: string
  /**
   * What happens on confirmation. This is the main content, not a footnote: it carries the
   * information the decision rests on.
   */
  consequence?: ReactNode
  /** The verb of the action ("Löschen", "Abschalten", "Verwerfen") - never "OK". */
  confirmLabel: string
  /** Only where "Abbrechen" would be wrong. */
  cancelLabel?: string
  tone?: ConfirmTone
}

export interface ConfirmRequest extends ConfirmOptions {
  id: number
}

interface ConfirmState {
  /** The one open request; `null` keeps the host unmounted. */
  request: ConfirmRequest | null
  /** Answers the open request and closes it. */
  settle: (confirmed: boolean) => void
  reset: () => void
}

let nextRequestId = 1
/** Resolver of the open request. Held outside the store so the state stays serialisable. */
let pending: ((confirmed: boolean) => void) | null = null

function answer(confirmed: boolean) {
  const resolve = pending
  pending = null
  resolve?.(confirmed)
}

/**
 * Confirmation of consequential actions, rendered exactly once by
 * {@link ../ui/feedback/ConfirmHost} in the AppShell - the same pattern as
 * {@link ./notificationStore}.
 *
 * There is always at most **one** open request. If a second one comes, the first is answered
 * with `false`: a `window.confirm` blocked the thread and could not overlap; without
 * this rule the `await` of the displaced request would hang forever and its action
 * would be stuck. `false` is the safe answer - a displaced question counts as declined.
 */
export const useConfirmStore = create<ConfirmState>((set) => ({
  request: null,
  settle: (confirmed) => {
    answer(confirmed)
    set({ request: null })
  },
  reset: () => {
    answer(false)
    set({ request: null })
  },
}))

/**
 * Imperative entry point, shaped like the `window.confirm` it replaces: it resolves to `true`
 * only on an explicit confirmation. Escape, the backdrop and being displaced all resolve `false`.
 */
export function confirmAction(options: ConfirmOptions): Promise<boolean> {
  answer(false)
  return new Promise<boolean>((resolve) => {
    pending = resolve
    useConfirmStore.setState({ request: { ...options, id: nextRequestId++ } })
  })
}
