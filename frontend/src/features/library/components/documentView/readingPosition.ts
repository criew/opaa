import { useCallback, useEffect, useEffectEvent, useRef, type RefObject } from 'react'

/**
 * Where a reader is in a scrolling document view: by default the line a quarter down the view; a
 * view that opens its passage further down passes its own `fraction`. Over the last screen of
 * scrolling (all of it, if shorter) the line moves down to the bottom edge, so the last page and
 * sections that never scroll up that far are reached too. Page and outline entry shown as current
 * are both taken at this line, so they always agree.
 */
export function readingLine(
  view: { scrollTop: number; clientHeight: number; scrollHeight: number },
  fraction = 1 / 4,
): number {
  const { scrollTop, clientHeight, scrollHeight } = view
  const travel = Math.max(0, scrollHeight - clientHeight)
  const stretch = Math.min(clientHeight, travel)
  const left = Math.max(0, travel - scrollTop)
  const nearEnd = stretch > 0 ? Math.max(0, 1 - left / stretch) : 0
  return scrollTop + clientHeight * (fraction + (1 - fraction) * nearEnd)
}

/**
 * The entry with the greatest position at or above `line` - the section being read - and on a tie
 * the later one in reading order; `null` while every entry lies below the line.
 */
export function lastAtOrBefore<T extends { position: number }>(
  entries: readonly T[],
  line: number,
): T | null {
  let found: T | null = null
  for (const entry of entries) {
    if (entry.position <= line && (found === null || entry.position >= found.position)) {
      found = entry
    }
  }
  return found
}

/** Space kept between a navigation target and the edge of the view. */
const TARGET_MARGIN = 48

/**
 * The horizontal scroll offset that shows as much of a page as fits: centred when the page is no
 * wider than the view, else aligned to its left edge. Only when `target` (both in the same content
 * coordinates as `pageLeft`) would lie outside does it scroll further, keeping a margin around the
 * target instead of pinning it to the right edge. Never negative.
 */
export function horizontalOffset({
  pageLeft,
  pageWidth,
  viewWidth,
  target,
}: {
  pageLeft: number
  pageWidth: number
  viewWidth: number
  target?: { left: number; right: number } | null
}): number {
  let offset = pageWidth <= viewWidth ? pageLeft - (viewWidth - pageWidth) / 2 : pageLeft
  if (target && (target.right > offset + viewWidth || target.left < offset)) {
    const pageEnd = pageLeft + pageWidth - viewWidth
    offset = Math.max(pageLeft, Math.min(target.left - TARGET_MARGIN, pageEnd))
  }
  return Math.max(0, offset)
}

/**
 * Calls `onUserScroll` when the view behind `ref` scrolls to a place the view did not put itself -
 * by wheel, touch, keyboard, scrollbar, the browser's search or a script - but not on an input
 * that scrolls nothing (a click into the text). The returned function tells it that the view has
 * just scrolled itself: its position at that moment does not count as the reader's. `attachKey`
 * re-attaches the listener once the scrolling element is mounted anew. The browser moving the
 * view itself (scroll anchoring, a smaller window) counts as the reader's scroll too.
 */
export function useUserScroll(
  ref: RefObject<HTMLElement | null>,
  onUserScroll: () => void,
  attachKey?: unknown,
): () => void {
  const settled = useRef<{ top: number; left: number } | null>(null)
  const report = useEffectEvent(onUserScroll)
  useEffect(() => {
    const element = ref.current
    if (!element) return
    const scroll = () => {
      const at = settled.current
      const unmoved =
        at !== null &&
        Math.abs(element.scrollTop - at.top) <= 1 &&
        Math.abs(element.scrollLeft - at.left) <= 1
      if (unmoved) return
      settled.current = null
      report()
    }
    element.addEventListener('scroll', scroll, { passive: true })
    return () => element.removeEventListener('scroll', scroll)
  }, [ref, attachKey])
  return useCallback(() => {
    const element = ref.current
    settled.current = element ? { top: element.scrollTop, left: element.scrollLeft } : null
  }, [ref])
}
