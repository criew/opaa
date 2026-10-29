import { useEffect, useEffectEvent, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import FitWidthIcon from '@mui/icons-material/WidthFull'
import TocIcon from '@mui/icons-material/Toc'
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from 'pdfjs-dist'
import type { PDFViewer as PdfJsViewer } from 'pdfjs-dist/web/pdf_viewer.mjs'
import 'pdfjs-dist/web/pdf_viewer.css'
import type { ChunkAnchor } from '@/shared/api/types'
import { markHighlight } from '@/shared/theme/tokens'
import { loadPdf, loadPdfViewer } from '../utils/pdfjs'
import { markedItems } from '../utils/quoteMatch'
import { sha256Hex } from '../utils/sha256'
import PdfOutline, { type OutlineNode } from './PdfOutline'

/** Pages searched for the quote when the anchor names none (an outline PDF whose page was lost). */
const SEARCH_PAGE_LIMIT = 50

const ZOOM_STEP = 1.25
const MIN_SCALE = 0.25
const MAX_SCALE = 5

export interface PdfViewerProps {
  data: ArrayBuffer
  anchor?: ChunkAnchor | null
  /** SHA-256 of the original as indexed when the citation was made; compared with `data`. */
  contentSha256?: string | null
  /** pdf.js could not open or render the file - the caller falls back to the browser viewer. */
  onError: (error: unknown) => void
}

type MarkState = 'none' | 'pending' | 'marked' | 'not-found'

interface Passage {
  from: number
  to: number
}

/**
 * The PDF view behind „Dokument öffnen“: pdf.js's own viewer, scrolling continuously from page to
 * page, with the outline as a collapsible table of contents beside it. It opens on the anchor's
 * first page and marks the passage between its quotes in the text layer of every page it spans;
 * without pages in the anchor it searches the first pages for the start quote. A quote it cannot
 * find leaves the page unmarked and says so, as does an original whose checksum differs from the
 * indexed one. Annotations and forms stay off and no scripting manager is wired in, so an original
 * runs no JavaScript.
 */
export default function PdfViewer({ data, anchor, contentSha256, onError }: PdfViewerProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<PdfJsViewer | null>(null)
  const [pdf, setPdf] = useState<PDFDocumentProxy | null>(null)
  const [outline, setOutline] = useState<OutlineNode[]>([])
  const [outlineOpen, setOutlineOpen] = useState(true)
  const [page, setPage] = useState(1)
  const [markState, setMarkState] = useState<MarkState>(anchor?.quoteStart ? 'pending' : 'none')
  const [changed, setChanged] = useState(false)
  const reportError = useEffectEvent((error: unknown) => onError(error))

  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    let cancelled = false
    let task: PDFDocumentLoadingTask | null = null

    void (async () => {
      try {
        const [loading, { pdfjs, viewer: pdfjsViewer }] = await Promise.all([
          loadPdf(data),
          loadPdfViewer(),
        ])
        task = loading
        if (cancelled) return
        const document = await loading.promise
        if (cancelled) return

        const eventBus = new pdfjsViewer.EventBus()
        const linkService = new pdfjsViewer.PDFLinkService({ eventBus })
        const viewer = new pdfjsViewer.PDFViewer({
          container,
          eventBus,
          linkService,
          annotationMode: pdfjs.AnnotationMode.DISABLE,
          textLayerMode: 1,
        })
        linkService.setViewer(viewer)
        viewerRef.current = viewer

        const passage = await passageOf(document, anchor)
        if (cancelled) return
        if (anchor?.quoteStart && passage === null) setMarkState('not-found')

        eventBus.on('pagesinit', () => {
          viewer.currentScaleValue = 'auto'
          if (passage) viewer.scrollPageIntoView({ pageNumber: passage.from })
        })
        eventBus.on('pagechanging', ({ pageNumber }: { pageNumber: number }) => setPage(pageNumber))
        eventBus.on(
          'textlayerrendered',
          ({ pageNumber, source }: { pageNumber: number; source: { div: HTMLElement } }) => {
            if (!passage || !anchor?.quoteStart) return
            if (pageNumber < passage.from || pageNumber > passage.to) return
            const startsHere = pageNumber === passage.from
            const spans = textSpans(source.div)
            const range = markedItems(
              spans.map((span) => span.textContent ?? ''),
              anchor.quoteStart,
              anchor.quoteEnd,
              { startsHere, endsHere: pageNumber === passage.to },
            )
            if (range) {
              for (let i = range[0]; i <= range[1]; i++) spans[i].classList.add('passage')
              if (startsHere) {
                spans[range[0]].scrollIntoView?.({ block: 'center' })
                setMarkState('marked')
              }
            } else if (startsHere) {
              setMarkState('not-found')
            }
          },
        )

        viewer.setDocument(document)
        linkService.setDocument(document)
        setPdf(document)
        const items = (await document.getOutline()) as PdfJsOutlineItem[] | null
        const nodes = await toOutline(document, items ?? [])
        if (!cancelled) setOutline(nodes)
      } catch (error) {
        if (!cancelled) reportError(error)
      }
    })()

    return () => {
      cancelled = true
      viewerRef.current = null
      void task?.destroy()
    }
  }, [data, anchor])

  useEffect(() => {
    if (!contentSha256 || !anchor) return
    let cancelled = false
    void sha256Hex(data).then((actual) => {
      if (!cancelled) setChanged(actual !== contentSha256.toLowerCase())
    })
    return () => {
      cancelled = true
    }
  }, [data, contentSha256, anchor])

  function zoom(factor: number) {
    const viewer = viewerRef.current
    if (!viewer) return
    viewer.currentScale = Math.min(MAX_SCALE, Math.max(MIN_SCALE, viewer.currentScale * factor))
  }

  function fitWidth() {
    if (viewerRef.current) viewerRef.current.currentScaleValue = 'page-width'
  }

  function goTo(node: OutlineNode) {
    const viewer = viewerRef.current
    if (!viewer || node.dest == null) return
    void viewer.linkService.goToDestination(node.dest)
  }

  const numPages = pdf?.numPages ?? 0
  const hasOutline = outline.length > 0
  return (
    <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1, flex: 1, minHeight: 0 }}>
      <Box
        role="toolbar"
        aria-label="Ansicht"
        sx={{ display: 'flex', alignItems: 'center', gap: 0.5, flexWrap: 'wrap' }}
      >
        {hasOutline && (
          <Tooltip
            title={outlineOpen ? 'Inhaltsverzeichnis ausblenden' : 'Inhaltsverzeichnis einblenden'}
          >
            <IconButton
              size="small"
              aria-label="Inhaltsverzeichnis"
              aria-expanded={outlineOpen}
              aria-controls="pdf-outline"
              onClick={() => setOutlineOpen((open) => !open)}
            >
              <TocIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        <Typography component="span" sx={{ fontSize: 14, mx: 1 }} aria-live="polite">
          {numPages > 0 ? `Seite ${page} von ${numPages}` : 'Lädt …'}
        </Typography>
        <Tooltip title="Verkleinern">
          <IconButton size="small" aria-label="Verkleinern" onClick={() => zoom(1 / ZOOM_STEP)}>
            <RemoveIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Vergrößern">
          <IconButton size="small" aria-label="Vergrößern" onClick={() => zoom(ZOOM_STEP)}>
            <AddIcon fontSize="small" />
          </IconButton>
        </Tooltip>
        <Tooltip title="Seitenbreite">
          <IconButton size="small" aria-label="Seitenbreite" onClick={fitWidth}>
            <FitWidthIcon fontSize="small" />
          </IconButton>
        </Tooltip>
      </Box>
      {(markState === 'not-found' || changed) && (
        <Typography role="status" sx={{ fontSize: 13, color: 'warning.main', fontWeight: 600 }}>
          {changed
            ? 'Das Original wurde seit der Antwort geändert – die Markierung kann abweichen.'
            : 'Die Fundstelle wurde im Original nicht gefunden – angezeigt wird die Fundseite.'}
        </Typography>
      )}
      <Box sx={{ display: 'flex', flex: 1, minHeight: 0, gap: 1 }}>
        {hasOutline && outlineOpen && (
          <Box
            id="pdf-outline"
            component="nav"
            aria-label="Inhaltsverzeichnis"
            sx={{
              width: 260,
              flex: 'none',
              overflow: 'auto',
              borderRight: 1,
              borderColor: 'divider',
              pr: 1,
            }}
          >
            <PdfOutline nodes={outline} activeNode={activeNode(outline, page)} onSelect={goTo} />
          </Box>
        )}
        <Box sx={{ position: 'relative', flex: 1, minWidth: 0, bgcolor: 'action.hover' }}>
          {!pdf && (
            <CircularProgress
              aria-label="PDF wird geladen"
              sx={{ position: 'absolute', top: 32, left: '50%' }}
            />
          )}
          <Box
            ref={containerRef}
            data-testid="pdf-container"
            sx={{
              position: 'absolute',
              inset: 0,
              overflow: 'auto',
              '& .textLayer .passage': {
                backgroundColor: markHighlight,
                boxShadow: `0 0 0 2px ${markHighlight}`,
                borderRadius: '2px',
              },
            }}
          >
            <div className="pdfViewer" />
          </Box>
        </Box>
      </Box>
    </Box>
  )
}

/** The last entry, in reading order, whose page is at or before `page` - the section shown. */
function activeNode(nodes: OutlineNode[], page: number): OutlineNode | null {
  let active: OutlineNode | null = null
  const visit = (list: OutlineNode[]) => {
    for (const node of list) {
      if (node.page !== null && node.page <= page) active = node
      visit(node.children)
    }
  }
  visit(nodes)
  return active
}

/** The text items of a rendered page's text layer, in reading order. */
function textSpans(pageDiv: HTMLElement): HTMLElement[] {
  return Array.from(pageDiv.querySelectorAll<HTMLElement>('.textLayer span')).filter(
    (span) => !span.classList.contains('markedContent') && span.getAttribute('role') !== 'img',
  )
}

/** The pages the anchor's passage spans, clamped to the document; searched when it names none. */
async function passageOf(
  pdf: PDFDocumentProxy,
  anchor: ChunkAnchor | null | undefined,
): Promise<Passage | null> {
  let from = anchor?.pageFrom ?? null
  if (from === null && anchor?.quoteStart) from = await searchStartPage(pdf, anchor.quoteStart)
  if (from === null) return null
  const first = Math.min(Math.max(from, 1), pdf.numPages)
  const last = Math.min(Math.max(anchor?.pageTo ?? first, first), pdf.numPages)
  return { from: first, to: last }
}

/** The first page whose text holds `quote`, within {@link SEARCH_PAGE_LIMIT}; `null` without one. */
async function searchStartPage(pdf: PDFDocumentProxy, quote: string): Promise<number | null> {
  const last = Math.min(pdf.numPages, SEARCH_PAGE_LIMIT)
  for (let page = 1; page <= last; page++) {
    const content = await (await pdf.getPage(page)).getTextContent()
    const items = content.items.map((item) => ('str' in item ? item.str : ''))
    if (markedItems(items, quote, null, { startsHere: true, endsHere: false }) !== null) {
      return page
    }
  }
  return null
}

interface PdfJsOutlineItem {
  title: string
  dest: string | unknown[] | null
  items: PdfJsOutlineItem[]
}

/** The outline with the page each entry points at, where its destination resolves to one. */
async function toOutline(pdf: PDFDocumentProxy, items: PdfJsOutlineItem[]): Promise<OutlineNode[]> {
  return Promise.all(
    items.map(async (item) => ({
      title: item.title,
      dest: item.dest,
      page: await pageOfDestination(pdf, item.dest),
      children: await toOutline(pdf, item.items ?? []),
    })),
  )
}

async function pageOfDestination(
  pdf: PDFDocumentProxy,
  dest: string | unknown[] | null,
): Promise<number | null> {
  try {
    const explicit = typeof dest === 'string' ? await pdf.getDestination(dest) : dest
    const target = explicit?.[0]
    if (typeof target === 'number') return target + 1
    if (target && typeof target === 'object') {
      return (await pdf.getPageIndex(target as Parameters<PDFDocumentProxy['getPageIndex']>[0])) + 1
    }
  } catch {
    // an unresolvable destination only loses the entry's page, not the entry
  }
  return null
}
