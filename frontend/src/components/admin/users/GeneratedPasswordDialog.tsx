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
import { fontFamily, radius } from '../../../theme/tokens'
import { SHOWN_ONCE_HINT } from './localUserLabels'
import { copyOnce } from './copyOnce'

export interface GeneratedPassword {
  displayName: string
  email: string
  password: string
}

/**
 * Die einmalige Anzeige eines erzeugten Anfangs- oder Ersatzpassworts (#1541, ADR-0033
 * Entscheidung 11). Das Passwort steht im Klartext, weil es genau hier übergeben wird und nirgends
 * sonst existiert; bei der nächsten Anmeldung muss es gewechselt werden.
 */
export default function GeneratedPasswordDialog({
  generated,
  onClose,
}: {
  generated: GeneratedPassword | null
  onClose: () => void
}) {
  if (!generated) return null

  return (
    <Dialog
      open
      fullWidth
      maxWidth="sm"
      onClose={onClose}
      aria-labelledby="generated-password-title"
    >
      <DialogTitle id="generated-password-title">Passwort übergeben</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>
          Für „{generated.displayName}“ ({generated.email})
        </Typography>
        <Box
          data-testid="generated-password-value"
          sx={{
            fontFamily: fontFamily.mono,
            fontSize: 16,
            fontWeight: 500,
            p: 1.5,
            border: 1,
            borderColor: 'divider',
            borderRadius: `${radius.sm}px`,
            overflowWrap: 'anywhere',
          }}
        >
          {generated.password}
        </Box>
        <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
          Bei der ersten Anmeldung muss das Passwort gewechselt werden. Übergeben Sie es auf einem
          nachvollziehbaren Weg – nicht in derselben Nachricht wie die Kennung.
        </Typography>
        <Alert severity="info" sx={{ mt: 2 }}>
          {SHOWN_ONCE_HINT}
        </Alert>
      </DialogContent>
      <DialogActions>
        <Stack direction="row" spacing={1}>
          <Button
            variant="outlined"
            startIcon={<ContentCopyOutlinedIcon />}
            onClick={() => void copyOnce(generated.password, 'Das Passwort')}
          >
            Passwort kopieren
          </Button>
          <Button variant="contained" onClick={onClose}>
            Übergeben, schließen
          </Button>
        </Stack>
      </DialogActions>
    </Dialog>
  )
}
