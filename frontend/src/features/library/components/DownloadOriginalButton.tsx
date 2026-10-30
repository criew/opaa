import { useState } from 'react'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import DownloadIcon from '@mui/icons-material/Download'
import { notify } from '@/shared/stores/notificationStore'

interface DownloadOriginalButtonProps {
  /** Saves the original; a rejection is shown as a notification. */
  onDownload: () => void | Promise<void>
  size?: 'small' | 'medium'
}

/**
 * „Original herunterladen“ as an icon button, the same in every view that shows a document: the
 * preview dialogs' title bar and the Belegfenster. Disabled while a fetch for it runs.
 */
export default function DownloadOriginalButton({
  onDownload,
  size = 'medium',
}: DownloadOriginalButtonProps) {
  const [busy, setBusy] = useState(false)

  async function download() {
    setBusy(true)
    try {
      await onDownload()
    } catch (err) {
      notify(
        err instanceof Error ? err.message : 'Das Original konnte nicht geladen werden.',
        'error',
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <Tooltip title="Original herunterladen">
      <span>
        <IconButton
          size={size}
          aria-label="Original herunterladen"
          disabled={busy}
          onClick={() => void download()}
        >
          {busy ? (
            <CircularProgress size={18} aria-label="Original wird geladen" />
          ) : (
            <DownloadIcon fontSize="small" />
          )}
        </IconButton>
      </span>
    </Tooltip>
  )
}
