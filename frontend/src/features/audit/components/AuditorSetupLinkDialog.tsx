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
import { copyOnce } from '@/shared/utils/copyOnce'

export interface AuditorSetupLink {
  person: string
  email: string | null
  url: string
}

/**
 * The one-time display of the link with which a new person of the revision sets her password.
 * Only the confirming auditor sees it - it is neither mailed nor shown to the system
 * administration - and closes only through the button, since Escape or a click outside would
 * destroy a value that exists exactly once.
 */
export default function AuditorSetupLinkDialog({
  link,
  onClose,
}: {
  link: AuditorSetupLink | null
  onClose: () => void
}) {
  if (!link) return null
  const { person, email, url } = link

  return (
    <Dialog open fullWidth maxWidth="sm" aria-labelledby="auditor-setup-link-title">
      <DialogTitle id="auditor-setup-link-title">Link zum Passwort für „{person}“</DialogTitle>
      <DialogContent>
        {email && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>{email}</Typography>
        )}
        <Alert severity="warning" sx={{ mb: 2 }}>
          Übergeben Sie den Link der Person selbst, persönlich oder auf einem Weg, den die
          Systemverwaltung nicht einsehen kann. Er wird nicht per E-Mail versandt.
        </Alert>
        <Box
          data-testid="auditor-setup-link-value"
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
          Der Link wird nur jetzt angezeigt. Geht er verloren, entzieht die Systemverwaltung die
          Rolle und beantragt sie neu; die Bestätigung erzeugt dann einen neuen Link.
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
