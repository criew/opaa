import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDocumentContent } from '../api/libraryApi'
import { openDocumentContent, openInBrowserViewer, TEXT_PREVIEW_MAX_BYTES } from './documentContent'

vi.mock('../api/libraryApi', () => ({
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
    it('hands a PDF to the app viewer instead of a new tab', async () => {
      const blob = new Blob(['x'], { type: 'application/pdf' })
      mockGetDocumentContent.mockResolvedValueOnce({ blob, fileName: 'original.pdf' })

      const result = await openDocumentContent('doc-1', 'fallback.pdf')

      expect(result).toEqual({ kind: 'pdf-preview', fileName: 'original.pdf', blob })
      expect(createObjectURLSpy).not.toHaveBeenCalled()
      expect(windowOpenSpy).not.toHaveBeenCalled()
    })

    it('opens a PDF in the browser viewer in a new tab and revokes the object URL after the delay', async () => {
      // window.open with 'noopener' in its features always returns null per spec, real tab opened
      // or not - the mock reflects that instead of a value no real browser produces.
      windowOpenSpy.mockReturnValue(null)

      openInBrowserViewer(new Blob(['x'], { type: 'application/pdf' }))

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

    it.each(['image/svg+xml', 'application/xhtml+xml', 'text/html', ''])(
      'never opens a %j blob in the browser viewer',
      (type) => {
        openInBrowserViewer(new Blob(['<x/>'], { type }))

        expect(createObjectURLSpy).not.toHaveBeenCalled()
        expect(windowOpenSpy).not.toHaveBeenCalled()
      },
    )

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

    // A blob: URL opened in a new tab runs in this app's origin, so only types that cannot carry
    // script may be opened there: an exact allowlist, never a prefix match.
    it.each([
      'image/png',
      'image/jpeg',
      'image/gif',
      'image/webp',
      'IMAGE/PNG',
      'image/jpeg; charset=binary',
      ' Image/WebP ; foo=bar',
    ])('previews the allowlisted image type %j in a new tab', async (type) => {
      windowOpenSpy.mockReturnValue(null)
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type }),
        fileName: 'original.img',
      })

      const result = await openDocumentContent('doc-1', 'fallback.img')

      expect(result).toEqual({ kind: 'blob-preview' })
      expect(windowOpenSpy).toHaveBeenCalledTimes(1)
      expect(clickSpy).not.toHaveBeenCalled()
    })

    it.each([
      'application/xhtml+xml',
      'application/xhtml+xml; charset=utf-8',
      'image/svg+xml',
      'IMAGE/SVG+XML',
      'application/rss+xml',
      'application/mathml+xml',
      'image/foo+xml',
      'text/xml',
      'Text/XML; charset=utf-8',
      'application/xml',
      'text/html',
      'image/bmp',
      'image/x-icon',
      'image/avif',
      'image/pngx',
      'application/pdfx',
      '',
    ])('downloads %j instead of opening it in a tab of the app origin', async (type) => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['<x/>'], { type }),
        fileName: 'original.bin',
      })

      const result = await openDocumentContent('doc-1', 'fallback.bin')

      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalledTimes(1)
      expect(result).toEqual({ kind: 'download', fileName: 'original.bin' })
    })

    it('downloads a non-previewable file under its original name instead of opening a tab', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type: 'application/vnd.ms-word' }),
        fileName: 'original.docx',
      })

      const result = await openDocumentContent('doc-1', 'fallback.docx')

      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalledTimes(1)
      expect(result).toEqual({ kind: 'download', fileName: 'original.docx' })
    })

    // DOCX (and every other format without its own preview) stays a download - the caller
    // uses the 'download' result kind to show visible feedback ("<Dateiname> wird heruntergeladen"),
    // since the acceptance criteria require a click to never appear to do nothing.
    it('reports a DOCX download by its result kind so the caller can show feedback (#780)', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], {
          type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
        }),
        fileName: 'bescheid.docx',
      })

      const result = await openDocumentContent('doc-1', 'fallback.docx')

      expect(result).toEqual({ kind: 'download', fileName: 'bescheid.docx' })
      expect(clickSpy).toHaveBeenCalledTimes(1)
    })

    // Markdown/plain text never navigate to a blob: URL (the browser would show it raw or
    // download it, depending on browser) - the caller renders `content` in its own preview dialog.
    it('returns Markdown content for a text preview instead of opening a tab or downloading (#780)', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['# Titel\n\nInhalt'], { type: 'text/markdown; charset=utf-8' }),
        fileName: '001_personalausweis.md',
      })

      const result = await openDocumentContent('doc-1', 'fallback.md')

      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).not.toHaveBeenCalled()
      expect(createObjectURLSpy).not.toHaveBeenCalled()
      expect(result).toEqual({
        kind: 'text-preview',
        fileName: '001_personalausweis.md',
        contentType: 'text/markdown',
        content: '# Titel\n\nInhalt',
        blob: expect.any(Blob),
      })
    })

    it('returns plain text content for a text preview (#780)', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['Reiner Text ohne Markup.'], { type: 'text/plain' }),
        fileName: 'notiz.txt',
      })

      const result = await openDocumentContent('doc-1', 'fallback.txt')

      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).not.toHaveBeenCalled()
      expect(result).toEqual({
        kind: 'text-preview',
        fileName: 'notiz.txt',
        contentType: 'text/plain',
        content: 'Reiner Text ohne Markup.',
        blob: expect.any(Blob),
      })
    })

    // Reading an unbounded Markdown/plain text original into a string via
    // `blob.text()` could freeze the tab - above TEXT_PREVIEW_MAX_BYTES it falls back to a
    // download instead, tagged with `reason` so the caller can explain why.
    it('falls back to a download for a Markdown original larger than TEXT_PREVIEW_MAX_BYTES (#781 review, Nit 3)', async () => {
      const oversized = new Blob([new Uint8Array(TEXT_PREVIEW_MAX_BYTES + 1)], {
        type: 'text/markdown',
      })
      const textSpy = vi.spyOn(oversized, 'text')
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: oversized,
        fileName: 'riesiges-dokument.md',
      })

      const result = await openDocumentContent('doc-1', 'fallback.md')

      expect(textSpy).not.toHaveBeenCalled()
      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalledTimes(1)
      expect(result).toEqual({
        kind: 'download',
        fileName: 'riesiges-dokument.md',
        reason: 'too-large-for-preview',
      })
    })

    it('still previews a Markdown original at exactly TEXT_PREVIEW_MAX_BYTES (#781 review, Nit 3)', async () => {
      const atLimit = new Blob([new Uint8Array(TEXT_PREVIEW_MAX_BYTES)], { type: 'text/markdown' })
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: atLimit,
        fileName: 'genau-am-limit.md',
      })

      const result = await openDocumentContent('doc-1', 'fallback.md')

      expect(result.kind).toBe('text-preview')
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
