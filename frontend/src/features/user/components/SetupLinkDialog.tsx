import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { LocalUserResponse, MailDeliveryPath } from '@/shared/api/types'
import { fontFamily, fontSize, radius } from '@/shared/theme/tokens'
import { MAIL_DELIVERY_PATH_TEXT, SHOWN_ONCE_HINT } from './localUserLabels'
import { copyOnce } from '@/shared/utils/copyOnce'
import SecretDialog from '@/shared/ui/dialog/SecretDialog'

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
    <SecretDialog
      titleId="setup-link-title"
      title={title}
      onCopy={() => void copyOnce(url, 'Der Link')}
      onClose={onClose}
    >
      <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', mb: 1.5 }}>
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
