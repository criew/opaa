import Box from '@mui/material/Box'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import Typography from '@mui/material/Typography'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import MarkdownRenderer from '@/shared/ui/markdown/MarkdownRenderer'
import type { TextPreviewResult } from '../utils/documentContent'
import { fontFamily } from '@/shared/theme/tokens'

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
      <DialogContent dividers>
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
