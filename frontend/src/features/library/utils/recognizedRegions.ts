import type { ChunkAnchor, ChunkRegion } from '@/shared/api/types'

/** CSS class of a frame drawn over a rendered page. */
export const REGION_CLASS = 'recognized-region'

/** The anchor's frames on `page`, clamped into the page. */
export function regionsOnPage(anchor: ChunkAnchor | null | undefined, page: number): ChunkRegion[] {
  return (anchor?.regions ?? []).filter((region) => region.page === page).map(clamped)
}

/** First and last page with a frame; `null` without frames. */
export function framedPages(
  anchor: ChunkAnchor | null | undefined,
): { from: number; to: number } | null {
  const pages = (anchor?.regions ?? []).map((region) => region.page)
  if (pages.length === 0) return null
  return { from: Math.min(...pages), to: Math.max(...pages) }
}

/**
 * Puts the frames into a rendered page div, once per render: pdf.js empties the div when it renders
 * the page again (zoom, scrolling back), so a second call for the same render draws nothing more.
 * The frames are placed in fractions of the page, so they follow every zoom step without a redraw.
 * Returns the first frame of the page, `null` when there is none.
 */
export function drawRegions(pageDiv: HTMLElement, regions: ChunkRegion[]): HTMLElement | null {
  const existing = pageDiv.querySelector<HTMLElement>(`.${REGION_CLASS}`)
  if (existing || regions.length === 0) return existing
  for (const region of regions) {
    const frame = pageDiv.ownerDocument.createElement('div')
    frame.className = REGION_CLASS
    frame.setAttribute('aria-hidden', 'true')
    frame.style.left = `${region.x * 100}%`
    frame.style.top = `${region.y * 100}%`
    frame.style.width = `${region.width * 100}%`
    frame.style.height = `${region.height * 100}%`
    pageDiv.appendChild(frame)
  }
  return pageDiv.querySelector<HTMLElement>(`.${REGION_CLASS}`)
}

function clamped(region: ChunkRegion): ChunkRegion {
  const x = clamp(region.x)
  const y = clamp(region.y)
  return {
    page: region.page,
    x,
    y,
    width: Math.min(clamp(region.width), 1 - x),
    height: Math.min(clamp(region.height), 1 - y),
  }
}

function clamp(value: number): number {
  return Math.min(Math.max(value, 0), 1)
}
