import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDocumentPreview } from './useDocumentPreview'
import { openDocumentContent } from '../utils/documentContent'
import { useNotificationStore } from '@/shared/stores/notificationStore'

vi.mock('../utils/documentContent', async (importOriginal) => ({
  opensAsTextVersion: (await importOriginal<typeof import('../utils/documentContent')>())
    .opensAsTextVersion,
  openDocumentContent: vi.fn(),
}))

const mockOpenDocumentContent = vi.mocked(openDocumentContent)

/** The queued global popup notifications (guidelines 5.9) - download notices and failures land
 *  here instead of in hook-local state. */
function queuedMessages(): string[] {
  return useNotificationStore.getState().queue.map((n) => n.message)
}

describe('useDocumentPreview', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useNotificationStore.getState().reset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('opens a text preview for a Markdown/plain text result', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({
      kind: 'text-preview',
      blob: new Blob(),
      fileName: '001_personalausweis.md',
      contentType: 'text/markdown',
      content: '# Titel',
    })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({ id: 'doc-1', fileName: 'fallback.md' })
    })

    expect(result.current.previewDocument).toEqual({
      kind: 'text-preview',
      blob: new Blob(),
      fileName: '001_personalausweis.md',
      contentType: 'text/markdown',
      content: '# Titel',
    })
    expect(queuedMessages()).toEqual([])
  })

  it('hands the anchor to the text preview', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({
      kind: 'text-preview',
      blob: new Blob(),
      fileName: 'a.md',
      contentType: 'text/markdown',
      content: '# Titel',
    })
    const anchor = { quoteStart: 'Titel' }
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({ id: 'doc-1', fileName: 'a.md', anchor })
    })

    expect(result.current.previewDocument?.anchor).toBe(anchor)
  })

  it('closes the preview via closePreview', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({
      kind: 'text-preview',
      blob: new Blob(),
      fileName: 'a.txt',
      contentType: 'text/plain',
      content: 'x',
    })
    const { result } = renderHook(() => useDocumentPreview())
    await act(async () => {
      await result.current.openDocument({ id: 'doc-1', fileName: 'fallback.txt' })
    })
    expect(result.current.previewDocument).not.toBeNull()

    act(() => result.current.closePreview())

    expect(result.current.previewDocument).toBeNull()
  })

  it('opens a PDF in the app viewer with the anchor and checksum the caller passed', async () => {
    const blob = new Blob(['%PDF'], { type: 'application/pdf' })
    const anchor = { pageFrom: 2, pageTo: 3, quoteStart: 'Anfang', quoteEnd: 'Ende' }
    mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'pdf-preview', fileName: 'a.pdf', blob })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({
        id: 'doc-1',
        fileName: 'fallback.pdf',
        anchor,
        contentSha256: 'ab12',
      })
    })

    expect(result.current.pdfPreview).toEqual({
      kind: 'pdf-preview',
      fileName: 'a.pdf',
      blob,
      anchor,
      contentSha256: 'ab12',
    })
    expect(result.current.previewDocument).toBeNull()

    act(() => result.current.closePdfPreview())

    expect(result.current.pdfPreview).toBeNull()
  })

  // A format without a preview or Textfassung (an archive, say) must give visible download
  // feedback so a click never appears to do nothing - as a global popup notification (5.9).
  it('notifies with a German download message for a download result', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'anlagen.zip' })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({ id: 'doc-1', fileName: 'fallback.zip' })
    })

    expect(queuedMessages()).toEqual(['anlagen.zip wird heruntergeladen'])
    expect(result.current.previewDocument).toBeNull()
  })

  // A Markdown/plain text original that fell back to a download purely
  // because it exceeded the size cap gets a more informative message than the generic one above.
  it('notifies with a "zu groß für die Vorschau" message for a too-large-for-preview download', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({
      kind: 'download',
      fileName: 'riesiges-dokument.md',
      reason: 'too-large-for-preview',
    })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({ id: 'doc-1', fileName: 'fallback.md' })
    })

    expect(queuedMessages()).toEqual([
      'riesiges-dokument.md ist zu groß für die Vorschau – wird heruntergeladen',
    ])
  })

  it('opens an Office document as its Textfassung at the cited passage, without fetching it', async () => {
    const anchor = { slide: 3, quoteStart: 'Eine Frage' }
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({
        id: 'doc-1',
        fileName: 'Einführung.PPTX',
        chunkIndex: 4,
        anchor,
      })
    })

    expect(mockOpenDocumentContent).not.toHaveBeenCalled()
    expect(result.current.textVersion).toEqual({
      documentId: 'doc-1',
      fileName: 'Einführung.PPTX',
      chunkIndex: 4,
      anchor,
    })
    act(() => result.current.closeTextVersion())
    expect(result.current.textVersion).toBeNull()
  })

  it('does not preview or notify for a blob-preview result (PDF/image unchanged)', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'blob-preview' })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({ id: 'doc-1', fileName: 'fallback.pdf' })
    })

    expect(result.current.previewDocument).toBeNull()
    expect(queuedMessages()).toEqual([])
  })

  it('surfaces a fetch failure as a German error notification', async () => {
    mockOpenDocumentContent.mockRejectedValueOnce(
      new Error('Das Originaldokument wurde nicht gefunden.'),
    )
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({ id: 'doc-1', fileName: 'fallback.pdf' })
    })

    expect(useNotificationStore.getState().queue).toEqual([
      expect.objectContaining({
        message: 'Das Originaldokument wurde nicht gefunden.',
        severity: 'error',
      }),
    ])
  })

  // ADR-0023: the content endpoint deliberately has no original for CONFLUENCE - the document
  // opens directly at its source URL, exactly like the citation deep link.
  it('opens a Confluence document at its source URL in a new tab, without the content endpoint', async () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null)
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({
        id: 'doc-1',
        fileName: 'Betriebshandbuch',
        sourceType: 'CONFLUENCE',
        sourceUrl: 'http://wiki.example/pages/viewpage.action?pageId=42',
      })
    })

    expect(openSpy).toHaveBeenCalledWith(
      'http://wiki.example/pages/viewpage.action?pageId=42',
      '_blank',
      'noopener,noreferrer',
    )
    expect(mockOpenDocumentContent).not.toHaveBeenCalled()
    expect(queuedMessages()).toEqual([])
  })

  it('opens a Confluence document at the cited passage through a text fragment', async () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null)
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({
        id: 'doc-1',
        fileName: 'Betriebshandbuch',
        sourceType: 'CONFLUENCE',
        sourceUrl: 'http://wiki.example/pages/viewpage.action?pageId=42',
        anchor: { quoteStart: 'Fristen beginnen', quoteEnd: 'mit Zustellung.' },
      })
    })

    expect(openSpy).toHaveBeenCalledWith(
      'http://wiki.example/pages/viewpage.action?pageId=42#:~:text=Fristen%20beginnen,mit%20Zustellung.',
      '_blank',
      'noopener,noreferrer',
    )
  })

  it('notifies with an error when a Confluence document carries no source URL at all', async () => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null)
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({
        id: 'doc-1',
        fileName: 'Betriebshandbuch',
        sourceType: 'CONFLUENCE',
      })
    })

    expect(openSpy).not.toHaveBeenCalled()
    expect(useNotificationStore.getState().queue).toEqual([
      expect.objectContaining({ severity: 'error' }),
    ])
  })

  // regression guard for #2912: a source address from third-party metadata is only navigated to
  // with an http(s) scheme; any other counts as no usable address.
  it.each([
    'javascript:alert(1)',
    'data:text/html,<script>alert(1)</script>',
    'file:///etc/passwd',
  ])('opens no tab for a Confluence document whose source URL is %j', async (sourceUrl) => {
    const openSpy = vi.spyOn(window, 'open').mockReturnValue(null)
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({
        id: 'doc-1',
        fileName: 'Betriebshandbuch',
        sourceType: 'CONFLUENCE',
        sourceUrl,
      })
    })

    expect(openSpy).not.toHaveBeenCalled()
    expect(mockOpenDocumentContent).not.toHaveBeenCalled()
    expect(queuedMessages()).toEqual(['Für dieses Dokument ist keine Quell-Adresse hinterlegt.'])
  })
})
