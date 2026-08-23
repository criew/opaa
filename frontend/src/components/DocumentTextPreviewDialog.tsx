import Box from '@mui/material/Box'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
import MarkdownRenderer from './chat/MarkdownRenderer'
import type { TextPreviewResult } from '../utils/documentContent'

interface DocumentTextPreviewDialogProps {
  /** `null` keeps the dialog closed (mirrors MUI's own `open` pattern used elsewhere in this app -
   *  see EditLibrarySourceDialog) - the last previewed document's content stays out of the DOM once
   *  closed rather than lingering hidden. */
  document: TextPreviewResult | null
  onClose: () => void
}

/**
 * #780: the client-side preview for Markdown/plain text originals ("Im Dokument öffnen" on a `.md`/
 * `.txt` Fundstelle) - a `text/markdown` or `text/plain` blob would otherwise render as raw,
 * unformatted text or trigger a silent download depending on the browser, neither of which is the
 * "Beleg bis ins Original prüfen" moment the ticket asks for.
 *
 * Security (#780 acceptance criteria, mirrors the #743 SVG Sperre for a different attack surface):
 * Markdown renders through {@link MarkdownRenderer}, which never enables `rehype-raw` - any literal
 * `<script>`/`<img onerror=...>` in the source text is therefore parsed as inert text, not HTML, and
 * link/image URLs pass through react-markdown's default `urlTransform`, which strips dangerous
 * protocols like `javascript:`. Plain text never goes through Markdown parsing at all - it renders as
 * a single React text node, which React itself escapes.
 */
export default function DocumentTextPreviewDialog({
  document,
  onClose,
}: DocumentTextPreviewDialogProps) {
  return (
    <Dialog open={document != null} onClose={onClose} maxWidth="md" fullWidth scroll="paper">
      <DialogTitle
        sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}
      >
        <Typography component="span" sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
          {document?.fileName}
        </Typography>
        <IconButton
          size="small"
          onClick={onClose}
          aria-label="Vorschau schließen"
          sx={{ flex: 'none' }}
        >
          <CloseIcon fontSize="small" />
        </IconButton>
      </DialogTitle>
      <DialogContent dividers>
        {document?.contentType === 'text/markdown' ? (
          <MarkdownRenderer content={document.content} />
        ) : (
          <Box
            component="pre"
            sx={{
              m: 0,
              fontFamily: 'monospace',
              fontSize: '0.875rem',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-word',
            }}
          >
            {document?.content}
          </Box>
        )}
      </DialogContent>
    </Dialog>
  )
}
