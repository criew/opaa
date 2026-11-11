import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { LocalUserResponse } from '@/shared/api/types'
import { fontFamily, fontSize, radius } from '@/shared/theme/tokens'
import { SHOWN_ONCE_HINT } from './localUserLabels'
import { copyOnce } from '@/shared/utils/copyOnce'
import SecretDialog from '@/shared/ui/dialog/SecretDialog'

/**
 * The one-time display of a handover link (ADR-0033 Entscheidung 12) - as with the
 * invitation link only when the mail did not go out. A dialog of its own next to
 * {@link ./SetupLinkDialog}, because this link does something else and the warning about it is
 * a different one: whoever redeems it changes the account's identity.
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
    <SecretDialog
      titleId="handover-link-title"
      title={`Link zur Kontoübergabe für „${user.displayName}“`}
      onCopy={() => void copyOnce(url, 'Der Link')}
      onClose={onClose}
    >
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 1.5 }}>
        {user.email}
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
          fontSize: fontSize.meta,
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
        <Typography sx={{ fontSize: fontSize.meta, color: 'text.secondary', mt: 1 }}>
          Dies ist ein Pfad relativ zur Installation, weil keine öffentliche Basis-URL gesetzt ist –
          setzen Sie die Adresse Ihrer Installation davor.
        </Typography>
      )}
      <Alert severity="info" sx={{ mt: 2 }}>
        {SHOWN_ONCE_HINT}
      </Alert>
    </SecretDialog>
  )
}
