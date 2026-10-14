import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ContentCopyOutlinedIcon from '@mui/icons-material/ContentCopyOutlined'
import type { LocalUserResponse, MailDeliveryPath } from '@/shared/api/types'
import { fontFamily, radius } from '@/shared/theme/tokens'
import { MAIL_DELIVERY_PATH_TEXT, SHOWN_ONCE_HINT } from './localUserLabels'
import { copyOnce } from '@/shared/utils/copyOnce'

export interface SetupLinkHandover {
  user: LocalUserResponse
  url: string
  deliveryPath?: MailDeliveryPath
  /** Which act produced the link - an invitation sets the first password, a reset replaces it. */
  kind: 'INVITE' | 'RESET'
}

/**
 * The one-time display of an invitation or reset link (ADR-0033 Entscheidung 11).
 * It appears only when the mail did not go out; the delivery path is stated as a sentence,
 * so that a handover outside the system stays distinguishable from a delivery.
 */
export default function SetupLinkDialog({
  handover,
  onClose,
}: {
  handover: SetupLinkHandover | null
  onClose: () => void
}) {
  if (!handover) return null
  const { user, url, deliveryPath, kind } = handover
  const title =
    kind === 'INVITE'
      ? `Einladungslink für „${user.displayName}“`
      : `Link zum Zurücksetzen für „${user.displayName}“`

  return (
    <Dialog
      open
      fullWidth
      maxWidth="sm"
      aria-labelledby="setup-link-title"
      // Without `onClose` neither a click outside nor Escape closes it: both would destroy the
      // value that exists exactly once. It is closed only
      // via the button.
    >
      <DialogTitle id="setup-link-title">{title}</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
          {user.email}
        </Typography>
        {deliveryPath && (
          <Alert severity="warning" sx={{ mb: 2 }}>
            {MAIL_DELIVERY_PATH_TEXT[deliveryPath]}
          </Alert>
        )}
        <Box
          data-testid="setup-link-value"
          sx={{
            fontFamily: fontFamily.mono,
            fontSize: 12.5,
            p: 1.25,
            border: 1,
            borderColor: 'divider',
            borderRadius: `${radius.sm}px`,
            overflowWrap: 'anywhere',
          }}
        >
          {url}
        </Box>
        {url.startsWith('/') && (
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
            Dies ist ein Pfad relativ zur Installation, weil keine öffentliche Basis-URL gesetzt ist
            – setzen Sie die Adresse Ihrer Installation davor.
          </Typography>
        )}
        <Alert severity="info" sx={{ mt: 2 }}>
          {SHOWN_ONCE_HINT}
        </Alert>
      </DialogContent>
      <DialogActions>
        <Stack direction="row" spacing={1}>
          <Button
            variant="outlined"
            startIcon={<ContentCopyOutlinedIcon />}
            onClick={() => void copyOnce(url, 'Der Link')}
          >
            Link kopieren
          </Button>
          <Button variant="contained" onClick={onClose}>
            Schließen
          </Button>
        </Stack>
      </DialogActions>
    </Dialog>
  )
}
