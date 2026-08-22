import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDocumentContent } from '../services/api'
import { openDocumentContent } from './documentContent'

vi.mock('../services/api', () => ({
  getDocumentContent: vi.fn(),
}))

const mockGetDocumentContent = vi.mocked(getDocumentContent)

describe('documentContent', () => {
  let createObjectURLSpy: ReturnType<typeof vi.spyOn>
  let revokeObjectURLSpy: ReturnType<typeof vi.spyOn>
  let windowOpenSpy: ReturnType<typeof vi.spyOn>
  let clickSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    vi.useFakeTimers()
    createObjectURLSpy = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:mock-object-url')
    revokeObjectURLSpy = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    windowOpenSpy = vi.spyOn(window, 'open')
    clickSpy = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
  })

  describe('openDocumentContent', () => {
    it('previews a PDF in a new tab and revokes the object URL after the delay', async () => {
      // window.open with 'noopener' in its features always returns null per spec, real tab opened
      // or not (#743 review) - the mock reflects that instead of a value no real browser produces.
      windowOpenSpy.mockReturnValue(null)
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type: 'application/pdf' }),
        fileName: 'original.pdf',
      })

      await openDocumentContent('doc-1', 'fallback.pdf')

      expect(createObjectURLSpy).toHaveBeenCalledTimes(1)
      expect(windowOpenSpy).toHaveBeenCalledWith(
        'blob:mock-object-url',
        '_blank',
        'noopener,noreferrer',
      )
      expect(clickSpy).not.toHaveBeenCalled()
      expect(revokeObjectURLSpy).not.toHaveBeenCalled()

      await vi.advanceTimersByTimeAsync(60_000)

      expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-object-url')
    })

    it('previews an image in a new tab', async () => {
      windowOpenSpy.mockReturnValue(null)
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type: 'image/png' }),
        fileName: 'original.png',
      })

      await openDocumentContent('doc-1', 'fallback.png')

      expect(windowOpenSpy).toHaveBeenCalledWith(
        'blob:mock-object-url',
        '_blank',
        'noopener,noreferrer',
      )
      expect(clickSpy).not.toHaveBeenCalled()
    })

    it('downloads an SVG under its original name instead of opening it inline (#743: SVG can carry script)', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['<svg/>'], { type: 'image/svg+xml' }),
        fileName: 'original.svg',
      })

      await openDocumentContent('doc-1', 'fallback.svg')

      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalledTimes(1)
    })

    it('downloads an SVG with a charset parameter too (#748 review, finding 2b: the essence, not the full Content-Type line, must be compared)', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['<svg/>'], { type: 'image/svg+xml; charset=utf-8' }),
        fileName: 'original.svg',
      })

      await openDocumentContent('doc-1', 'fallback.svg')

      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalledTimes(1)
    })

    it('downloads a non-previewable file under its original name instead of opening a tab', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type: 'application/vnd.ms-word' }),
        fileName: 'original.docx',
      })

      await openDocumentContent('doc-1', 'fallback.docx')

      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalledTimes(1)
    })

    it('revokes the object URL when triggering the download throws before the revoke is scheduled', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type: 'application/vnd.ms-word' }),
        fileName: 'original.docx',
      })
      clickSpy.mockImplementation(() => {
        throw new Error('boom')
      })

      await expect(openDocumentContent('doc-1', 'fallback.docx')).rejects.toThrow('boom')

      expect(revokeObjectURLSpy).toHaveBeenCalledWith('blob:mock-object-url')
    })

    it('uses the fallback file name when the response carries none', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type: 'application/vnd.ms-word' }),
        fileName: null,
      })
      let downloadAttribute: string | null = null
      clickSpy.mockImplementation(function (this: HTMLAnchorElement) {
        downloadAttribute = this.download
      })

      await openDocumentContent('doc-1', 'fallback.docx')

      expect(downloadAttribute).toBe('fallback.docx')
    })

    it('propagates a fetch failure (e.g. 404) to the caller', async () => {
      mockGetDocumentContent.mockRejectedValueOnce(
        new Error('Das Originaldokument wurde nicht gefunden.'),
      )

      await expect(openDocumentContent('doc-1', 'fallback.pdf')).rejects.toThrow(
        'Das Originaldokument wurde nicht gefunden.',
      )
      expect(createObjectURLSpy).not.toHaveBeenCalled()
    })
  })
})
