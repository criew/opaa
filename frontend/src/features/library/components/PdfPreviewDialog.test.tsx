import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useEffect } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderWithProviders } from '../../../test/test-utils'
import PdfPreviewDialog from './PdfPreviewDialog'
import type { PdfViewerProps } from './PdfViewer'
import type { PdfPreviewResult } from '../utils/documentContent'
import { openInBrowserViewer } from '../utils/documentContent'

const viewer = vi.hoisted(() => ({ fail: false, props: null as PdfViewerProps | null }))

vi.mock('./PdfViewer', () => ({
  default: function MockPdfViewer(props: PdfViewerProps) {
    viewer.props = props
    useEffect(() => {
      if (viewer.fail) props.onError(new Error('broken'))
    }, [props])
    return <div>Viewer</div>
  },
}))

vi.mock('../utils/documentContent', () => ({ openInBrowserViewer: vi.fn() }))

const ANCHOR = { pageFrom: 2, pageTo: 3, quoteStart: 'Anfang', quoteEnd: 'Ende' }

function pdf(): PdfPreviewResult {
  return {
    kind: 'pdf-preview',
    fileName: 'handbuch.pdf',
    blob: new Blob(['%PDF-1.4'], { type: 'application/pdf' }),
    anchor: ANCHOR,
    contentSha256: 'ab12',
  }
}

describe('PdfPreviewDialog', () => {
  it('stays closed without a preview', () => {
    renderWithProviders(<PdfPreviewDialog preview={null} onClose={vi.fn()} />)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows the file name and hands the bytes, anchor and checksum to the viewer', async () => {
    viewer.fail = false
    renderWithProviders(<PdfPreviewDialog preview={pdf()} onClose={vi.fn()} />)

    expect(screen.getByRole('dialog', { name: 'handbuch.pdf' })).toBeInTheDocument()
    expect(await screen.findByText('Viewer')).toBeInTheDocument()
    expect(viewer.props?.anchor).toEqual(ANCHOR)
    expect(viewer.props?.contentSha256).toBe('ab12')
    expect(new TextDecoder().decode(viewer.props?.data)).toBe('%PDF-1.4')
  })

  it('offers the browser viewer behind a click when the viewer cannot show the file', async () => {
    viewer.fail = true
    const onClose = vi.fn()
    const preview = pdf()
    renderWithProviders(<PdfPreviewDialog preview={preview} onClose={onClose} />)

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Dieses PDF kann hier nicht angezeigt werden.',
    )
    expect(openInBrowserViewer).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Im Browser öffnen' }))

    expect(openInBrowserViewer).toHaveBeenCalledWith(preview.blob)
    expect(onClose).toHaveBeenCalled()
  })
})
