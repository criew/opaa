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
import type { LocalUserResponse } from '../../../types/api'
import { fontFamily, radius } from '../../../theme/tokens'
import { SHOWN_ONCE_HINT } from './localUserLabels'
import { copyOnce } from './copyOnce'

/**
 * Die einmalige Anzeige eines Übergabe-Links (#1563, ADR-0033 Entscheidung 12) - wie beim
 * Einladungslink nur dann, wenn die Mail nicht hinausgegangen ist. Ein eigener Dialog neben
 * {@link ./SetupLinkDialog}, weil dieser Link etwas anderes tut und die Warnung dazu eine andere
 * ist: Wer ihn einlöst, wechselt die Identität des Kontos.
 */
export default function HandoverLinkDialog({
  handover,
  onClose,
}: {
  handover: { user: LocalUserResponse; url: string } | null
  onClose: () => void
}) {
  if (!handover) return null
  const { user, url } = handover

  return (
    <Dialog
      open
      fullWidth
      maxWidth="sm"
      aria-labelledby="handover-link-title"
      // Ohne `onClose` schließt weder ein Klick daneben noch Escape: Beides vernichtete den Wert,
      // den es genau einmal gibt - dasselbe Muster wie beim Einladungslink.
    >
      <DialogTitle id="handover-link-title">Übergabe-Link übergeben</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>
          Für „{user.displayName}“ ({user.email})
        </Typography>
        <Alert severity="warning" sx={{ mb: 2 }}>
          Der Link konnte nicht per E-Mail zugestellt werden. Übergeben Sie ihn der Person auf einem
          Weg, auf dem Sie sicher sind, dass Sie mit ihr sprechen — wer ihn einlöst, bestimmt die
          Anbieteridentität des Kontos.
        </Alert>
        <Box
          data-testid="handover-link-value"
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
