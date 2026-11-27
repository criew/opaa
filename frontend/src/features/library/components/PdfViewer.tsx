import { useEffect, useEffectEvent, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import type { SxProps, Theme } from '@mui/material/styles'
import type { PDFDocumentLoadingTask, PDFDocumentProxy } from 'pdfjs-dist/legacy/build/pdf.mjs'
import type { PDFViewer as PdfJsViewer } from 'pdfjs-dist/legacy/web/pdf_viewer.mjs'
import 'pdfjs-dist/legacy/web/pdf_viewer.css'
import type { ChunkAnchor } from '@/shared/api/types'
import { fontSize, fontWeight, radius, shadow } from '@/shared/theme/tokens'
import { isRecognizedText, RECOGNIZED_TEXT_NOTICE } from '@/shared/utils/recognizedText'
import { loadPdf, loadPdfViewer } from '../utils/pdfjs'
import { containsQuote, markedItems } from '../utils/quoteMatch'
import { sha256Hex } from '../utils/sha256'
import { NO_ANCHOR_NOTICE } from '../utils/textPassage'
import { drawRegions, framedPages, REGION_CLASS, regionsOnPage } from '../utils/recognizedRegions'
import PdfOutline, { type OutlineNode } from './PdfOutline'
import {
  horizontalOffset,
  lastAtOrBefore,
  readingLine,
  useUserScroll,
} from './documentView/readingPosition'
import RecognizedTextPanel from './RecognizedTextPanel'
import LoadingState from '@/shared/ui/states/LoadingState'
import useFullScreenDialog from '@/shared/ui/dialog/useFullScreenDialog'
import ReadingLayout from './documentView/ReadingLayout'
import PdfTools from './documentView/PdfTools'
import { FindingPanel } from './documentView/FindingCard'
import { anchorPlace, anchorQuote } from './documentView/findingText'
import {
  drawPassageMarks,
  labelFrame,
  PASSAGE_BAND_CLASS,
  PASSAGE_EDGE_CLASS,
  PASSAGE_LABEL_CLASS,
} from './documentView/passageMarks'

/** Pages searched for the quote when the anchor names none (an outline PDF whose page was lost). */
const SEARCH_PAGE_LIMIT = 50

const ZOOM_STEP = 1.25
const MIN_SCALE = 0.25
const MAX_SCALE = 5
/** A PDF opens in page width, but no larger than this on a wide screen. */
const MAX_OPENING_SCALE = 1.6
/** On a phone, page width would shrink the text below reading size: it opens at least this large. */
const PHONE_MIN_SCALE = 0.7

const RECOGNIZED_TEXT_PANEL_ID = 'pdf-recognized-text'

export interface PdfViewerProps {
  data: ArrayBuffer
  anchor?: ChunkAnchor | null
  /** SHA-256 of the original as indexed when the citation was made; compared with `data`. */
  contentSha256?: string | null
  /** pdf.js could not open or render the file - the caller falls back to the browser viewer. */
  onError: (error: unknown) => void
  /** The cited document and passage; a scan's side panel shows the passage's recognized text. */
  documentId?: string | null
  chunkIndex?: number | null
}

/** `framed` and `page-only` belong to a scan: frames drawn, or only its page opened. */
type MarkState = 'none' | 'pending' | 'marked' | 'not-found' | 'no-anchor' | 'framed' | 'page-only'

interface Passage {
  from: number
  to: number
}

/** Where an outline entry leads: its page and pdf.js's explicit destination there. */
interface OutlineTarget {
  node: OutlineNode
  pageNumber: number
  destArray: unknown[]
}

/** An outline entry with its destination resolved, where it resolves. */
interface ResolvedNode extends OutlineNode {
  children: ResolvedNode[]
  destination: { pageNumber: number; destArray: unknown[] } | null
}

/** The page and the outline entry at the reading line; both shown as current. */
interface Reading {
  page: number
  node: OutlineNode | null
}

/**
 * The PDF view behind „Dokument öffnen“: pdf.js's own viewer, scrolling continuously from page to
 * page, with the outline as a collapsible table of contents beside it. It opens on the anchor's
 * first page and marks the passage between its quotes in the text layer of every page it spans;
 * without pages in the anchor it searches the first pages for the start quote. A quote it cannot
 * find leaves the page unmarked and says so, as does an original whose checksum differs from the
 * indexed one. Annotations and forms stay off and no scripting manager is wired in, so an original
 * runs no JavaScript.
 *
 * A scan read by text recognition has no text layer to search: its anchor's frames are drawn over
 * the unchanged original on every page they touch, and the view scrolls to the first one. Without
 * frames (a vision model) it opens the page and says the passage is not framed. Beside the scan,
 * „Erkannter Text“ shows what was read.
 */
export default function PdfViewer({
  data,
  anchor,
  contentSha256,
  onError,
  documentId,
  chunkIndex,
}: PdfViewerProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const viewerRef = useRef<PdfJsViewer | null>(null)
  const [pdf, setPdf] = useState<PDFDocumentProxy | null>(null)
  const [outline, setOutline] = useState<OutlineNode[]>([])
  const targetsRef = useRef<OutlineTarget[]>([])
  const [outlineOpen, setOutlineOpen] = useState(true)
  const [reading, setReading] = useState<Reading>({ page: 1, node: null })
  // the entry just chosen stays the current one until the reader scrolls on their own
  const [chosen, setChosen] = useState<OutlineTarget | null>(null)
  const forgetInput = useUserScroll(containerRef, () => setChosen(null))
  const [scale, setScale] = useState(1)
  // the page the passage starts on; the status names it, not the page scrolled to since
  const [markedPage, setMarkedPage] = useState(1)
  const firstMarkRef = useRef<HTMLElement | null>(null)
  const phone = useFullScreenDialog()
  const phoneRef = useRef(phone)
  useEffect(() => {
    phoneRef.current = phone
  }, [phone])
  const recognized = isRecognizedText(anchor)
  const [recognizedOpen, setRecognizedOpen] = useState(true)
  const [markState, setMarkState] = useState<MarkState>(() => initialMarkState(anchor))
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

        const scan = isRecognizedText(anchor)
        let frames = scan ? framedPages(anchor) : null
        // frames beyond the original's last page belong to another file: open the page only
        if (frames && frames.from > document.numPages) {
          frames = null
          setMarkState('page-only')
        }
        const firstFramed = frames?.from
        const passage = scan
          ? scanPassage(document, anchor, frames)
          : await passageOf(document, anchor)
        if (cancelled) return
        if (!scan && anchor?.quoteStart && passage === null) setMarkState('not-found')
        let jumped = false

        eventBus.on('pagesinit', () => {
          viewer.currentScaleValue = 'page-width'
          const fitted = viewer.currentScale
          if (phoneRef.current && fitted < PHONE_MIN_SCALE) viewer.currentScale = PHONE_MIN_SCALE
          else if (fitted > MAX_OPENING_SCALE) viewer.currentScale = MAX_OPENING_SCALE
          if (passage) {
            viewer.scrollPageIntoView({ pageNumber: passage.from })
            alignPage(container, viewer, passage.from)
          }
        })
        if (frames) {
          eventBus.on(
            'pagerendered',
            ({ pageNumber, source }: { pageNumber: number; source: { div: HTMLElement } }) => {
              const first = drawRegions(source.div, regionsOnPage(anchor, pageNumber))
              if (first && pageNumber === firstFramed) {
                labelFrame(first)
                firstMarkRef.current = first
              }
              // drawn again after every zoom step, scrolled to only once
              if (first && pageNumber === firstFramed && !jumped) {
                jumped = true
                first.scrollIntoView?.({ block: 'center' })
                alignPage(container, viewer, pageNumber, first)
                setMarkState('framed')
              }
            },
          )
        }
        // pdf.js's own current page is the most visible one, which with pages of different widths
        // is often not the one at the top: page and outline entry are measured at the reading line
        eventBus.on('updateviewarea', () => {
          const next = readingAt(container, viewer, targetsRef.current)
          setReading((previous) =>
            previous.page === next.page && previous.node === next.node ? previous : next,
          )
        })
        eventBus.on('scalechanging', ({ scale: next }: { scale: number }) => setScale(next))
        eventBus.on(
          'textlayerrendered',
          ({ pageNumber, source }: { pageNumber: number; source: { div: HTMLElement } }) => {
            if (scan || !passage || !anchor?.quoteStart) return
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
              const band = drawPassageMarks(source.div, spans.slice(range[0], range[1] + 1), {
                label: startsHere,
              })
              if (startsHere) firstMarkRef.current = band ?? spans[range[0]]
              // a text layer renders again on zoom and when its page scrolls back into view - the
              // passage stays marked, but the view jumps to it only once
              if (startsHere && !jumped) {
                jumped = true
                spans[range[0]].scrollIntoView?.({ block: 'center', inline: 'nearest' })
                alignPage(container, viewer, pageNumber, spans[range[0]])
                setMarkedPage(pageNumber)
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
        if (cancelled) return
        const targets = outlineTargets(nodes)
        targetsRef.current = targets
        setOutline(nodes)
        setReading(readingAt(container, viewer, targets))
      } catch (error) {
        if (!cancelled) reportError(error)
      }
    })()

    return () => {
      cancelled = true
      viewerRef.current = null
      targetsRef.current = []
      teardown()
      abort.abort()
      void task?.destroy()
    }
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
    const container = containerRef.current
    const target = targetsRef.current.find((candidate) => candidate.node === node)
    if (!viewer || !container || !target) return
    forgetInput()
    viewer.scrollPageIntoView({ pageNumber: target.pageNumber, destArray: target.destArray })
    alignPage(container, viewer, target.pageNumber, destinationX(viewer, target))
    setChosen(target)
  }

  function goToPage(next: number) {
    const viewer = viewerRef.current
    const container = containerRef.current
    if (!viewer || !container) return
    forgetInput()
    setChosen(null)
    viewer.currentPageNumber = next
    alignPage(container, viewer, next)
  }

  function jumpToPassage() {
    const mark = firstMarkRef.current
    const viewer = viewerRef.current
    const container = containerRef.current
    if (!mark || !viewer || !container) return
    forgetInput()
    setChosen(null)
    mark.scrollIntoView?.({ block: 'center', inline: 'nearest' })
    const pageNumber = Number(mark.closest<HTMLElement>('.page')?.dataset.pageNumber)
    if (pageNumber) alignPage(container, viewer, pageNumber, mark)
  }

  const page = chosen?.pageNumber ?? reading.page
  const currentNode = chosen ? chosen.node : reading.node

  const numPages = pdf?.numPages ?? 0
  const hasOutline = outline.length > 0
  const status = {
    text: recognized
      ? scanStatusText(markState, changed, anchor)
      : statusText(markState, changed, anchor?.pageFrom != null, markedPage),
    warning: changed || markState === 'not-found',
  }
  const canJump = markState === 'marked' || markState === 'framed'
  const showOutline = hasOutline && outlineOpen
  const showRecognized = recognized && recognizedOpen && anchor != null
  return (
    <ReadingLayout
      finding={
        anchor !== undefined
          ? { place: anchorPlace(anchor), quote: recognized ? '' : anchorQuote(anchor) }
          : null
      }
      status={status}
      onJump={canJump ? jumpToPassage : undefined}
      panels={
        showOutline || showRecognized ? (
          <>
            {showOutline && (
              <FindingPanel>
                <Box
                  id="pdf-outline"
                  component="nav"
                  aria-label="Inhaltsverzeichnis"
                  sx={{ mx: -1 }}
                >
                  <PdfOutline nodes={outline} activeNode={currentNode} onSelect={goTo} />
                </Box>
              </FindingPanel>
            )}
            {showRecognized && (
              <FindingPanel>
                <RecognizedTextPanel
                  id={RECOGNIZED_TEXT_PANEL_ID}
                  anchor={anchor}
                  documentId={documentId}
                  chunkIndex={chunkIndex}
                />
              </FindingPanel>
            )}
          </>
        ) : undefined
      }
    >
      <Box sx={{ position: 'relative', flex: 1, minWidth: 0, minHeight: 160 }}>
        {!pdf && (
          <Box sx={{ position: 'absolute', inset: 0, p: 2 }}>
            <LoadingState shape="block" />
          </Box>
        )}
        <Box
          ref={containerRef}
          data-testid="pdf-container"
          tabIndex={0}
          role="region"
          aria-label="Seiten"
          sx={pagesSx}
        >
          <div className="pdfViewer" />
        </Box>
        <PdfTools
          page={page}
          numPages={numPages}
          scale={scale}
          onPage={goToPage}
          onZoomIn={() => zoom(ZOOM_STEP)}
          onZoomOut={() => zoom(1 / ZOOM_STEP)}
          onFitWidth={fitWidth}
          hasOutline={hasOutline}
          outlineOpen={outlineOpen}
          outlineId="pdf-outline"
          onToggleOutline={() => setOutlineOpen((open) => !open)}
          recognized={recognized}
          recognizedOpen={recognizedOpen}
          recognizedId={RECOGNIZED_TEXT_PANEL_ID}
          onToggleRecognized={() => setRecognizedOpen((open) => !open)}
        />
      </Box>
    </ReadingLayout>
  )
}

/**
 * The pages on the desk, and the marks on them: bands multiplied onto the page, so the text
 * stays dark on them, the Mittelblau edge, the label; a scan's frames in the same colours.
 */
const pagesSx: SxProps<Theme> = (theme) => {
  const { band, edge, edgeFg } = theme.palette.documentView
  return {
    position: 'absolute',
    inset: 0,
    overflow: 'auto',
    '--page-margin': '16px auto',
    '--page-border': 'none',
    '& .pdfViewer': { pb: 10 },
    '& .pdfViewer .page': { boxShadow: shadow.floating, borderRadius: '2px' },
    // the spans only carry the passage for the search; the bands below draw it
    '& .textLayer .passage': { backgroundColor: 'transparent' },
    [`& .${PASSAGE_BAND_CLASS}`]: {
      position: 'absolute',
      zIndex: 1,
      pointerEvents: 'none',
      backgroundColor: band,
      mixBlendMode: 'multiply',
      borderRadius: '3px',
    },
    [`& .${PASSAGE_EDGE_CLASS}`]: {
      position: 'absolute',
      zIndex: 1,
      pointerEvents: 'none',
      backgroundColor: edge,
      borderRadius: '2px',
    },
    [`& .${REGION_CLASS}`]: {
      position: 'absolute',
      zIndex: 3,
      pointerEvents: 'none',
      boxSizing: 'border-box',
      border: `2px solid ${edge}`,
      borderRadius: '4px',
      backgroundColor: band,
      mixBlendMode: 'multiply',
    },
    [`& .${PASSAGE_LABEL_CLASS}`]: {
      position: 'absolute',
      left: 0,
      bottom: '100%',
      mb: 0.5,
      whiteSpace: 'nowrap',
      backgroundColor: edge,
      color: edgeFg,
      fontFamily: theme.typography.fontFamily,
      fontSize: fontSize.label,
      fontWeight: fontWeight.semibold,
      lineHeight: '16px',
      px: 1,
      borderRadius: `${radius.xs}px`,
      '&[data-place="margin"]': {
        left: 'auto',
        right: '100%',
        bottom: 'auto',
        top: 0,
        mb: 0,
        mr: 1,
      },
    },
    '@media (forced-colors: active)': {
      [`& .${PASSAGE_BAND_CLASS}`]: {
        backgroundColor: 'Mark',
        mixBlendMode: 'normal',
        opacity: 0.5,
      },
      [`& .${PASSAGE_EDGE_CLASS}`]: { backgroundColor: 'Highlight', forcedColorAdjust: 'none' },
      [`& .${REGION_CLASS}`]: { borderColor: 'Highlight', backgroundColor: 'transparent' },
      [`& .${PASSAGE_LABEL_CLASS}`]: {
        backgroundColor: 'Highlight',
        color: 'HighlightText',
        forcedColorAdjust: 'none',
      },
    },
  }
}

function initialMarkState(anchor: ChunkAnchor | null | undefined): MarkState {
  if (anchor === null) return 'no-anchor'
  if (isRecognizedText(anchor)) return framedPages(anchor) ? 'pending' : 'page-only'
  return anchor?.quoteStart ? 'pending' : 'none'
}

/** A scan's status line, visible: the reader compares frame and recognized text with the scan. */
function scanStatusText(
  markState: MarkState,
  changed: boolean,
  anchor: ChunkAnchor | null | undefined,
): string {
  if (changed) {
    return markState === 'page-only'
      ? 'Das Original wurde seit der Antwort geändert – die Fundseite kann abweichen.'
      : 'Das Original wurde seit der Antwort geändert – der Rahmen kann abweichen.'
  }
  const frames = framedPages(anchor)
  if (markState === 'framed' && frames) {
    const pages =
      frames.from === frames.to
        ? `auf Seite ${frames.from}`
        : `auf den Seiten ${frames.from}–${frames.to}`
    return `Fundstelle ${pages} umrahmt. ${RECOGNIZED_TEXT_NOTICE}.`
  }
  if (markState === 'page-only') {
    const where =
      anchor?.pageFrom != null ? `Fundstelle auf Seite ${anchor.pageFrom}, im Scan` : 'Im Scan'
    return `${where} nicht umrahmt. ${RECOGNIZED_TEXT_NOTICE}.`
  }
  return ''
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

/** The bounds of `element` in the scrolled content of `container`. */
function contentRect(element: Element, container: HTMLElement) {
  const box = element.getBoundingClientRect()
  const frame = container.getBoundingClientRect()
  const left = box.left - frame.left - container.clientLeft + container.scrollLeft
  const top = box.top - frame.top - container.clientTop + container.scrollTop
  return { left, top, right: left + box.width, width: box.width }
}

/** The page and the outline entry at the reading line of the view. */
function readingAt(container: HTMLElement, viewer: PdfJsViewer, targets: OutlineTarget[]): Reading {
  const count = viewer.pagesCount
  if (!count) return { page: 1, node: null }
  const line = readingLine(container)
  const tops = new Map<number, number>()
  const topOf = (pageNumber: number) => {
    let top = tops.get(pageNumber)
    if (top === undefined) {
      const div = viewer.getPageView(pageNumber - 1)?.div
      top = div ? contentRect(div, container).top : 0
      tops.set(pageNumber, top)
    }
    return top
  }
  // pages run top to bottom: the last one starting at or above the line holds it
  let low = 1
  let high = count
  while (low < high) {
    const middle = Math.ceil((low + high) / 2)
    if (topOf(middle) <= line) low = middle
    else high = middle - 1
  }
  const entries = targets.map((target) => ({
    node: target.node,
    position: topOf(target.pageNumber) + destinationY(viewer, target),
  }))
  return { page: low, node: lastAtOrBefore(entries, line)?.node ?? null }
}

/** How far down its page an outline entry points, in CSS pixels; the page top if it names none. */
function destinationY(viewer: PdfJsViewer, target: OutlineTarget): number {
  const view = viewer.getPageView(target.pageNumber - 1)
  if (!view) return 0
  const [, kind, ...args] = target.destArray as [unknown, { name?: string } | null, ...unknown[]]
  const name = kind?.name
  const y =
    name === 'XYZ'
      ? args[1]
      : name === 'FitH' || name === 'FitBH'
        ? args[0]
        : name === 'FitR'
          ? args[3]
          : null
  if (typeof y !== 'number') return 0
  const offset = view.viewport.convertToViewportPoint(0, y)[1]
  return Math.min(Math.max(offset, 0), view.viewport.height)
}

/** Where across its page an outline entry points, in CSS pixels; `null` if it names no place. */
function destinationX(viewer: PdfJsViewer, target: OutlineTarget): number | null {
  const view = viewer.getPageView(target.pageNumber - 1)
  const [, kind, x] = target.destArray as [unknown, { name?: string } | null, unknown]
  const name = kind?.name
  if (!view || typeof x !== 'number') return null
  if (name !== 'XYZ' && name !== 'FitR' && name !== 'FitV' && name !== 'FitBV') return null
  return view.viewport.convertToViewportPoint(x, 0)[0]
}

/**
 * Scrolls the view across so that page `pageNumber` shows as fully as it fits (see
 * {@link horizontalOffset}), keeping `target` - a mark on the page or a position across it - in
 * view. pdf.js would scroll a destination's left edge to the view's left edge instead, which on a
 * page narrower than the widest one cuts off what lies to its left.
 */
function alignPage(
  container: HTMLElement,
  viewer: PdfJsViewer,
  pageNumber: number,
  target?: HTMLElement | number | null,
) {
  const div = viewer.getPageView(pageNumber - 1)?.div
  if (!div) return
  const page = contentRect(div, container)
  let mark: { left: number; right: number } | null = null
  if (typeof target === 'number') mark = { left: page.left + target, right: page.left + target }
  else if (target) mark = contentRect(target, container)
  container.scrollLeft = horizontalOffset({
    pageLeft: page.left,
    pageWidth: page.width,
    viewWidth: container.clientWidth,
    target: mark,
  })
}

/** The text items of a rendered page's text layer, in reading order. */
function textSpans(pageDiv: HTMLElement): HTMLElement[] {
  return Array.from(pageDiv.querySelectorAll<HTMLElement>('.textLayer span')).filter(
    (span) => !span.classList.contains('markedContent') && span.getAttribute('role') !== 'img',
  )
}

/** The pages a scan opens at: those of its frames, else the anchor's; never searched. */
function scanPassage(
  pdf: PDFDocumentProxy,
  anchor: ChunkAnchor | null | undefined,
  frames: { from: number; to: number } | null,
): Passage | null {
  const from = frames?.from ?? anchor?.pageFrom
  if (from == null) return null
  const first = Math.min(Math.max(from, 1), pdf.numPages)
  const last = Math.min(Math.max(frames?.to ?? anchor?.pageTo ?? first, first), pdf.numPages)
  return { from: first, to: last }
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
async function toOutline(
  pdf: PDFDocumentProxy,
  items: PdfJsOutlineItem[],
): Promise<ResolvedNode[]> {
  return Promise.all(
    items.map(async (item) => {
      const [destination, children] = await Promise.all([
        resolveDestination(pdf, item.dest),
        toOutline(pdf, item.items ?? []),
      ])
      const node: ResolvedNode = {
        title: item.title,
        dest: item.dest,
        page: destination?.pageNumber ?? null,
        children,
        destination,
      }
      return node
    }),
  )
}

/** The resolved entries of `nodes` and everything below them, in reading order. */
function outlineTargets(nodes: ResolvedNode[], targets: OutlineTarget[] = []): OutlineTarget[] {
  for (const node of nodes) {
    if (node.destination) targets.push({ node, ...node.destination })
    outlineTargets(node.children, targets)
  }
  return targets
}

async function resolveDestination(
  pdf: PDFDocumentProxy,
  dest: string | unknown[] | null,
): Promise<{ pageNumber: number; destArray: unknown[] } | null> {
  try {
    const explicit = typeof dest === 'string' ? await pdf.getDestination(dest) : dest
    if (!explicit) return null
    const target = explicit[0]
    if (typeof target === 'number') return { pageNumber: target + 1, destArray: explicit }
    if (target && typeof target === 'object') {
      const index = await pdf.getPageIndex(
        target as Parameters<PDFDocumentProxy['getPageIndex']>[0],
      )
      return { pageNumber: index + 1, destArray: explicit }
    }
  } catch {
    // an unresolvable destination only loses the entry's page, not the entry
  }
  return null
}
