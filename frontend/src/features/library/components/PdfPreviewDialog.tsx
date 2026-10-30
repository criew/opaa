import { lazy, Suspense, useEffect, useState, type ComponentType } from 'react'
import type { PdfViewerProps } from './PdfViewer'
import Button from '@mui/material/Button'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogContent from '@mui/material/DialogContent'
import Typography from '@mui/material/Typography'
import DialogTitleWithClose from '@/shared/ui/DialogTitleWithClose'
import type { PdfPreviewResult } from '../utils/documentContent'
import { downloadBlob, openInBrowserViewer } from '../utils/documentContent'
import DownloadOriginalButton from './DownloadOriginalButton'

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
 * the passage marked (ADR-0062 anchor). When pdf.js cannot show the file, the dialog says so and
 * offers the browser's own viewer - behind a click, since a popup opened that long after the
 * user's click is blocked.
 */
export default function PdfPreviewDialog({ preview, onClose }: PdfPreviewDialogProps) {
  const [data, setData] = useState<{ blob: Blob; buffer: ArrayBuffer } | null>(null)
  const [failedBlob, setFailedBlob] = useState<Blob | null>(null)

  useEffect(() => {
    if (!preview) return
    let cancelled = false
    void preview.blob.arrayBuffer().then((buffer) => {
      if (!cancelled) setData({ blob: preview.blob, buffer })
    })
    return () => {
      cancelled = true
      // a closed dialog holds no copy of the file
      setData(null)
    }
  }, [preview])

  function openInBrowser() {
    if (preview) openInBrowserViewer(preview.blob)
    onClose()
  }

  const failed = preview != null && failedBlob === preview.blob
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
      <DialogTitleWithClose
        id={TITLE_ID}
        onClose={onClose}
        actions={
          preview && (
            <DownloadOriginalButton
              onDownload={() => downloadBlob(preview.blob, preview.fileName)}
            />
          )
        }
      >
        <Typography component="span" sx={{ fontWeight: 600, wordBreak: 'break-word' }}>
          {preview?.fileName}
        </Typography>
      </DialogTitleWithClose>
      <DialogContent dividers sx={{ display: 'flex', flexDirection: 'column', minHeight: 0 }}>
        {failed ? (
          <Box
            role="alert"
            sx={{ display: 'flex', flexDirection: 'column', gap: 2, alignItems: 'flex-start' }}
          >
            <Typography>Dieses PDF kann hier nicht angezeigt werden.</Typography>
            <Button variant="contained" onClick={openInBrowser}>
              Im Browser öffnen
            </Button>
          </Box>
        ) : buffer ? (
          <Suspense fallback={<CircularProgress aria-label="Anzeige wird geladen" />}>
            <PdfViewer
              data={buffer}
              anchor={preview?.anchor}
              contentSha256={preview?.contentSha256}
              onError={() => setFailedBlob(preview?.blob ?? null)}
            />
          </Suspense>
        ) : (
          <CircularProgress aria-label="PDF wird geladen" />
        )}
      </DialogContent>
    </Dialog>
  )
}
