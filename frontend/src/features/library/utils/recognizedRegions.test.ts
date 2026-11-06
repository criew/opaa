import { describe, expect, it } from 'vitest'
import type { ChunkAnchor } from '@/shared/api/types'
import { drawRegions, framedPages, REGION_CLASS, regionsOnPage } from './recognizedRegions'

const ACROSS_PAGES: ChunkAnchor = {
  pageFrom: 2,
  pageTo: 3,
  regions: [
    { page: 2, x: 0.1, y: 0.8, width: 0.8, height: 0.15 },
    { page: 3, x: 0.1, y: 0.05, width: 0.8, height: 0.1 },
  ],
  textOrigin: { connectionType: 'DOCLING_SERVE' },
}

describe('recognizedRegions', () => {
  it('names the pages a passage is framed on, across a page break', () => {
    expect(framedPages(ACROSS_PAGES)).toEqual({ from: 2, to: 3 })
    expect(framedPages({ pageFrom: 2, textOrigin: { connectionType: 'OPENAI_VISION' } })).toBeNull()
    expect(framedPages(null)).toBeNull()
  })

  it('takes only the frames of the page asked for and keeps them inside the page', () => {
    const anchor: ChunkAnchor = {
      regions: [{ page: 1, x: 0.9, y: -0.01, width: 0.3, height: 0.5 }],
    }

    expect(regionsOnPage(ACROSS_PAGES, 3)).toEqual([ACROSS_PAGES.regions![1]])
    expect(regionsOnPage(ACROSS_PAGES, 1)).toEqual([])
    const [frame] = regionsOnPage(anchor, 1)
    expect(frame.x).toBe(0.9)
    expect(frame.y).toBe(0)
    expect(frame.width).toBeCloseTo(0.1)
    expect(frame.height).toBe(0.5)
  })

  it('places a frame in fractions of the page, so it follows every zoom step', () => {
    const page = document.createElement('div')

    const first = drawRegions(page, regionsOnPage(ACROSS_PAGES, 2))

    expect(first).not.toBeNull()
    expect(first).toHaveClass(REGION_CLASS)
    expect(first).toHaveAttribute('aria-hidden', 'true')
    expect(first!.style.left).toBe('10%')
    expect(first!.style.top).toBe('80%')
    expect(first!.style.width).toBe('80%')
    expect(first!.style.height).toBe('15%')
  })

  it('draws a page once per render and again after pdf.js emptied it', () => {
    const page = document.createElement('div')
    const regions = regionsOnPage(ACROSS_PAGES, 2)

    drawRegions(page, regions)
    drawRegions(page, regions)
    expect(page.querySelectorAll(`.${REGION_CLASS}`)).toHaveLength(1)

    page.replaceChildren()
    expect(drawRegions(page, regions)).not.toBeNull()
    expect(page.querySelectorAll(`.${REGION_CLASS}`)).toHaveLength(1)
  })

  it('draws nothing on a page without frames', () => {
    const page = document.createElement('div')

    expect(drawRegions(page, [])).toBeNull()
    expect(page.childElementCount).toBe(0)
  })
})
