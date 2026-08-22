import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { getDocumentContent } from '../services/api'
import { openDocumentContent, openExternalSourceUrl } from './documentContent'

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
      windowOpenSpy.mockReturnValue({} as Window)
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
      windowOpenSpy.mockReturnValue({} as Window)
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

    it('downloads a non-previewable file under its original name instead of opening a tab', async () => {
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type: 'application/vnd.ms-word' }),
        fileName: 'original.docx',
      })

      await openDocumentContent('doc-1', 'fallback.docx')

      expect(windowOpenSpy).not.toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalledTimes(1)
    })

    it('falls back to a download when the preview tab is blocked', async () => {
      windowOpenSpy.mockReturnValue(null)
      mockGetDocumentContent.mockResolvedValueOnce({
        blob: new Blob(['x'], { type: 'application/pdf' }),
        fileName: 'original.pdf',
      })

      await openDocumentContent('doc-1', 'fallback.pdf')

      expect(windowOpenSpy).toHaveBeenCalled()
      expect(clickSpy).toHaveBeenCalledTimes(1)
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

  describe('openExternalSourceUrl', () => {
    it('opens the given URL in a new tab without exposing window.opener', () => {
      openExternalSourceUrl('https://example.gov/aktuelles/dienstanweisung-2024')

      expect(windowOpenSpy).toHaveBeenCalledWith(
        'https://example.gov/aktuelles/dienstanweisung-2024',
        '_blank',
        'noopener,noreferrer',
      )
    })
  })
})
