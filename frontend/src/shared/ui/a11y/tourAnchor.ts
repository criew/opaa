/**
 * Marks the elements the quick tour points at. The tour lives in the application shell, the
 * elements in the shell and in the chat theme; this attribute is their only contract, so labels
 * and styling stay free to change without breaking the tour.
 */
export const TOUR_ANCHOR_ATTRIBUTE = 'data-tour-anchor'

/** The elements a station of the quick tour can explain. */
export type TourAnchorName =
  | 'chat-input'
  | 'ai-notice'
  | 'space-knowledge'
  | 'filter'
  | 'prompts'
  | 'attach'
  | 'dictate'
  | 'new-chat'
  | 'chats'
  | 'spaces'
  | 'catalog'
  | 'handbook'
  | 'notifications'
  | 'admin'
  | 'profile'
  | 'menu'

/** Spread onto the element a station explains: `<Box {...tourAnchor('spaces')}>`. */
export function tourAnchor(name: TourAnchorName): Record<typeof TOUR_ANCHOR_ATTRIBUTE, string> {
  return { [TOUR_ANCHOR_ATTRIBUTE]: name }
}

/**
 * The rendered element for `name`; one with a layout box wins over a hidden copy, e.g. the rail
 * kept mounted in the closed drawer.
 */
export function findTourAnchor(name: TourAnchorName): HTMLElement | null {
  const candidates = Array.from(
    document.querySelectorAll<HTMLElement>(`[${TOUR_ANCHOR_ATTRIBUTE}="${name}"]`),
  )
  return candidates.find((element) => element.getClientRects().length > 0) ?? candidates[0] ?? null
}

/** Puts the caret into the chat's question field, where every way out of the tour ends. */
export function focusChatInput(): void {
  findTourAnchor('chat-input')
    ?.querySelector<HTMLTextAreaElement>('textarea:not([aria-hidden="true"])')
    ?.focus()
}
