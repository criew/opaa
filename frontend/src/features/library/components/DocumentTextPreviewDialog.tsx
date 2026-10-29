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
import { passageRange } from '../utils/textPassage'

/** Name of the CSS Custom Highlight that marks the cited passage. */
const PASSAGE_HIGHLIGHT = 'opaa-passage'

type MarkState = 'none' | 'marked' | 'not-found'

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
 * Security (mirrors the SVG Sperre in documentContent.ts for a different attack surface):
 * Markdown renders through {@link MarkdownRenderer}, which never enables `rehype-raw` - any literal
 * `<script>`/`<img onerror=...>` in the source text is therefore parsed as inert text, not HTML, and
 * link/image URLs pass through react-markdown's default `urlTransform`, which strips dangerous
 * protocols like `javascript:`. Plain text never goes through Markdown parsing at all - it renders as
 * a single React text node, which React itself escapes.
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
  // touching the rendered DOM; a browser without it still scrolls to the passage. A ref callback,
  // so it runs once the content is in the DOM and again for each newly previewed document.
  const markPassage = useCallback(
    (content: HTMLElement | null) => {
      const anchor = previewDocument?.anchor
      if (!content || !previewDocument || !anchor?.quoteStart) return
      const range = passageRange(content, anchor)
      const highlights = typeof CSS !== 'undefined' ? CSS.highlights : undefined
      if (range) {
        if (highlights && typeof Highlight !== 'undefined') {
          highlights.set(PASSAGE_HIGHLIGHT, new Highlight(range))
        }
        range.startContainer.parentElement?.scrollIntoView?.({ block: 'center' })
      }
      setMark({ of: previewDocument, state: range ? 'marked' : 'not-found' })
      return () => {
        highlights?.delete(PASSAGE_HIGHLIGHT)
      }
    },
    [previewDocument],
  )

  const markState = mark?.of === previewDocument ? mark.state : 'none'
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
        styles={{ [`::highlight(${PASSAGE_HIGHLIGHT})`]: { backgroundColor: markHighlight } }}
      />
      <Typography
        role="status"
        sx={
          markState === 'not-found'
            ? { px: 3, pb: 1, fontSize: 13, color: 'warning.main', fontWeight: 600 }
            : visuallyHidden
        }
      >
        {markState === 'not-found'
          ? 'Die Fundstelle wurde im Dokument nicht gefunden.'
          : markState === 'marked'
            ? 'Fundstelle markiert.'
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
