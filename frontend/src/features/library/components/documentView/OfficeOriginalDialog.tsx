import { useCallback, useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import GlobalStyles from '@mui/material/GlobalStyles'
import ReadingModeDialog from '@/shared/ui/dialog/ReadingModeDialog'
import LoadingState from '@/shared/ui/states/LoadingState'
import type { PendingOriginal } from '../../hooks/useDocumentPreview'
import { downloadBlob } from '../../utils/documentContent'
import type { OfficeOriginal } from '../../utils/officeOriginal'
import { NO_ANCHOR_NOTICE, passageRange } from '../../utils/textPassage'
import DownloadOriginalButton from '../DownloadOriginalButton'
import { backLabel } from './backLabel'
import DocumentPaper from './DocumentPaper'
import { deskScrollSx } from './styles'
import { anchorPlace, anchorQuote } from './findingText'
import { CsvTableView, DocxView, MailView } from './OfficeOriginalViews'
import PassageLabel from './PassageLabel'
import ReadingLayout from './ReadingLayout'
import { OutlinePanel, OutlineToggle, OutlineToolbar } from './OutlinePanel'
import { useHeadingNavigation, useOutlineOpen, useRenderedHeadings } from './useHeadingNavigation'
import { highlightStyles, markPassageBlocks, passageBlockSx } from './textMarks'

const TITLE_ID = 'office-original-title'
const OUTLINE_ID = 'office-original-outline'
/** Name of the CSS Custom Highlight that marks the cited passage in a Word file or a mail. */
const HIGHLIGHT = 'opaa-original-passage'

interface Found {
  of: OfficeOriginal
  /** The first marked block or row, above which the label stands; `null` when not found. */
  block: HTMLElement | null
  /** Rows of a table found through the quote. */
  rows: number[]
}

/**
 * „Dokument öffnen“ for a Word file, a CSV table or a mail: the original as itself on light
 * paper, opened at the cited passage. A table marks the rows the anchor names (or that hold its
 * quote); a Word file and a mail mark the quote as a highlight with the edge beside its blocks. A
 * Word file docx-preview cannot read hands over to `onUnreadable`, which shows the Textfassung.
 */
export default function OfficeOriginalDialog({
  original,
  pending = null,
  onClose,
  onUnreadable,
}: {
  /** `null` keeps the dialog closed, unless an original is `pending`. */
  original: OfficeOriginal | null
  /** An original still being fetched: the dialog opens with its name and shows it loading. */
  pending?: PendingOriginal | null
  onClose: () => void
  onUnreadable: (original: OfficeOriginal) => void
}) {
  const scrollRef = useRef<HTMLDivElement | null>(null)
  const [scroller, setScroller] = useState<HTMLDivElement | null>(null)
  const scrollerRef = useCallback((element: HTMLDivElement | null) => {
    scrollRef.current = element
    setScroller(element)
  }, [])
  const [paper, setPaper] = useState<HTMLDivElement | null>(null)
  const [docxBody, setDocxBody] = useState<{ of: OfficeOriginal; body: HTMLElement } | null>(null)
  const [found, setFound] = useState<Found | null>(null)
  const anchor = original?.anchor
  const rowAnchored = original?.format === 'csv' && anchor?.rowFrom != null

  const body =
    original?.format === 'docx'
      ? docxBody?.of === original
        ? docxBody.body
        : null
      : (paper?.querySelector<HTMLElement>('[data-document-body]') ?? null)

  // a Word file's outline: its heading paragraphs, as the indexing recognizes them
  const { nodes: outline, headings } = useRenderedHeadings(
    original?.format === 'docx' ? body : null,
    'docx',
    original,
  )
  const navigation = useHeadingNavigation(scroller, outline, headings)
  const { follow } = navigation
  const outlineState = useOutlineOpen(outline.length > 0)

  // The passage is searched once the content is in the DOM: a table by the anchor's rows where it
  // names them, everything else by its quotes. A frame later, so the marks measure laid-out text.
  useEffect(() => {
    if (!original || !body || !anchor?.quoteStart || rowAnchored) return
    const highlights = typeof CSS !== 'undefined' ? CSS.highlights : undefined
    const frame = requestAnimationFrame(() => {
      const range = passageRange(body, anchor)
      if (!range) {
        setFound({ of: original, block: null, rows: [] })
        return
      }
      if (original.format === 'csv') {
        const rows = Array.from(body.querySelectorAll<HTMLElement>('tr[data-row]'))
          .filter((row) => range.intersectsNode(row))
          .map((row) => Number(row.dataset.row))
        setFound({ of: original, block: null, rows })
        return
      }
      if (highlights && typeof Highlight !== 'undefined') {
        highlights.set(HIGHLIGHT, new Highlight(range))
      }
      setFound({ of: original, block: markPassageBlocks(body, range), rows: [] })
    })
    return () => {
      cancelAnimationFrame(frame)
      highlights?.delete(HIGHLIGHT)
    }
  }, [original, body, anchor, rowAnchored])

  const current = found?.of === original ? found : null
  const markedRows = new Set<number>(
    rowAnchored && anchor?.rowFrom != null
      ? range(anchor.rowFrom, anchor.rowTo ?? anchor.rowFrom)
      : (current?.rows ?? []),
  )
  const rowBlock =
    original?.format === 'csv' && markedRows.size > 0
      ? (body?.querySelector<HTMLElement>(`tr[data-row="${Math.min(...markedRows)}"]`) ?? null)
      : null
  const labelBlock = original?.format === 'csv' ? rowBlock : (current?.block ?? null)
  const located =
    original?.format === 'csv' ? rowBlock != null : current != null && current.block != null
  const missing =
    anchor != null &&
    (original?.format === 'csv'
      ? body != null && rowBlock == null && (rowAnchored || current != null)
      : current != null && current.block == null)

  // the view opens at the passage once it is marked
  const jumped = useRef<OfficeOriginal | null>(null)
  const jump = useCallback(() => {
    const scroller = scrollRef.current
    if (!scroller || !labelBlock) return
    const top = labelBlock.getBoundingClientRect().top - scroller.getBoundingClientRect().top
    scroller.scrollTo?.({ top: scroller.scrollTop + top - scroller.clientHeight / 3 })
    if (!labelBlock.hasAttribute('tabindex')) labelBlock.setAttribute('tabindex', '-1')
    labelBlock.focus({ preventScroll: true })
    follow(labelBlock)
  }, [labelBlock, follow])
  useEffect(() => {
    if (!original || !labelBlock || jumped.current === original) return
    jumped.current = original
    jump()
  }, [original, labelBlock, jump])

  const status = {
    text:
      anchor === null
        ? NO_ANCHOR_NOTICE
        : missing
          ? 'Die Fundstelle wurde im Dokument nicht gefunden.'
          : located
            ? 'Fundstelle markiert.'
            : '',
    warning: missing,
  }
  const firstRow = rowBlock ? Number(rowBlock.dataset.row) : null
  const place =
    anchorPlace(anchor) ?? (firstRow != null && !rowAnchored ? `Zeile ${firstRow}` : null)

  return (
    <ReadingModeDialog
      open={original != null || pending != null}
      onClose={onClose}
      backLabel={backLabel(original ? anchor : pending?.anchor)}
      titleId={TITLE_ID}
      title={original?.fileName ?? pending?.fileName}
      titleActions={
        original && (
          <DownloadOriginalButton
            onDownload={() => downloadBlob(original.blob, original.fileName)}
          />
        )
      }
    >
      <GlobalStyles styles={(theme) => highlightStyles(HIGHLIGHT, theme)} />
      {!original && (
        <Box sx={{ p: 3 }}>
          <LoadingState shape="block" />
        </Box>
      )}
      {original && (
        <ReadingLayout
          finding={
            anchor !== undefined
              ? { place, quote: anchorQuote(anchor, original?.format === 'csv') }
              : null
          }
          status={status}
          onJump={located ? jump : undefined}
          panels={
            outlineState.open ? (
              <OutlinePanel
                id={OUTLINE_ID}
                nodes={outline}
                activeNode={navigation.active}
                onSelect={navigation.goTo}
              />
            ) : undefined
          }
        >
          {outlineState.toggleable && (
            <OutlineToolbar>
              <OutlineToggle
                open={outlineState.open}
                controls={OUTLINE_ID}
                onToggle={outlineState.toggle}
              />
            </OutlineToolbar>
          )}
          <Box
            ref={scrollerRef}
            data-testid="office-original-content"
            role="region"
            aria-label="Dokument"
            tabIndex={0}
            sx={deskScrollSx}
          >
            {original?.format === 'docx' ? (
              <Box sx={{ position: 'relative', pt: { sm: 1 } }} ref={setPaper}>
                <PassageLabel block={labelBlock} container={paper} />
                <DocxView
                  blob={original.blob}
                  highlight={HIGHLIGHT}
                  onRendered={(rendered) => setDocxBody({ of: original, body: rendered })}
                  onFailed={() => onUnreadable(original)}
                />
              </Box>
            ) : original ? (
              <DocumentPaper
                paperRef={setPaper}
                wide={original.format === 'csv'}
                sx={passageBlockSx}
              >
                {original.format === 'mail' && (
                  <PassageLabel block={labelBlock} container={paper} />
                )}
                <Box data-document-body="">
                  {original.format === 'csv' ? (
                    <CsvTableView table={original.table} marked={markedRows} />
                  ) : (
                    <MailView mail={original.mail} />
                  )}
                </Box>
              </DocumentPaper>
            ) : null}
          </Box>
        </ReadingLayout>
      )}
    </ReadingModeDialog>
  )
}

function range(from: number, to: number): number[] {
  const out: number[] = []
  for (let row = from; row <= Math.max(from, to) && out.length < 10_000; row++) out.push(row)
  return out
}
