import { useSyncExternalStore } from 'react'

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

/** The mounted header's element for the slot, or `null` while no header offers it. */
export function useAreaHeaderSlot(slot: AreaHeaderSlot): HTMLElement | null {
  return useSyncExternalStore(
    subscribe,
    () => elements[slot],
    () => null,
  )
}
