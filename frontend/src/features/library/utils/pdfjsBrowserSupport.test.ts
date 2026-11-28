import { describe, expect, it } from 'vitest'

describe('loadPdfViewer', () => {
  // Chromium before 145 lacks both; pdf.js and its viewer call them without a fallback.
  it('runs in a browser without Map.getOrInsertComputed and Math.sumPrecise', async () => {
    delete (Map.prototype as { getOrInsertComputed?: unknown }).getOrInsertComputed
    delete (Math as { sumPrecise?: unknown }).sumPrecise
    const { loadPdfViewer } = await import('./pdfjs')

    const { viewer } = await loadPdfViewer()

    expect(viewer.PDFViewer).toBeTypeOf('function')
    expect(typeof (Map.prototype as { getOrInsertComputed?: unknown }).getOrInsertComputed).toBe(
      'function',
    )
    expect(typeof (Math as { sumPrecise?: unknown }).sumPrecise).toBe('function')
  })
})
