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
import { fontFamily, radius } from '@/shared/theme/tokens'
import { SHOWN_ONCE_HINT } from './localUserLabels'
import { copyOnce } from '@/shared/utils/copyOnce'

export interface GeneratedPassword {
  displayName: string
  email: string
  password: string
}

/**
 * The one-time display of a generated initial or replacement password (ADR-0033
 * Entscheidung 11). The password is shown in plain text because it is handed over exactly here and
 * exists nowhere else; it must be changed on the next login.
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
      aria-labelledby="generated-password-title"
      // See SetupLinkDialog: the password exists exactly once, so neither Escape
      // nor a click outside closes it – only the button.
    >
      <DialogTitle id="generated-password-title">
        Neues Passwort für „{generated.displayName}“
      </DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
          {generated.email}
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
            Schließen
          </Button>
        </Stack>
      </DialogActions>
    </Dialog>
  )
}
