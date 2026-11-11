import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { fontFamily, fontSize, radius } from '@/shared/theme/tokens'
import { SHOWN_ONCE_HINT } from './localUserLabels'
import { copyOnce } from '@/shared/utils/copyOnce'
import SecretDialog from '@/shared/ui/dialog/SecretDialog'

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
    <SecretDialog
      titleId="generated-password-title"
      title={`Neues Passwort für „${generated.displayName}“`}
      onCopy={() => void copyOnce(generated.password, 'Das Passwort')}
      onClose={onClose}
    >
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 1.5 }}>
        {generated.email}
      </Typography>
      <Box
        data-testid="generated-password-value"
        sx={{
          fontFamily: fontFamily.mono,
          fontSize: fontSize.title,
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
      <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 1 }}>
        Bei der ersten Anmeldung muss das Passwort gewechselt werden. Übergeben Sie es auf einem
        nachvollziehbaren Weg – nicht in derselben Nachricht wie die Kennung.
      </Typography>
      <Alert severity="info" sx={{ mt: 2 }}>
        {SHOWN_ONCE_HINT}
      </Alert>
    </SecretDialog>
  )
}
