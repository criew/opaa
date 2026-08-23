import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useDocumentPreview } from './useDocumentPreview'
import { openDocumentContent } from '../utils/documentContent'

vi.mock('../utils/documentContent', () => ({
  openDocumentContent: vi.fn(),
}))

const mockOpenDocumentContent = vi.mocked(openDocumentContent)

describe('useDocumentPreview', () => {
  it('opens a text preview for a Markdown/plain text result', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({
      kind: 'text-preview',
      fileName: '001_personalausweis.md',
      contentType: 'text/markdown',
      content: '# Titel',
    })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument('doc-1', 'fallback.md')
    })

    expect(result.current.previewDocument).toEqual({
      kind: 'text-preview',
      fileName: '001_personalausweis.md',
      contentType: 'text/markdown',
      content: '# Titel',
    })
    expect(result.current.downloadMessage).toBeNull()
    expect(result.current.error).toBeNull()
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
      await result.current.openDocument('doc-1', 'fallback.txt')
    })
    expect(result.current.previewDocument).not.toBeNull()

    act(() => result.current.closePreview())

    expect(result.current.previewDocument).toBeNull()
  })

  // #780 acceptance criteria: a format without a preview (DOCX among them) must give visible
  // download feedback so a click never appears to do nothing.
  it('shows a German download message for a download result', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'bescheid.docx' })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument('doc-1', 'fallback.docx')
    })

    expect(result.current.downloadMessage).toBe('bescheid.docx wird heruntergeladen')
    expect(result.current.previewDocument).toBeNull()
  })

  it('clears the download message via clearDownloadMessage', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'download', fileName: 'a.docx' })
    const { result } = renderHook(() => useDocumentPreview())
    await act(async () => {
      await result.current.openDocument('doc-1', 'fallback.docx')
    })
    expect(result.current.downloadMessage).not.toBeNull()

    act(() => result.current.clearDownloadMessage())

    expect(result.current.downloadMessage).toBeNull()
  })

  it('does not show a preview or a download message for a blob-preview result (PDF/image unchanged)', async () => {
    mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'blob-preview' })
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument('doc-1', 'fallback.pdf')
    })

    expect(result.current.previewDocument).toBeNull()
    expect(result.current.downloadMessage).toBeNull()
    expect(result.current.error).toBeNull()
  })

  it('surfaces a fetch failure as a German error message', async () => {
    mockOpenDocumentContent.mockRejectedValueOnce(
      new Error('Das Originaldokument wurde nicht gefunden.'),
    )
    const { result } = renderHook(() => useDocumentPreview())

    await act(async () => {
      await result.current.openDocument('doc-1', 'fallback.pdf')
    })

    await waitFor(() =>
      expect(result.current.error).toBe('Das Originaldokument wurde nicht gefunden.'),
    )
  })

  it('clears a previous error when opening a document again', async () => {
    mockOpenDocumentContent.mockRejectedValueOnce(new Error('boom'))
    const { result } = renderHook(() => useDocumentPreview())
    await act(async () => {
      await result.current.openDocument('doc-1', 'fallback.pdf')
    })
    expect(result.current.error).toBe('boom')

    mockOpenDocumentContent.mockResolvedValueOnce({ kind: 'blob-preview' })
    await act(async () => {
      await result.current.openDocument('doc-1', 'fallback.pdf')
    })

    expect(result.current.error).toBeNull()
  })
})
