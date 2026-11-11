export interface Rect {
  top: number
  left: number
  width: number
  height: number
}

/**
 * The cutout around an element: padded on every side and kept inside the viewport, so the ring
 * around it is never cut off. Null for an element without a layout box.
 */
export function toCutout(box: DOMRect, pad: number): Rect | null {
  if (box.width <= 0 || box.height <= 0) return null
  const edge = 4
  const left = Math.max(edge, box.left - pad)
  const top = Math.max(edge, box.top - pad)
  const right = Math.min(window.innerWidth - edge, box.right + pad)
  const bottom = Math.min(window.innerHeight - edge, box.bottom + pad)
  return { left, top, width: Math.max(0, right - left), height: Math.max(0, bottom - top) }
}

export function sameRect(a: Rect | null, b: Rect | null): boolean {
  if (a === null || b === null) return a === b
  return a.left === b.left && a.top === b.top && a.width === b.width && a.height === b.height
}

interface Spacing {
  /** Distance to the viewport edges and to the cutout. */
  gutter: number
  /** The cutout's padding around the element; the ring sits outside it. */
  pad: number
}

/**
 * Places the card beside a cutout in the left third (the rail, the space column), else below it,
 * else above it. With a ceiling - the top of the element the next station shows - the card goes
 * above that ceiling, so it never covers the next station. Without a cutout it is centred.
 */
export function placeCard(
  cutout: Rect | null,
  card: { width: number; height: number },
  ceiling: number | null,
  { gutter, pad }: Spacing,
): { left: number; top: number } {
  const vw = window.innerWidth
  const vh = window.innerHeight
  const clampX = (x: number) => Math.min(Math.max(gutter, x), vw - card.width - gutter)
  const clampY = (y: number) => Math.min(Math.max(gutter, y), vh - card.height - gutter)
  if (!cutout) return { left: clampX((vw - card.width) / 2), top: clampY((vh - card.height) / 2) }
  // The ring (2 px plus 2 px offset) lies outside the cutout.
  const ring = pad
  if (ceiling !== null) {
    return {
      left: clampX(cutout.left),
      top: clampY(Math.min(ceiling - pad, cutout.top) - ring - gutter - card.height),
    }
  }
  const right = cutout.left + cutout.width + ring + gutter
  if (cutout.left < vw / 3 && right + card.width <= vw - gutter) {
    return { left: right, top: clampY(cutout.top) }
  }
  const below = cutout.top + cutout.height + ring + gutter
  if (below + card.height <= vh - gutter) return { left: clampX(cutout.left), top: below }
  return { left: clampX(cutout.left), top: clampY(cutout.top - ring - gutter - card.height) }
}
