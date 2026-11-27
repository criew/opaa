import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ChunkAnchor } from '@/shared/api/types'
import { renderWithProviders } from '@/test/test-utils'
import { setViewportWidth } from '@/test/viewport'
import PdfViewer from './PdfViewer'

type Handler = (payload: never) => void

interface PageBox {
  left: number
  width: number
  height: number
}

interface OutlineItem {
  title: string
  dest: unknown[]
  items: OutlineItem[]
}

/**
 * Stand-ins for pdf.js: the test fires the viewer's events itself. Pages lie stacked without gaps
 * in the scrolled content; a destination scrolls like pdf.js does, its point to the top left.
 */
const pdf = vi.hoisted(() => ({
  handlers: new Map<string, ((payload: unknown) => void)[]>(),
  scrollPageIntoView: vi.fn(),
  /** The scale pdf.js would compute for „page-width“. */
  pageWidthScale: 1,
  viewer: null as { currentScale: number; currentPageNumber: number } | null,
  pages: [] as PageBox[],
  outline: null as OutlineItem[] | null,
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
    currentScale = 1
    currentPageNumber = 1
    constructor() {
      pdf.viewer = this
    }
    set currentScaleValue(value: string) {
      if (value === 'page-width') this.currentScale = pdf.pageWidthScale
    }
    scrollPageIntoView = pdf.scrollPageIntoView
    get pagesCount() {
      return pdf.pages.length
    }
    getPageView(index: number) {
      return pageView(index)
    }
    setDocument() {}
  }
  const document = {
    numPages: 3,
    getOutline: async () => pdf.outline,
    getDestination: async () => null,
    getPageIndex: async (ref: { num: number }) => ref.num - 1,
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

const pageDivs = new Map<number, HTMLElement>()

function container(): HTMLElement {
  return screen.getByTestId('pdf-container')
}

function pageTop(index: number): number {
  return pdf.pages.slice(0, index).reduce((sum, page) => sum + page.height, 0)
}

/** A page of {@link pdf.pages} as pdf.js's page view: its element, and PDF points as pixels. */
function pageView(index: number) {
  const box = pdf.pages[index]
  if (!box) return undefined
  let div = pageDivs.get(index)
  if (!div) {
    div = document.createElement('div')
    div.getBoundingClientRect = () => {
      const view = container()
      const top = pageTop(index) - view.scrollTop
      const left = box.left - view.scrollLeft
      return rect({ left, top, width: box.width, height: box.height })
    }
    pageDivs.set(index, div)
  }
  return {
    div,
    viewport: {
      height: box.height,
      convertToViewportPoint: (x: number, y: number) => [x, box.height - y],
    },
  }
}

/** Scrolls as pdf.js does: a destination's point to the top left, a bare page to its top. */
function scrollLikePdfJs({ pageNumber, destArray }: { pageNumber: number; destArray?: unknown[] }) {
  const view = container()
  const box = pdf.pages[pageNumber - 1]
  if (destArray) {
    const [, , x, y] = destArray as [unknown, unknown, number, number]
    view.scrollLeft = box.left + x
    view.scrollTop = pageTop(pageNumber - 1) + box.height - y
  } else {
    view.scrollTop = pageTop(pageNumber - 1)
  }
  fire('pagechanging', { pageNumber })
  fire('updateviewarea', {})
}

/** The reader scrolls the view to `scrollTop` on their own. */
function wheelTo(scrollTop: number) {
  const view = container()
  fireEvent.wheel(view)
  view.scrollTop = scrollTop
  fireEvent.scroll(view)
  fire('updateviewarea', {})
}

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
  const view = container()
  Object.defineProperty(view, 'clientWidth', { configurable: true, value: 800 })
  Object.defineProperty(view, 'clientHeight', { configurable: true, value: 600 })
  // the pages and the space below the last one
  Object.defineProperty(view, 'scrollHeight', { configurable: true, value: pageTop(3) + 80 })
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
    pdf.scrollPageIntoView.mockImplementation(scrollLikePdfJs)
    pdf.pageWidthScale = 1
    pdf.pages = [0, 1, 2].map(() => ({ left: 0, width: 600, height: 1000 }))
    pdf.outline = null
    pageDivs.clear()
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
      expect(screen.getByRole('status')).toHaveTextContent('Fundstelle auf Seite 2 markiert.')
      expect(screen.queryByRole('button', { name: 'Erkannter Text' })).toBeNull()
      expect(screen.queryByRole('complementary', { name: 'Erkannter Text' })).toBeNull()
    })

    it('draws band, edge and label over the page and keeps naming the cited page', async () => {
      await open(TEXT)
      fire('pagesinit', {})
      const page = renderedPage(['2 Module', 'Das Modul indexing verwaltet die Dokumentenaufnahme'])
      sized(page, { left: 0, top: 0, width: 600, height: 800 })
      sized(page.querySelectorAll('span')[1], { left: 100, top: 200, width: 300, height: 20 })

      fire('textlayerrendered', { pageNumber: 2, source: { div: page } })

      const band = page.querySelector<HTMLElement>('.opaa-passage-band')!
      expect(band).toHaveAttribute('aria-hidden', 'true')
      expect(band.style.left).toMatch(/%$/)
      expect(page.querySelectorAll('.opaa-passage-edge')).toHaveLength(1)
      expect(page.querySelector('.opaa-passage-label')).toHaveTextContent('Fundstelle')
      // a later page change does not change what the status says
      wheelTo(2000)
      expect(screen.getByRole('status')).toHaveTextContent('Fundstelle auf Seite 2 markiert.')
      expect(screen.getAllByRole('status')).toHaveLength(1)

      scrollIntoView.mockReset()
      await userEvent.click(screen.getByRole('button', { name: 'Zur Fundstelle' }))
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'center', inline: 'nearest' })
    })

    it('shows the finding card with place and quote', async () => {
      await open(TEXT)
      expect(screen.getByRole('heading', { name: 'Fundstelle' })).toBeInTheDocument()
      expect(screen.getByText('Seite 2')).toBeInTheDocument()
      expect(
        screen.getByText('„Das Modul indexing verwaltet die Dokumentenaufnahme“'),
      ).toBeInTheDocument()
    })

    it('pages forward and back from the floating tools', async () => {
      await open(TEXT)
      wheelTo(1000)
      expect(screen.getByText('Seite 2 von 3')).toBeInTheDocument()
      await userEvent.click(screen.getByRole('button', { name: 'Nächste Seite' }))
      expect(pdf.viewer!.currentPageNumber).toBe(3)
      await userEvent.click(screen.getByRole('button', { name: 'Vorherige Seite' }))
      expect(pdf.viewer!.currentPageNumber).toBe(1)
    })
  })

  // regression guard for #3880: the outline marks the place shown, and a jump keeps pages of
  // different widths in view instead of scrolling a destination's left edge to the view's
  describe('navigating pages of different widths', () => {
    const at = (page: number, x: number, y: number) => [{ num: page }, { name: 'XYZ' }, x, y, 0]
    const OUTLINE: OutlineItem[] = [
      { title: 'Titel', dest: at(1, 0, 1000), items: [] },
      { title: 'Inhalt', dest: at(2, 100, 900), items: [] },
      { title: 'Abschnitt', dest: at(2, 100, 850), items: [] },
      { title: 'Vorwort', dest: at(2, 100, 400), items: [] },
      {
        title: 'Doppelseite',
        dest: at(3, 50, 1000),
        items: [{ title: 'Rechte Seite', dest: at(3, 1000, 700), items: [] }],
      },
    ]

    beforeEach(() => {
      // two portrait pages, centred above a spread twice as wide
      pdf.pages = [
        { left: 300, width: 600, height: 1000 },
        { left: 300, width: 600, height: 1000 },
        { left: 0, width: 1200, height: 1000 },
      ]
      pdf.outline = OUTLINE
    })

    const entry = async (title: string) =>
      within(await screen.findByRole('navigation', { name: 'Inhaltsverzeichnis' })).getByRole(
        'button',
        { name: title },
      )

    it('marks the entry chosen and its page, then the place the reader scrolls to', async () => {
      await open(undefined as unknown as ChunkAnchor)

      // „Abschnitt“ follows just below „Inhalt“, above the reading line after the jump
      await userEvent.click(await entry('Inhalt'))
      expect(await entry('Inhalt')).toHaveAttribute('aria-current', 'location')
      expect(screen.getByText('Seite 2 von 3')).toBeInTheDocument()

      // neither the view's own scroll event nor a click into the page that scrolls nothing
      // releases the choice
      fireEvent.scroll(container())
      fireEvent.pointerDown(container())
      fire('updateviewarea', {})
      expect(await entry('Inhalt')).toHaveAttribute('aria-current', 'location')

      await userEvent.click(await entry('Vorwort'))
      expect(await entry('Vorwort')).toHaveAttribute('aria-current', 'location')
      expect(await entry('Inhalt')).not.toHaveAttribute('aria-current')

      // the reading line, a quarter down the view, decides once the reader scrolls
      wheelTo(2000)
      expect(await entry('Doppelseite')).toHaveAttribute('aria-current', 'location')
      expect(screen.getByText('Seite 3 von 3')).toBeInTheDocument()

      wheelTo(1300)
      expect(await entry('Abschnitt')).toHaveAttribute('aria-current', 'location')
      expect(screen.getByText('Seite 2 von 3')).toBeInTheDocument()
    })

    // regression guard for #3897: a passage centred near the top of its page keeps naming that page
    it('names the page of the passage it opened at until the view is scrolled away', async () => {
      await open({ pageFrom: 2, pageTo: 2, quoteStart: 'Das Modul indexing' })
      const page = renderedPage(['Das Modul indexing verwaltet die Dokumentenaufnahme'])
      const span = page.querySelector('span')!
      // 50 px into page 2; centred, the reading line a quarter down still lies on page 1
      span.getBoundingClientRect = () =>
        rect({ left: 400, top: 1050 - container().scrollTop, width: 300, height: 20 })
      scrollIntoView.mockImplementation(() => {
        container().scrollTop = 1050 - 300
      })

      fire('textlayerrendered', { pageNumber: 2, source: { div: page } })
      fireEvent.scroll(container())
      fire('updateviewarea', {})
      expect(screen.getByText('Seite 2 von 3')).toBeInTheDocument()

      // zooming keeps the place: pdf.js scrolls, but the passage's page stays the current one
      Object.defineProperty(pdf.viewer!, 'currentScale', {
        get: () => 1,
        set: () => {
          container().scrollTop = 800
        },
      })
      await userEvent.click(screen.getByRole('button', { name: 'Vergrößern' }))
      fireEvent.scroll(container())
      fire('updateviewarea', {})
      expect(screen.getByText('Seite 2 von 3')).toBeInTheDocument()

      // scrolled by a script or the browser's search, without an input on the view
      container().scrollTop = 2480
      fireEvent.scroll(container())
      fire('updateviewarea', {})
      expect(screen.getByText('Seite 3 von 3')).toBeInTheDocument()
    })

    it('reaches the last page and its entry at the end, though they never scroll up far', async () => {
      pdf.pages = [0, 1, 2].map(() => ({ left: 0, width: 600, height: 300 }))
      pdf.outline = [
        { title: 'Anfang', dest: at(1, 0, 300), items: [] },
        { title: 'Schluss', dest: at(3, 0, 100), items: [] },
      ]
      await open(undefined as unknown as ChunkAnchor)

      wheelTo(380)

      expect(screen.getByText('Seite 3 von 3')).toBeInTheDocument()
      expect(await entry('Schluss')).toHaveAttribute('aria-current', 'location')
    })

    it('centres a page that fits and aligns a wider one left, unless the target lies beyond', async () => {
      await open(undefined as unknown as ChunkAnchor)

      await userEvent.click(await entry('Inhalt'))
      expect(container().scrollLeft).toBe(200)

      await userEvent.click(await entry('Doppelseite'))
      expect(container().scrollLeft).toBe(0)

      // the target would lie right of the view: shown with a margin, the spread's end at the edge
      await userEvent.click(await entry('Rechte Seite'))
      expect(container().scrollLeft).toBe(400)
    })

    it('opens a passage on a narrow page with the whole page in view', async () => {
      await open({ pageFrom: 2, pageTo: 2, quoteStart: 'Das Modul indexing' })
      const page = renderedPage(['Das Modul indexing verwaltet die Dokumentenaufnahme'])
      sized(page.querySelector('span')!, { left: 400, top: 200, width: 300, height: 20 })

      fire('textlayerrendered', { pageNumber: 2, source: { div: page } })

      expect(screen.getByRole('status')).toHaveTextContent('Fundstelle auf Seite 2 markiert.')
      expect(container().scrollLeft).toBe(200)
    })
  })

  describe('opening scale', () => {
    const originalMatchMedia = window.matchMedia
    afterEach(() => {
      window.matchMedia = originalMatchMedia
    })

    it('opens in page width, no larger than the opening bound on a wide screen', async () => {
      pdf.pageWidthScale = 2.4
      await open({ pageFrom: 1 })
      fire('pagesinit', {})
      expect(pdf.viewer!.currentScale).toBe(1.6)
    })

    it('keeps a readable minimum on a phone and offers only paging and zoom there', async () => {
      setViewportWidth(360)
      pdf.pageWidthScale = 0.42
      await open({ pageFrom: 1 })
      fire('pagesinit', {})
      expect(pdf.viewer!.currentScale).toBe(0.7)
      const tools = screen.getByRole('toolbar', { name: 'Ansicht' })
      expect(
        within(tools)
          .getAllByRole('button')
          .map((b) => b.getAttribute('aria-label')),
      ).toEqual(['Vorherige Seite', 'Nächste Seite', 'Verkleinern', 'Vergrößern'])
    })
  })
})

function rect(box: { left: number; top: number; width: number; height: number }): DOMRect {
  return {
    ...box,
    right: box.left + box.width,
    bottom: box.top + box.height,
    x: box.left,
    y: box.top,
    toJSON: () => box,
  } as DOMRect
}

function sized(
  element: Element,
  box: { left: number; top: number; width: number; height: number },
) {
  element.getBoundingClientRect = () => rect(box)
}
