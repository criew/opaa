import { KEEP_FOCUS_STATE } from '@/shared/ui/a11y/routeFocus'

/**
 * Navigation state that opens the spaces overview as the space search (guidelines 5.13): the
 * cursor lands in its search field, like the chat search's handover, instead of on the heading.
 */
export const SPACE_SEARCH_HANDOVER = { ...KEEP_FOCUS_STATE, spaceSearch: true } as const

/** Whether a location's state asks the overview to focus its search field. */
export function handsOverSpaceSearch(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    (state as { spaceSearch?: unknown }).spaceSearch === true
  )
}
