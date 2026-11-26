import { BRAND, type DocViewVariant } from './docViewVariant'

const MARK_CLASS = 'lab-passage-mark'

/**
 * Draws the cited passage over a rendered page as clean line bands (A, B) or one frame with a
 * label (C), measured from the marked text-layer spans and placed in fractions of the page, so
 * they follow every zoom step. Earlier marks of the page are replaced. Returns the first mark.
 */
export function drawPassageMarks(
  pageDiv: HTMLElement,
  spans: HTMLElement[],
  variant: Exclude<DocViewVariant, null>,
): HTMLElement | null {
  pageDiv.querySelectorAll(`.${MARK_CLASS}`).forEach((node) => node.remove())
  if (spans.length === 0) return null
  const page = pageDiv.getBoundingClientRect()
  if (page.width === 0 || page.height === 0) return null
  const lines = lineRects(spans.map((span) => span.getBoundingClientRect()))
  const doc = pageDiv.ownerDocument
  const place = (el: HTMLElement, l: number, t: number, w: number, h: number) => {
    el.className = MARK_CLASS
    el.setAttribute('aria-hidden', 'true')
    Object.assign(el.style, {
      position: 'absolute',
      pointerEvents: 'none',
      zIndex: '1',
      left: `${((l - page.left) / page.width) * 100}%`,
      top: `${((t - page.top) / page.height) * 100}%`,
      width: `${(w / page.width) * 100}%`,
      height: `${(h / page.height) * 100}%`,
    })
    pageDiv.appendChild(el)
    return el
  }
  const pad = Math.max(2, page.width * 0.004)
  let first: HTMLElement | null = null
  if (variant === 'c') {
    const left = Math.min(...lines.map((r) => r.left)) - pad * 3
    const top = Math.min(...lines.map((r) => r.top)) - pad * 2
    const right = Math.max(...lines.map((r) => r.right)) + pad * 3
    const bottom = Math.max(...lines.map((r) => r.bottom)) + pad * 2
    const frame = place(doc.createElement('div'), left, top, right - left, bottom - top)
    Object.assign(frame.style, {
      border: `2px solid ${BRAND.blue}`,
      borderRadius: '6px',
      background: BRAND.markerSoft,
      mixBlendMode: 'multiply',
      boxSizing: 'border-box',
    })
    const label = doc.createElement('span')
    label.textContent = 'Fundstelle'
    Object.assign(label.style, {
      position: 'absolute',
      ...(left - page.left > 84
        ? { right: '100%', top: '-2px', marginRight: '6px' }
        : { left: '-2px', bottom: '100%', marginBottom: '2px' }),
      background: BRAND.blue,
      color: '#fff',
      font: '600 11px/16px Quicksand, Inter, sans-serif',
      padding: '1px 8px',
      borderRadius: '4px',
      mixBlendMode: 'normal',
      whiteSpace: 'nowrap',
    })
    // the label must not be multiplied with the page: it sits in a sibling layer
    const tag = place(doc.createElement('div'), left, top, right - left, bottom - top)
    tag.appendChild(label)
    return frame
  }
  for (const line of lines) {
    const h = line.bottom - line.top
    const band = place(
      doc.createElement('div'),
      line.left - pad,
      line.top - pad / 2,
      line.right - line.left + pad * 2,
      h + pad,
    )
    Object.assign(band.style, {
      background: variant === 'b' ? BRAND.markerSoft : BRAND.marker,
      mixBlendMode: 'multiply',
      borderRadius: variant === 'b' ? '2px 2px 0 0' : '3px',
      borderBottom: variant === 'b' ? `2px solid ${BRAND.blue}` : 'none',
      boxSizing: 'border-box',
    })
    first ??= band
  }
  if (variant === 'a') {
    const top = Math.min(...lines.map((r) => r.top))
    const bottom = Math.max(...lines.map((r) => r.bottom))
    const left = Math.min(...lines.map((r) => r.left))
    const bar = place(
      doc.createElement('div'),
      left - pad * 5,
      top - pad / 2,
      4,
      bottom - top + pad,
    )
    Object.assign(bar.style, { background: BRAND.blue, borderRadius: '2px' })
  }
  return first
}

interface Line {
  left: number
  right: number
  top: number
  bottom: number
}

/** Merges span rectangles into one rectangle per text line. */
function lineRects(rects: DOMRect[]): Line[] {
  const lines: Line[] = []
  for (const r of rects) {
    if (r.width === 0 || r.height === 0) continue
    const mid = (r.top + r.bottom) / 2
    const line = lines.find((l) => mid > l.top && mid < l.bottom)
    if (line) {
      line.left = Math.min(line.left, r.left)
      line.right = Math.max(line.right, r.right)
      line.top = Math.min(line.top, r.top)
      line.bottom = Math.max(line.bottom, r.bottom)
    } else {
      lines.push({ left: r.left, right: r.right, top: r.top, bottom: r.bottom })
    }
  }
  return lines
}
