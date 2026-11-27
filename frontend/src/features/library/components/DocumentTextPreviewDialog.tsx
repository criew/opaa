import { useCallback, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import GlobalStyles from '@mui/material/GlobalStyles'
import MarkdownRenderer from '@/shared/ui/markdown/MarkdownRenderer'
import ReadingModeDialog from '@/shared/ui/dialog/ReadingModeDialog'
import type { TextPreviewResult } from '../utils/documentContent'
import { fontFamily, fontSize } from '@/shared/theme/tokens'
import { NO_ANCHOR_NOTICE, passageRange } from '../utils/textPassage'
import { downloadBlob } from '../utils/documentContent'
import DownloadOriginalButton from './DownloadOriginalButton'
import ReadingLayout from './documentView/ReadingLayout'
import DocumentPaper from './documentView/DocumentPaper'
import { deskScrollSx } from './documentView/styles'
import PassageLabel from './documentView/PassageLabel'
import { anchorPlace, anchorQuote } from './documentView/findingText'
import { highlightStyles, markPassageBlocks, passageBlockSx } from './documentView/textMarks'
import { backLabel } from './documentView/backLabel'
import { OutlinePanel, OutlineToggle, OutlineToolbar } from './documentView/OutlinePanel'
import {
  useHeadingNavigation,
  useOutlineOpen,
  useRenderedHeadings,
} from './documentView/useHeadingNavigation'

/** Name of the CSS Custom Highlight that marks the cited passage. */
const PASSAGE_HIGHLIGHT = 'opaa-passage'

type MarkState = 'none' | 'marked' | 'scrolled' | 'not-found' | 'no-anchor'

interface DocumentTextPreviewDialogProps {
  /** `null` keeps the dialog closed - the last previewed document's content stays out of the DOM once
   *  closed rather than lingering hidden. Named `previewDocument`, not `document` - the latter
   *  would shadow the global `document` inside this component's scope. */
  previewDocument: TextPreviewResult | null
  onClose: () => void
}

// MUI's Dialog does not wire `aria-labelledby` to its DialogTitle
// automatically - without an explicit, matching id pair, the dialog's accessible name falls back
// to nothing a screen reader can announce on open.
const TITLE_ID = 'document-preview-title'
const OUTLINE_ID = 'document-preview-outline'

/**
 * The client-side preview for Markdown/plain text originals ("Dokument öffnen" on a `.md`/
 * `.txt` Fundstelle) - a `text/markdown` or `text/plain` blob would otherwise render as raw,
 * unformatted text or trigger a silent download depending on the browser, neither of which is the
 * "Beleg bis ins Original prüfen" moment the ticket asks for.
 *
 * Security (mirrors the blob preview allowlist in documentContent.ts for a different attack surface):
 * Markdown renders through {@link MarkdownRenderer}, which never enables `rehype-raw` - any literal
 * `<script>`/`<img onerror=...>` in the source text is therefore parsed as inert text, not HTML.
 * URLs are kept as written; MarkdownRenderer's own link component makes only http(s) links to
 * allowed origins clickable (`javascript:` stays text) and its image component never loads an image.
 * Plain text never goes through Markdown parsing at all - it renders as a single React text node,
 * which React itself escapes.
 *
 * `preserveCitationMarkers`: this is a document's own original content, not a
 * chat answer - MarkdownRenderer's default citation-marker handling (stripping/resolving
 * `【source: …】` runs) does not apply here, and a coincidental such run in the source text must
 * render exactly as written.
 */
export default function DocumentTextPreviewDialog({
  previewDocument,
  onClose,
}: DocumentTextPreviewDialogProps) {
  const [mark, setMark] = useState<{
    of: TextPreviewResult
    state: MarkState
    block: HTMLElement | null
  } | null>(null)
  const [paper, setPaper] = useState<HTMLDivElement | null>(null)
  const scroller = useRef<{ content: HTMLElement; range: Range } | null>(null)

  // The cited passage is marked with the CSS Custom Highlight API, which paints a Range without
  // touching the rendered DOM; the blocks it runs through get the edge stroke by attribute. A ref
  // callback, so it runs once the content is in the DOM and again for each newly previewed
  // document; later re-renders of the Markdown (link targets loading) replace text nodes, so a
  // mutation marks the passage anew - the view jumps to it only once.
  const markPassage = useCallback(
    (content: HTMLElement | null) => {
      const anchor = previewDocument?.anchor
      if (!content || !previewDocument || !anchor?.quoteStart) return
      const highlights = typeof CSS !== 'undefined' ? CSS.highlights : undefined
      let jumped = false
      const apply = () => {
        observer?.disconnect()
        const body = content.querySelector<HTMLElement>('[data-document-body]') ?? content
        const range = passageRange(body, anchor)
        let highlighted = false
        if (range && highlights && typeof Highlight !== 'undefined') {
          highlights.set(PASSAGE_HIGHLIGHT, new Highlight(range))
          highlighted = true
        } else {
          highlights?.delete(PASSAGE_HIGHLIGHT)
        }
        const block = range ? markPassageBlocks(body, range) : null
        scroller.current = range ? { content, range } : null
        if (range && !jumped) {
          jumped = true
          scrollToPassage(content, range)
        }
        setMark({
          of: previewDocument,
          state: !range ? 'not-found' : highlighted ? 'marked' : 'scrolled',
          block,
        })
        observer?.observe(content, OBSERVED)
      }
      const observer =
        typeof MutationObserver !== 'undefined' ? new MutationObserver(apply) : undefined
      apply()
      return () => {
        observer?.disconnect()
        highlights?.delete(PASSAGE_HIGHLIGHT)
        scroller.current = null
      }
    },
    [previewDocument],
  )

  const [scrollBox, setScrollBox] = useState<HTMLDivElement | null>(null)
  const contentRef = useCallback(
    (content: HTMLDivElement | null) => {
      setScrollBox(content)
      const cleanup = markPassage(content)
      return () => {
        cleanup?.()
        setScrollBox(null)
      }
    },
    [markPassage],
  )

  // a Markdown original's outline: its headings, `#` to `######`
  const markdown = previewDocument?.contentType === 'text/markdown'
  const { nodes: outline, headings } = useRenderedHeadings(
    markdown ? (paper?.querySelector<HTMLElement>('[data-document-body]') ?? null) : null,
    'markdown',
    previewDocument,
  )
  const navigation = useHeadingNavigation(scrollBox, outline, headings)
  const outlineState = useOutlineOpen(outline.length > 0)

  const anchor = previewDocument?.anchor
  const current = mark?.of === previewDocument ? mark : null
  const markState = anchor === null ? 'no-anchor' : (current?.state ?? 'none')
  const status = {
    text:
      markState === 'not-found'
        ? 'Die Fundstelle wurde im Dokument nicht gefunden.'
        : markState === 'no-anchor'
          ? NO_ANCHOR_NOTICE
          : markState === 'marked'
            ? 'Fundstelle markiert.'
            : markState === 'scrolled'
              ? 'Zur Fundstelle gescrollt.'
              : '',
    warning: markState === 'not-found',
  }
  const canJump = markState === 'marked' || markState === 'scrolled'
  return (
    <ReadingModeDialog
      open={previewDocument != null}
      onClose={onClose}
      backLabel={backLabel(anchor)}
      titleId={TITLE_ID}
      title={previewDocument?.fileName}
      titleActions={
        previewDocument && (
          <DownloadOriginalButton
            onDownload={() => downloadBlob(previewDocument.blob, previewDocument.fileName)}
          />
        )
      }
    >
      <GlobalStyles styles={(theme) => highlightStyles(PASSAGE_HIGHLIGHT, theme)} />
      <ReadingLayout
        finding={
          anchor !== undefined ? { place: anchorPlace(anchor), quote: anchorQuote(anchor) } : null
        }
        status={status}
        onJump={
          canJump
            ? () => {
                if (scroller.current)
                  scrollToPassage(scroller.current.content, scroller.current.range)
              }
            : undefined
        }
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
          ref={contentRef}
          data-testid="document-text-content"
          role="region"
          aria-label="Dokument"
          tabIndex={0}
          sx={deskScrollSx}
        >
          <DocumentPaper paperRef={setPaper} sx={passageBlockSx}>
            <PassageLabel block={current?.block ?? null} container={paper} />
            <Box data-document-body="">
              {previewDocument?.contentType === 'text/markdown' ? (
                <MarkdownRenderer content={previewDocument.content} preserveCitationMarkers />
              ) : (
                <Box
                  component="pre"
                  sx={{
                    m: 0,
                    fontFamily: fontFamily.mono,
                    fontSize: fontSize.text,
                    whiteSpace: 'pre-wrap',
                    wordBreak: 'break-word',
                  }}
                >
                  {previewDocument?.content}
                </Box>
              )}
            </Box>
          </DocumentPaper>
        </Box>
      </ReadingLayout>
    </ReadingModeDialog>
  )
}

const OBSERVED: MutationObserverInit = { childList: true, subtree: true, characterData: true }

/**
 * Scrolls `content` so the passage starts in the middle of the view - by the range's own position,
 * since the element around it may be a whole `<pre>` - and moves focus to the block that holds it,
 * so a screen reader continues reading there.
 */
function scrollToPassage(content: HTMLElement, range: Range) {
  const rect = range.getBoundingClientRect?.()
  if (rect) {
    const top = content.getBoundingClientRect().top
    content.scrollTop += rect.top - top - content.clientHeight / 3
  }
  const block = range.startContainer.parentElement?.closest<HTMLElement>(BLOCKS)
  if (block && content.contains(block)) {
    if (!block.hasAttribute('tabindex')) block.setAttribute('tabindex', '-1')
    block.focus({ preventScroll: true })
  }
}

const BLOCKS = 'p, li, pre, td, th, blockquote, h1, h2, h3, h4, h5, h6'
