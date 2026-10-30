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

/**
 * Marks the element a page wants focused on arrival instead of its heading, e.g. the question
 * field of a new chat. Both AppShell and PageHeading honour it; at most one per page.
 */
export const ARRIVAL_FOCUS_ATTRIBUTE = 'data-arrival-focus'

/** The arrival focus target inside `container`, see {@link ARRIVAL_FOCUS_ATTRIBUTE}. */
export function arrivalFocusTarget(container: ParentNode): HTMLElement | null {
  return container.querySelector<HTMLElement>(`[${ARRIVAL_FOCUS_ATTRIBUTE}]`)
}
