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
import type { LocalUserResponse, MailDeliveryPath } from '../../../types/api'
import { fontFamily, radius } from '../../../theme/tokens'
import { MAIL_DELIVERY_PATH_TEXT, SHOWN_ONCE_HINT } from './localUserLabels'
import { copyOnce } from './copyOnce'

export interface SetupLinkHandover {
  user: LocalUserResponse
  url: string
  deliveryPath?: MailDeliveryPath
  /** Which act produced the link - an invitation sets the first password, a reset replaces it. */
  kind: 'INVITE' | 'RESET'
}

/**
 * Die einmalige Anzeige eines Einladungs- oder Rücksetzlinks (#1541, ADR-0033 Entscheidung 11).
 * Sie erscheint nur, wenn die Mail nicht hinausgegangen ist; der Zustellweg steht als Satz dabei,
 * damit eine Übergabe außerhalb des Systems von einer Zustellung unterscheidbar bleibt.
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
  const title = kind === 'INVITE' ? 'Einladungslink übergeben' : 'Rücksetzlink übergeben'

  return (
    <Dialog
      open
      fullWidth
      maxWidth="sm"
      aria-labelledby="setup-link-title"
      // Ohne `onClose` schließt weder ein Klick daneben noch Escape: Beides würde den Wert
      // vernichten, der genau einmal existiert (Review-Runde 1, LOW 7). Geschlossen wird allein
      // über die Schaltfläche.
    >
      <DialogTitle id="setup-link-title">{title}</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>
          Für „{user.displayName}“ ({user.email})
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
            Übergeben, schließen
          </Button>
        </Stack>
      </DialogActions>
    </Dialog>
  )
}
