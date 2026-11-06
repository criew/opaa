import { useEffect, useEffectEvent, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import AddIcon from '@mui/icons-material/Add'
import RemoveIcon from '@mui/icons-material/Remove'
import FitWidthIcon from '@mui/icons-material/WidthFull'
import TocIcon from '@mui/icons-material/Toc'
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from 'pdfjs-dist'
import type { PDFViewer as PdfJsViewer } from 'pdfjs-dist/web/pdf_viewer.mjs'
import 'pdfjs-dist/web/pdf_viewer.css'
import type { ChunkAnchor, ChunkRegion } from '@/shared/api/types'
import { blue, markHighlight } from '@/shared/theme/tokens'
import MetaBadge from '@/shared/ui/MetaBadge'
import { currentUiLabVariant } from '@/shared/uiLabVariant'
import { loadPdf, loadPdfViewer } from '../utils/pdfjs'
import { containsQuote, markedItems } from '../utils/quoteMatch'
import { sha256Hex } from '../utils/sha256'
import { NO_ANCHOR_NOTICE } from '../utils/textPassage'
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

type MarkState = 'none' | 'pending' | 'marked' | 'not-found' | 'no-anchor' | 'framed' | 'page-only'

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
  // UI lab (epic #2640): only a lab variant reads the frames and the OCR origin of the anchor
  const variant = currentUiLabVariant()
  const ocr = variant !== null && anchor?.textOrigin != null
  const regions = ocr ? (anchor?.regions ?? []) : []
  const [markState, setMarkState] = useState<MarkState>(
    anchor === null
      ? 'no-anchor'
      : ocr
        ? regions.length > 0
          ? 'pending'
          : 'page-only'
        : anchor?.quoteStart
          ? 'pending'
          : 'none',
  )
  const [changed, setChanged] = useState(false)
  const reportError = useEffectEvent((error: unknown) => onError(error))

  useEffect(() => {
    const container = containerRef.current
    if (!container) return
    let cancelled = false
    let task: PDFDocumentLoadingTask | null = null
    const abort = new AbortController()
    let teardown = () => {}

    void (async () => {
      try {
        const [loading, { pdfjs, viewer: pdfjsViewer }] = await Promise.all([
          loadPdf(data),
          loadPdfViewer(),
        ])
        task = loading
        if (cancelled) {
          void loading.destroy()
          return
        }
        const document = await loading.promise
        if (cancelled) return

        const eventBus = new pdfjsViewer.EventBus()
        const linkService = new pdfjsViewer.PDFLinkService({ eventBus })
        // abortSignal is honoured by PDFViewer but missing from its typings
        const viewer = new pdfjsViewer.PDFViewer({
          container,
          eventBus,
          linkService,
          annotationMode: pdfjs.AnnotationMode.DISABLE,
          textLayerMode: 1,
          abortSignal: abort.signal,
        } as ConstructorParameters<typeof pdfjsViewer.PDFViewer>[0])
        linkService.setViewer(viewer)
        viewerRef.current = viewer
        // Detaching the document cancels every text layer, which pdf.js otherwise keeps in a static
        // registry together with its page - each closed viewer would stay in memory.
        teardown = () => {
          viewer.setDocument(null)
          linkService.setDocument(null)
        }

        const passage = await passageOf(document, anchor)
        if (cancelled) return
        if (!ocr && anchor?.quoteStart && passage === null) setMarkState('not-found')
        let jumped = false

        // A scan has no text layer: the frames of the anchor are drawn over the rendered page,
        // in page-relative units, so they follow zoom; pdf.js empties the page div on re-render.
        if (ocr) {
          eventBus.on(
            'pagerendered',
            ({ pageNumber, source }: { pageNumber: number; source: { div: HTMLElement } }) => {
              const own = regions.filter((region) => region.page === pageNumber)
              if (own.length === 0) return
              drawRegions(source.div, own, variant)
              if (!jumped && pageNumber === regions[0].page) {
                jumped = true
                source.div
                  .querySelector<HTMLElement>('.ocr-region')
                  ?.scrollIntoView?.({ block: 'center' })
                setMarkState('framed')
              }
            },
          )
        }

        eventBus.on('pagesinit', () => {
          viewer.currentScaleValue = 'auto'
          if (passage) viewer.scrollPageIntoView({ pageNumber: passage.from })
        })
        eventBus.on('pagechanging', ({ pageNumber }: { pageNumber: number }) => setPage(pageNumber))
        eventBus.on(
          'textlayerrendered',
          ({ pageNumber, source }: { pageNumber: number; source: { div: HTMLElement } }) => {
            if (ocr || !passage || !anchor?.quoteStart) return
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
              // a text layer renders again on zoom and when its page scrolls back into view - the
              // passage stays marked, but the view jumps to it only once
              if (startsHere && !jumped) {
                jumped = true
                spans[range[0]].scrollIntoView?.({ block: 'center' })
                setMarkState('marked')
              }
            } else if (startsHere && !jumped) {
              jumped = true
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
      teardown()
      abort.abort()
      void task?.destroy()
    }
    // `ocr`, `regions` and `variant` follow from `anchor` and the page's address
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, anchor])

  useEffect(() => {
    if (!contentSha256 || !anchor) return
    let cancelled = false
    // without a secure context (plain-HTTP intranet) there is no crypto.subtle: the check is
    // skipped then, which reads as "unchanged", not as "changed"
    if (!globalThis.crypto?.subtle) return
    sha256Hex(data).then(
      (actual) => {
        if (!cancelled) setChanged(actual !== contentSha256.toLowerCase())
      },
      () => {},
    )
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
              aria-controls={outlineOpen ? 'pdf-outline' : undefined}
              onClick={() => setOutlineOpen((open) => !open)}
            >
              <TocIcon fontSize="small" />
            </IconButton>
          </Tooltip>
        )}
        <Typography component="span" sx={{ fontSize: 14, mx: 1 }}>
          {numPages > 0 ? `Seite ${page} von ${numPages}` : 'Lädt …'}
        </Typography>
        {ocr && variant === 'B' && (
          <Tooltip title={OCR_NOTICE}>
            <Box component="span" tabIndex={0} sx={{ mx: 1, borderRadius: 1 }}>
              <MetaBadge ariaLabel={OCR_NOTICE}>Texterkennung</MetaBadge>
            </Box>
          </Tooltip>
        )}
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
      <Typography
        role="status"
        sx={
          changed || markState === 'not-found'
            ? { fontSize: 13, color: 'warning.main', fontWeight: 600 }
            : markState === 'no-anchor' ||
                markState === 'page-only' ||
                (ocr && variant === 'A' && markState === 'framed')
              ? { fontSize: 13, color: 'text.secondary' }
              : visuallyHidden
        }
      >
        {ocr
          ? ocrStatusText(markState, changed, anchor?.pageFrom ?? page, variant)
          : statusText(markState, changed, anchor?.pageFrom != null, page)}
      </Typography>
      <Box
        sx={{
          display: 'flex',
          flexDirection: { xs: ocr && variant === 'C' ? 'column' : 'row', sm: 'row' },
          flex: 1,
          minHeight: 0,
          gap: 1,
        }}
      >
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
        <Box
          sx={{
            position: 'relative',
            flex: 1,
            minWidth: 0,
            minHeight: 160,
            bgcolor: 'action.hover',
          }}
        >
          {!pdf && (
            <CircularProgress
              aria-label="PDF wird geladen"
              sx={{ position: 'absolute', top: 32, left: '50%' }}
            />
          )}
          <Box
            ref={containerRef}
            data-testid="pdf-container"
            tabIndex={0}
            role="region"
            aria-label="Seiten"
            sx={{
              position: 'absolute',
              inset: 0,
              overflow: 'auto',
              '& .textLayer .passage': {
                backgroundColor: markHighlight,
                boxShadow: `0 0 0 2px ${markHighlight}`,
                borderRadius: '2px',
              },
              ...REGION_SX,
            }}
          >
            <div className="pdfViewer" />
          </Box>
        </Box>
        {ocr && variant === 'C' && <RecognizedTextPanel anchor={anchor} />}
      </Box>
    </Box>
  )
}

const OCR_NOTICE = 'Text per Texterkennung gelesen, Abweichungen vom Original möglich.'

/** The frames of the three lab variants: A a plain frame, B with a tab, C as a spotlight. */
const REGION_SX = {
  '& .ocr-region': {
    position: 'absolute',
    zIndex: 3,
    pointerEvents: 'none',
    boxSizing: 'border-box',
    borderRadius: '3px',
  },
  '& .ocr-region[data-variant="A"]': {
    border: `3px solid ${blue[700]}`,
    backgroundColor: 'rgba(29, 78, 216, 0.08)',
  },
  '& .ocr-region[data-variant="B"]': {
    border: `2px dashed ${blue[700]}`,
    backgroundColor: markHighlight,
  },
  '& .ocr-region[data-variant="B"] .ocr-region-tab': {
    position: 'absolute',
    left: -2,
    bottom: '100%',
    px: 0.75,
    py: 0.125,
    fontSize: 12,
    fontWeight: 600,
    lineHeight: 1.4,
    color: '#FFFFFF',
    backgroundColor: blue[700],
    borderRadius: '3px 3px 0 0',
    whiteSpace: 'nowrap',
  },
  '& .ocr-region[data-variant="C"]': {
    border: `2px solid ${blue[700]}`,
    boxShadow: '0 0 0 4000px rgba(15, 23, 42, 0.38)',
  },
  '& .page[data-ocr-spotlight]': { overflow: 'hidden' },
} as const

/** Puts the frames of one page into its rendered page div, once per render. */
function drawRegions(pageDiv: HTMLElement, regions: ChunkRegion[], variant: string | null) {
  if (pageDiv.querySelector('.ocr-region')) return
  if (variant === 'C') pageDiv.setAttribute('data-ocr-spotlight', '')
  for (const region of regions) {
    const frame = document.createElement('div')
    frame.className = 'ocr-region'
    frame.setAttribute('aria-hidden', 'true')
    frame.dataset.variant = variant ?? 'A'
    frame.style.left = `${region.x * 100}%`
    frame.style.top = `${region.y * 100}%`
    frame.style.width = `${region.width * 100}%`
    frame.style.height = `${region.height * 100}%`
    if (variant === 'B') {
      const tab = document.createElement('span')
      tab.className = 'ocr-region-tab'
      tab.textContent = 'Fundstelle'
      frame.appendChild(tab)
    }
    pageDiv.appendChild(frame)
  }
}

function ocrStatusText(
  markState: MarkState,
  changed: boolean,
  page: number,
  variant: string | null,
): string {
  if (changed) return 'Das Original wurde seit der Antwort geändert – der Rahmen kann abweichen.'
  if (markState === 'page-only') {
    return `Fundstelle auf Seite ${page}. Dieser Scan wurde ohne Rahmen erkannt, die Stelle ist nicht markiert.`
  }
  if (markState !== 'framed') return ''
  return variant === 'A'
    ? `Fundstelle auf Seite ${page} umrahmt. ${OCR_NOTICE}`
    : `Fundstelle auf Seite ${page} umrahmt.`
}

/** Variant C: the recognized text beside the scan, so the reader compares both. */
function RecognizedTextPanel({ anchor }: { anchor: ChunkAnchor | null | undefined }) {
  if (!anchor) return null
  const quote = [anchor.quoteStart, anchor.quoteEnd].filter(Boolean).join(' … ')
  return (
    <Box
      component="aside"
      aria-label="Erkannter Text"
      sx={{
        width: { xs: 'auto', sm: 220, md: 300 },
        maxHeight: { xs: '40%', sm: 'none' },
        flex: 'none',
        overflow: 'auto',
        borderLeft: { xs: 0, sm: 1 },
        borderTop: { xs: 1, sm: 0 },
        borderColor: 'divider',
        pl: { xs: 0, sm: 1.5 },
        pt: { xs: 1, sm: 0 },
      }}
    >
      <Typography component="h3" sx={{ fontSize: 13, fontWeight: 600, mb: 0.5 }}>
        Erkannter Text
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', mb: 1 }}>
        Seite {anchor.pageFrom ?? '–'}
        {anchor.path?.length ? ` · ${anchor.path[anchor.path.length - 1]}` : ''}
      </Typography>
      <Typography
        component="blockquote"
        sx={{
          m: 0,
          pl: 1.25,
          borderLeft: 3,
          borderColor: 'divider',
          fontSize: 13.5,
          overflowWrap: 'anywhere',
        }}
      >
        {quote}
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', mt: 1.5 }}>
        Per Texterkennung gelesen. Vergleichen Sie Beträge und Paragrafen mit dem Scan.
      </Typography>
    </Box>
  )
}

/** What the status line says: warnings visibly, a found passage for screen readers only. */
function statusText(markState: MarkState, changed: boolean, pageKnown: boolean, page: number) {
  if (changed)
    return 'Das Original wurde seit der Antwort geändert – die Markierung kann abweichen.'
  if (markState === 'not-found') {
    return pageKnown
      ? 'Die Fundstelle wurde im Original nicht gefunden – angezeigt wird die Fundseite.'
      : 'Die Fundstelle wurde im Original nicht gefunden.'
  }
  if (markState === 'no-anchor') return NO_ANCHOR_NOTICE
  return markState === 'marked' ? `Fundstelle auf Seite ${page} markiert.` : ''
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
    const pdfPage = await pdf.getPage(page)
    const content = await pdfPage.getTextContent()
    pdfPage.cleanup()
    const items = content.items.map((item) => ('str' in item ? item.str : ''))
    if (containsQuote(items, quote)) return page
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
