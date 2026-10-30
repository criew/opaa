import { useCallback, useState } from 'react'
import Box from '@mui/material/Box'
import GlobalStyles from '@mui/material/GlobalStyles'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import MarkdownRenderer from '@/shared/ui/markdown/MarkdownRenderer'
import type { TextPreviewResult } from '../utils/documentContent'
import { fontFamily, markHighlight } from '@/shared/theme/tokens'
import { NO_ANCHOR_NOTICE, passageRange } from '../utils/textPassage'

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
  const [mark, setMark] = useState<{ of: TextPreviewResult; state: MarkState } | null>(null)

  // The cited passage is marked with the CSS Custom Highlight API, which paints a Range without
  // touching the rendered DOM. A ref callback, so it runs once the content is in the DOM and again
  // for each newly previewed document; later re-renders of the Markdown (link targets loading)
  // replace text nodes, so a mutation marks the passage anew - the view jumps to it only once.
  const markPassage = useCallback(
    (content: HTMLElement | null) => {
      const anchor = previewDocument?.anchor
      if (!content || !previewDocument || !anchor?.quoteStart) return
      const highlights = typeof CSS !== 'undefined' ? CSS.highlights : undefined
      let jumped = false
      const apply = () => {
        const range = passageRange(content, anchor)
        let highlighted = false
        if (range && highlights && typeof Highlight !== 'undefined') {
          highlights.set(PASSAGE_HIGHLIGHT, new Highlight(range))
          highlighted = true
        } else {
          highlights?.delete(PASSAGE_HIGHLIGHT)
        }
        if (range && !jumped) {
          jumped = true
          scrollToPassage(content, range)
        }
        setMark({
          of: previewDocument,
          state: !range ? 'not-found' : highlighted ? 'marked' : 'scrolled',
        })
      }
      apply()
      const observer =
        typeof MutationObserver !== 'undefined' ? new MutationObserver(apply) : undefined
      observer?.observe(content, { childList: true, subtree: true, characterData: true })
      return () => {
        observer?.disconnect()
        highlights?.delete(PASSAGE_HIGHLIGHT)
      }
    },
    [previewDocument],
  )

  const markState =
    previewDocument?.anchor === null
      ? 'no-anchor'
      : mark?.of === previewDocument
        ? mark.state
        : 'none'
  return (
    <Dialog
      open={previewDocument != null}
      onClose={onClose}
      maxWidth="md"
      fullWidth
      scroll="paper"
      aria-labelledby={TITLE_ID}
    >
      <DialogTitleWithClose id={TITLE_ID} onClose={onClose}>
        <Typography component="span" sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
          {previewDocument?.fileName}
        </Typography>
      </DialogTitleWithClose>
      <GlobalStyles
        styles={{
          [`::highlight(${PASSAGE_HIGHLIGHT})`]: { backgroundColor: markHighlight },
          '@media (forced-colors: active)': {
            [`::highlight(${PASSAGE_HIGHLIGHT})`]: { backgroundColor: 'Mark', color: 'MarkText' },
          },
        }}
      />
      <Typography
        role="status"
        sx={
          markState === 'not-found'
            ? { px: 3, pb: 1, fontSize: 13, color: 'warning.main', fontWeight: 600 }
            : markState === 'no-anchor'
              ? { px: 3, pb: 1, fontSize: 13, color: 'text.secondary' }
              : visuallyHidden
        }
      >
        {markState === 'not-found'
          ? 'Die Fundstelle wurde im Dokument nicht gefunden.'
          : markState === 'no-anchor'
            ? NO_ANCHOR_NOTICE
            : markState === 'marked'
              ? 'Fundstelle markiert.'
              : markState === 'scrolled'
                ? 'Zur Fundstelle gescrollt.'
                : ''}
      </Typography>
      <DialogContent dividers ref={markPassage}>
        {previewDocument?.contentType === 'text/markdown' ? (
          <MarkdownRenderer content={previewDocument.content} preserveCitationMarkers />
        ) : (
          <Box
            component="pre"
            sx={{
              m: 0,
              fontFamily: fontFamily.mono,
              fontSize: '0.875rem',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {previewDocument?.content}
          </Box>
        )}
      </DialogContent>
    </Dialog>
  )
}

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
