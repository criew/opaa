import { useLayoutEffect, useReducer, useSyncExternalStore } from 'react'

/** The two places of a page header that content below it may fill. */
export type AreaHeaderSlot = 'count' | 'action'

const elements: Record<AreaHeaderSlot, HTMLElement | null> = { count: null, action: null }
const listeners = new Set<() => void>()

function subscribe(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * Builds the header's ref callback for one slot. A page carries one header, so the slot of the
 * header mounted last is the one in use; unmounting clears it only if it is still that one.
 */
function attach(slot: AreaHeaderSlot) {
  return (element: HTMLElement | null) => {
    if (!element) return
    elements[slot] = element
    listeners.forEach((listener) => listener())
    return () => {
      if (elements[slot] !== element) return
      elements[slot] = null
      listeners.forEach((listener) => listener())
    }
  }
}

export const attachCountSlot = attach('count')
export const attachActionSlot = attach('action')

/**
 * The mounted header's element for the slot, `null` where no header offers it, and `undefined`
 * during the first render: a header in the same tree attaches its slots only on commit, so until
 * then nobody knows whether there is one. A caller renders nothing for `undefined` - the answer
 * arrives before the first paint.
 */
export function useAreaHeaderSlot(slot: AreaHeaderSlot): HTMLElement | null | undefined {
  const element = useSyncExternalStore(
    subscribe,
    () => elements[slot],
    () => null,
  )
  // One deliberate second render per mount, flushed before the paint.
  const [committed, markCommitted] = useReducer(() => true, false)
  useLayoutEffect(markCommitted, [markCommitted])
  return committed ? element : undefined
}
