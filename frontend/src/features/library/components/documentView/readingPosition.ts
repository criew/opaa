/**
 * Where a reader is in a scrolling document view: by default the line a quarter down the viewport;
 * a view that opens its passage further down passes its own `fraction`. The page and the outline
 * entry shown as current are both taken at this line, so they always agree.
 */
export function readingLine(scrollTop: number, clientHeight: number, fraction = 1 / 4): number {
  return scrollTop + clientHeight * fraction
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
 * Event props for a scrolling view that call `onRelease` whenever the reader scrolls it themselves
 * - wheel, touch, keyboard or a press on it or its scrollbar - but not when the code scrolls it.
 * A view keeps the outline entry just chosen as the current one until then.
 */
export function userScrollProps(onRelease: () => void) {
  return {
    onWheel: onRelease,
    onTouchMove: onRelease,
    onKeyDown: onRelease,
    onPointerDown: onRelease,
  }
}
