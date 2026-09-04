import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useDocumentPreview } from './useDocumentPreview'
import { openDocumentContent } from '../utils/documentContent'
import { useNotificationStore } from '../stores/notificationStore'

vi.mock('../utils/documentContent', () => ({
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
      fileName: '001_personalausweis.md',
      contentType: 'text/markdown',
      content: '# Titel',
    })
    expect(queuedMessages()).toEqual([])
  })

  it('closes the preview via closePreview', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({
      kind: 'text-preview',
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

  // #780 acceptance criteria: a format without a preview (DOCX among them) must give visible
  // download feedback so a click never appears to do nothing - since guidelines 5.9 as a global
  // popup notification.
  it('notifies with a German download message for a download result', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'bescheid.docx' })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument({ id: 'doc-1', fileName: 'fallback.docx' })
    })

    expect(queuedMessages()).toEqual(['bescheid.docx wird heruntergeladen'])
    expect(result.current.previewDocument).toBeNull()
  })

  // #781 review, Nit 3: a Markdown/plain text original that fell back to a download purely
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
})
