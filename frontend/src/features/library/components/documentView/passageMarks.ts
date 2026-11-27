/** A band behind one line of the passage, multiplied onto the page. */
export const PASSAGE_BAND_CLASS = 'opaa-passage-band'
/** The edge stroke left of the passage. */
export const PASSAGE_EDGE_CLASS = 'opaa-passage-edge'
/** The label „Fundstelle“ above the passage. */
export const PASSAGE_LABEL_CLASS = 'opaa-passage-label'

/** Room left of the stroke from which the label stands in the margin instead of above the text. */
const MARGIN_ROOM_PX = 84
/** Height the label takes above the passage. */
const LABEL_HEIGHT_PX = 22

const MARK_SELECTOR = `.${PASSAGE_BAND_CLASS}, .${PASSAGE_EDGE_CLASS}, .${PASSAGE_LABEL_CLASS}`

interface Line {
  left: number
  right: number
  top: number
  bottom: number
}

/**
 * Draws the cited passage over a rendered PDF page: one band per text line, one edge stroke left
 * of them and, where the passage starts, the label „Fundstelle“ beside the stroke in the margin,
 * without room there above it, and not at all where it would cover text. Measured from
 * the marked text-layer spans and placed in fractions of the page, so the marks follow every zoom
 * step; earlier marks of the page are replaced. Colours come from the classes. Returns the first
 * band, or `null` when the spans have no size (yet).
 */
export function drawPassageMarks(
  pageDiv: HTMLElement,
  spans: HTMLElement[],
  { label }: { label: boolean },
): HTMLElement | null {
  pageDiv.querySelectorAll(MARK_SELECTOR).forEach((node) => node.remove())
  const page = pageDiv.getBoundingClientRect()
  if (spans.length === 0 || page.width === 0 || page.height === 0) return null
  const lines = lineRects(spans.map((span) => span.getBoundingClientRect()))
  if (lines.length === 0) return null
  const pad = Math.max(2, page.width * 0.004)
  const place = (className: string, left: number, top: number, width: number, height: number) => {
    const element = pageDiv.ownerDocument.createElement('div')
    element.className = className
    element.setAttribute('aria-hidden', 'true')
    Object.assign(element.style, {
      left: `${((left - page.left) / page.width) * 100}%`,
      top: `${((top - page.top) / page.height) * 100}%`,
      width: `${(width / page.width) * 100}%`,
      height: `${(height / page.height) * 100}%`,
    })
    pageDiv.appendChild(element)
    return element
  }
  let first: HTMLElement | null = null
  for (const line of lines) {
    const band = place(
      PASSAGE_BAND_CLASS,
      line.left - pad,
      line.top - pad / 2,
      line.right - line.left + pad * 2,
      line.bottom - line.top + pad,
    )
    first ??= band
  }
  const top = Math.min(...lines.map((line) => line.top)) - pad / 2
  const bottom = Math.max(...lines.map((line) => line.bottom)) + pad / 2
  const left = Math.min(...lines.map((line) => line.left)) - pad * 4
  const edge = place(PASSAGE_EDGE_CLASS, left, top, 0, bottom - top)
  edge.style.width = '4px'
  if (label) {
    if (left - page.left >= MARGIN_ROOM_PX) appendPassageLabel(edge, true)
    else if (!textAbove(pageDiv, spans, left, top)) appendPassageLabel(edge)
  }
  return first
}

/**
 * Puts the label „Fundstelle“ at a scan's frame: in the margin left of it where there is room,
 * otherwise above it. It stands outside the frame: the frame is multiplied onto the scan, the label
 * keeps its own colours.
 */
export function labelFrame(frame: HTMLElement) {
  const pageDiv = frame.parentElement
  if (!pageDiv || pageDiv.querySelector(`.${PASSAGE_EDGE_CLASS}`)) return
  const holder = pageDiv.ownerDocument.createElement('div')
  holder.className = PASSAGE_EDGE_CLASS
  holder.setAttribute('aria-hidden', 'true')
  Object.assign(holder.style, {
    left: frame.style.left,
    top: frame.style.top,
    width: '0px',
    height: '0px',
  })
  pageDiv.appendChild(holder)
  const room = frame.getBoundingClientRect().left - pageDiv.getBoundingClientRect().left
  appendPassageLabel(holder, room >= MARGIN_ROOM_PX)
}

/**
 * Puts the label „Fundstelle“ at `mark` (positioned), once: in the margin left of it when
 * `inMargin` (attribute `data-place="margin"`), otherwise above it.
 */
export function appendPassageLabel(mark: HTMLElement, inMargin = false) {
  if (mark.querySelector(`.${PASSAGE_LABEL_CLASS}`)) return
  const label = mark.ownerDocument.createElement('span')
  label.className = PASSAGE_LABEL_CLASS
  label.setAttribute('aria-hidden', 'true')
  label.textContent = 'Fundstelle'
  if (inMargin) label.dataset.place = 'margin'
  mark.appendChild(label)
}

/** Whether other text of the page lies where the label would stand above the passage. */
function textAbove(pageDiv: HTMLElement, marked: HTMLElement[], left: number, top: number) {
  const room = { left, right: left + MARGIN_ROOM_PX, top: top - LABEL_HEIGHT_PX, bottom: top }
  return Array.from(pageDiv.querySelectorAll<HTMLElement>('.textLayer span')).some((span) => {
    if (marked.includes(span)) return false
    const rect = span.getBoundingClientRect()
    return (
      rect.width > 0 &&
      rect.left < room.right &&
      rect.right > room.left &&
      rect.top < room.bottom &&
      rect.bottom > room.top
    )
  })
}

/** Merges span rectangles into one rectangle per text line. */
function lineRects(rects: DOMRect[]): Line[] {
  const lines: Line[] = []
  for (const rect of rects) {
    if (rect.width === 0 || rect.height === 0) continue
    const middle = (rect.top + rect.bottom) / 2
    const line = lines.find((candidate) => middle > candidate.top && middle < candidate.bottom)
    if (line) {
      line.left = Math.min(line.left, rect.left)
      line.right = Math.max(line.right, rect.right)
      line.top = Math.min(line.top, rect.top)
      line.bottom = Math.max(line.bottom, rect.bottom)
    } else {
      lines.push({ left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom })
    }
  }
  return lines
}
