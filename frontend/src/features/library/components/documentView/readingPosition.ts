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

/** How long after an input of the reader a scroll still counts as theirs. */
const INPUT_WINDOW_MS = 1000

/**
 * Calls `onUserScroll` when the view behind `ref` scrolls shortly after the reader's own input -
 * wheel, touch, keyboard, or a press on the view or its scrollbar - but not when only the code
 * scrolls it, nor on an input that scrolls nothing (a click into the text). The returned function
 * forgets a pending input; a view calls it before it scrolls itself. `attachKey` re-attaches the
 * listeners once the scrolling element is mounted.
 */
export function useUserScroll(
  ref: RefObject<HTMLElement | null>,
  onUserScroll: () => void,
  attachKey?: unknown,
): () => void {
  const lastInput = useRef(-Infinity)
  const report = useEffectEvent(onUserScroll)
  useEffect(() => {
    const element = ref.current
    if (!element) return
    const input = () => {
      lastInput.current = performance.now()
    }
    const scroll = () => {
      if (performance.now() - lastInput.current <= INPUT_WINDOW_MS) report()
    }
    const inputs = ['wheel', 'touchmove', 'keydown', 'pointerdown'] as const
    for (const name of inputs) element.addEventListener(name, input, { passive: true })
    element.addEventListener('scroll', scroll, { passive: true })
    return () => {
      for (const name of inputs) element.removeEventListener(name, input)
      element.removeEventListener('scroll', scroll)
    }
  }, [ref, attachKey])
  return useCallback(() => {
    lastInput.current = -Infinity
  }, [])
}
