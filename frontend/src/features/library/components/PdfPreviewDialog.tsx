import { lazy, Suspense, useEffect, useState, type ComponentType } from 'react'
import type { PdfViewerProps } from './PdfViewer'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import Typography from '@mui/material/Typography'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import type { PdfPreviewResult } from '../utils/documentContent'
import { openInBrowserViewer } from '../utils/documentContent'

// pdf.js and the viewer load only when a PDF is opened, never with the page that offers it; a
// chunk that fails to load falls back to the browser viewer like a file pdf.js cannot open
const PdfViewer = lazy<ComponentType<PdfViewerProps>>(() =>
  import('./PdfViewer').catch(() => ({ default: ViewerUnavailable })),
)

function ViewerUnavailable({ onError }: PdfViewerProps) {
  useEffect(() => onError(new Error('PDF viewer could not be loaded')), [onError])
  return null
}

const TITLE_ID = 'pdf-preview-title'

interface PdfPreviewDialogProps {
  /** `null` keeps the dialog closed. */
  preview: PdfPreviewResult | null
  onClose: () => void
}

/**
 * „Dokument öffnen“ for a PDF: the original in the app's own viewer, opened on the cited page with
 * the passage marked (ADR-0062 anchor). When pdf.js cannot show the file, the dialog closes and the
 * browser's own viewer opens it in a new tab, as before.
 */
export default function PdfPreviewDialog({ preview, onClose }: PdfPreviewDialogProps) {
  const [data, setData] = useState<{ blob: Blob; buffer: ArrayBuffer } | null>(null)

  useEffect(() => {
    if (!preview) return
    let cancelled = false
    void preview.blob.arrayBuffer().then((buffer) => {
      if (!cancelled) setData({ blob: preview.blob, buffer })
    })
    return () => {
      cancelled = true
    }
  }, [preview])

  function fallBack() {
    if (preview) openInBrowserViewer(preview.blob)
    onClose()
  }

  const buffer = preview && data?.blob === preview.blob ? data.buffer : null
  return (
    <Dialog
      open={preview != null}
      onClose={onClose}
      maxWidth="xl"
      fullWidth
      aria-labelledby={TITLE_ID}
      slotProps={{ paper: { sx: { height: '90vh' } } }}
    >
      <DialogTitleWithClose id={TITLE_ID} onClose={onClose}>
        <Typography component="span" sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
          {preview?.fileName}
        </Typography>
      </DialogTitleWithClose>
      <DialogContent dividers sx={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        {buffer ? (
          <Suspense fallback={<CircularProgress aria-label="Anzeige wird geladen" />}>
            <PdfViewer
              data={buffer}
              anchor={preview?.anchor}
              contentSha256={preview?.contentSha256}
              onError={fallBack}
            />
          </Suspense>
        ) : (
          <CircularProgress aria-label="PDF wird geladen" />
        )}
      </DialogContent>
    </Dialog>
  )
}
