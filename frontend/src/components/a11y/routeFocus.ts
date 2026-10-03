/**
 * Navigation state for a route change that keeps the view, e.g. a new chat receiving its id: the
 * focus stays where the person left it instead of moving to the page heading.
 */
export const KEEP_FOCUS_STATE = { keepFocus: true } as const

/** Whether a location's state asks to keep the focus, see {@link KEEP_FOCUS_STATE}. */
export function keepsFocus(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    (state as { keepFocus?: unknown }).keepFocus === true
  )
}
