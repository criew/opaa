import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ChunkAnchor } from '@/shared/api/types'
import { renderWithProviders } from '@/test/test-utils'
import PdfViewer from './PdfViewer'

type Handler = (payload: never) => void

/** Stand-ins for pdf.js: the test fires the viewer's events itself. */
const pdf = vi.hoisted(() => ({
  handlers: new Map<string, ((payload: unknown) => void)[]>(),
  scrollPageIntoView: vi.fn(),
}))

vi.mock('../utils/pdfjs', () => {
  class EventBus {
    on(name: string, handler: Handler) {
      pdf.handlers.set(name, [...(pdf.handlers.get(name) ?? []), handler as never])
    }
  }
  class PDFLinkService {
    setViewer() {}
    setDocument() {}
  }
  class PDFViewer {
    currentScaleValue = 'auto'
    currentScale = 1
    linkService = { goToDestination: async () => {} }
    scrollPageIntoView = pdf.scrollPageIntoView
    setDocument() {}
  }
  const document = {
    numPages: 3,
    getOutline: async () => null,
    getPage: async () => ({ getTextContent: async () => ({ items: [] }), cleanup() {} }),
  }
  return {
    loadPdf: async () => ({ promise: Promise.resolve(document), destroy: async () => {} }),
    loadPdfViewer: async () => ({
      pdfjs: { AnnotationMode: { DISABLE: 0 } },
      viewer: { EventBus, PDFLinkService, PDFViewer },
    }),
  }
})

function fire(name: string, payload: unknown) {
  act(() => {
    for (const handler of pdf.handlers.get(name) ?? []) handler(payload)
  })
}

function renderedPage(lines: string[] = []): HTMLElement {
  const div = document.createElement('div')
  const layer = document.createElement('div')
  layer.className = 'textLayer'
  for (const line of lines) {
    const span = document.createElement('span')
    span.textContent = line
    layer.appendChild(span)
  }
  div.appendChild(layer)
  return div
}

async function open(
  anchor: ChunkAnchor | null,
  extra: { chunkIndex?: number; contentSha256?: string } = {},
) {
  renderWithProviders(
    <PdfViewer
      data={new ArrayBuffer(8)}
      anchor={anchor}
      onError={() => {}}
      documentId="doc-scan"
      chunkIndex={extra.chunkIndex}
      contentSha256={extra.contentSha256}
    />,
  )
  expect(await screen.findByText('Seite 1 von 3')).toBeInTheDocument()
}

const SCAN: ChunkAnchor = {
  pageFrom: 2,
  pageTo: 3,
  path: ['Anlage zur Verwaltungsgebührensatzung', 'Gebührentarif'],
  quoteStart: 'Nr. | Gegenstand | Gebühr 4 | Zweitausfertigung eines',
  quoteEnd: '4 | Zweitausfertigung eines Bescheids | 46,00 Euro',
  regions: [
    { page: 2, x: 0.11, y: 0.8, width: 0.78, height: 0.12 },
    { page: 3, x: 0.11, y: 0.06, width: 0.78, height: 0.05 },
  ],
  textOrigin: { connectionType: 'DOCLING_SERVE' },
}

describe('PdfViewer', () => {
  const scrollIntoView = vi.fn()

  beforeEach(() => {
    pdf.handlers.clear()
    pdf.scrollPageIntoView.mockReset()
    Element.prototype.scrollIntoView = scrollIntoView
  })

  afterEach(() => {
    scrollIntoView.mockReset()
  })

  describe('a scan read by text recognition', () => {
    it('opens the framed page and frames the passage over a page break', async () => {
      await open(SCAN)

      fire('pagesinit', {})
      expect(pdf.scrollPageIntoView).toHaveBeenCalledWith({ pageNumber: 2 })

      const second = renderedPage()
      const third = renderedPage()
      fire('pagerendered', { pageNumber: 2, source: { div: second } })
      fire('pagerendered', { pageNumber: 3, source: { div: third } })

      const frame = second.querySelector<HTMLElement>('.recognized-region')!
      expect(frame.style.top).toBe('80%')
      expect(third.querySelectorAll('.recognized-region')).toHaveLength(1)
      expect(scrollIntoView).toHaveBeenCalledTimes(1)
      expect(scrollIntoView.mock.contexts[0]).toBe(frame)
      expect(screen.getByRole('status')).toHaveTextContent(
        'Fundstelle auf den Seiten 2–3 umrahmt. Maschinell erkannt, maßgeblich ist das Original.',
      )
    })

    it('frames the page again after a zoom step without jumping back to it', async () => {
      await open(SCAN)
      const page = renderedPage()
      fire('pagerendered', { pageNumber: 2, source: { div: page } })

      page.replaceChildren()
      fire('pagerendered', { pageNumber: 2, source: { div: page } })

      expect(page.querySelectorAll('.recognized-region')).toHaveLength(1)
      expect(scrollIntoView).toHaveBeenCalledTimes(1)
    })

    it('leaves the text layer alone, since a scan has none worth searching', async () => {
      await open(SCAN)
      const page = renderedPage(['Nr. Gegenstand Gebühr 4 Zweitausfertigung eines Bescheids'])

      fire('textlayerrendered', { pageNumber: 2, source: { div: page } })

      expect(page.querySelector('.passage')).toBeNull()
      expect(screen.getByRole('status')).not.toHaveTextContent('nicht gefunden')
    })

    it('opens only the page of a scan read without frames and says so', async () => {
      await open({
        pageFrom: 2,
        pageTo: 2,
        quoteStart: 'Anlage zur Verwaltungsgebührensatzung',
        textOrigin: { connectionType: 'OPENAI_VISION' },
      })

      fire('pagesinit', {})

      expect(pdf.scrollPageIntoView).toHaveBeenCalledWith({ pageNumber: 2 })
      expect(screen.getByRole('status')).toHaveTextContent(
        'Fundstelle auf Seite 2, im Scan nicht umrahmt. Maschinell erkannt, maßgeblich ist das Original.',
      )
    })

    it('falls back to the page when the frames lie beyond the last page of the original', async () => {
      await open({
        pageFrom: 2,
        pageTo: 2,
        regions: [{ page: 5, x: 0.1, y: 0.2, width: 0.8, height: 0.1 }],
        textOrigin: { connectionType: 'DOCLING_SERVE' },
      })

      fire('pagesinit', {})
      const page = renderedPage()
      fire('pagerendered', { pageNumber: 2, source: { div: page } })

      expect(pdf.scrollPageIntoView).toHaveBeenCalledWith({ pageNumber: 2 })
      expect(page.querySelector('.recognized-region')).toBeNull()
      expect(screen.getByRole('status')).toHaveTextContent(
        'Fundstelle auf Seite 2, im Scan nicht umrahmt.',
      )
    })

    it('warns of a changed original by what it shows: frame or page', async () => {
      const changed = { contentSha256: 'ff'.repeat(32) }
      await open({ pageFrom: 2, textOrigin: { connectionType: 'OPENAI_VISION' } }, changed)
      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent(
          'Das Original wurde seit der Antwort geändert – die Fundseite kann abweichen.',
        ),
      )
    })

    it('warns that the frame may be off when a framed original has changed', async () => {
      await open(SCAN, { contentSha256: 'ff'.repeat(32) })
      await waitFor(() =>
        expect(screen.getByRole('status')).toHaveTextContent(
          'Das Original wurde seit der Antwort geändert – der Rahmen kann abweichen.',
        ),
      )
    })

    it('shows the recognized text of the passage beside the scan and hides it on request', async () => {
      const user = userEvent.setup()
      await open(SCAN, { chunkIndex: 4 })

      const panel = screen.getByRole('complementary', { name: 'Erkannter Text' })
      // the panel scrolls on its own, so it must be reachable by keyboard
      expect(panel).toHaveAttribute('tabindex', '0')
      expect(within(panel).getByText('Seiten 2–3 · Gebührentarif')).toBeInTheDocument()
      expect(await within(panel).findByTestId('recognized-text')).toHaveTextContent(
        '4 | Zweitausfertigung eines Bescheids | 46,00 Euro',
      )
      expect(
        within(panel).getByText('Maschinell erkannt, maßgeblich ist das Original'),
      ).toBeInTheDocument()

      const toggle = screen.getByRole('button', { name: 'Erkannter Text' })
      expect(toggle).toHaveAttribute('aria-expanded', 'true')
      await user.click(toggle)

      expect(toggle).toHaveAttribute('aria-expanded', 'false')
      expect(screen.queryByRole('complementary', { name: 'Erkannter Text' })).toBeNull()
    })

    it('shows the quotes of the citation when the stored passage no longer is the cited one', async () => {
      await open(SCAN, { chunkIndex: 1 })

      const panel = screen.getByRole('complementary', { name: 'Erkannter Text' })
      await waitFor(() =>
        expect(within(panel).getByTestId('recognized-text')).toHaveTextContent(
          'Nr. | Gegenstand | Gebühr 4 | Zweitausfertigung eines … 4 | Zweitausfertigung eines Bescheids | 46,00 Euro',
        ),
      )
    })
  })

  // regression guard: a PDF with a text layer is marked through it as before
  describe('a PDF with a text layer', () => {
    const TEXT: ChunkAnchor = {
      pageFrom: 2,
      pageTo: 2,
      quoteStart: 'Das Modul indexing verwaltet die Dokumentenaufnahme',
    }

    it('marks the passage in the text layer, draws no frame and offers no recognized text', async () => {
      await open(TEXT)
      fire('pagesinit', {})
      const page = renderedPage(['2 Module', 'Das Modul indexing verwaltet die Dokumentenaufnahme'])

      fire('pagerendered', { pageNumber: 2, source: { div: page } })
      fire('textlayerrendered', { pageNumber: 2, source: { div: page } })

      expect(pdf.scrollPageIntoView).toHaveBeenCalledWith({ pageNumber: 2 })
      expect(page.querySelectorAll('.passage')).toHaveLength(1)
      expect(page.querySelector('.recognized-region')).toBeNull()
      expect(screen.getByRole('status')).toHaveTextContent('Fundstelle auf Seite 1 markiert.')
      expect(screen.queryByRole('button', { name: 'Erkannter Text' })).toBeNull()
      expect(screen.queryByRole('complementary', { name: 'Erkannter Text' })).toBeNull()
    })
  })
})
