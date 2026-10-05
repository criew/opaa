import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { PendingSourceConnection } from '../../types/api'
import BusyButton from '../a11y/BusyButton'
import ServiceAccountConfirmation from './ServiceAccountConfirmation'

interface SourceConsentPanelProps {
  profileName: string
  /** The consent given in this wizard, waiting for the library to be created. */
  pending: PendingSourceConnection | null
  confirmed: boolean
  onConfirmedChange: (confirmed: boolean) => void
  /** Set once „Quelle verbinden“ was pressed without the confirmation. */
  confirmationMissing: boolean
  busy: boolean
  onConnect: () => void
}

function clockTime(value: string): string {
  return new Date(value).toLocaleTimeString('de-DE', { hour: '2-digit', minute: '2-digit' })
}

/**
 * The wizard's step „Quelle verbinden“ on a profile whose libraries consent at the provider
 * themselves: the confirmation of the service account and the way to the provider, then the
 * account the consent was given as.
 */
export default function SourceConsentPanel({
  profileName,
  pending,
  confirmed,
  onConfirmedChange,
  confirmationMissing,
  busy,
  onConnect,
}: SourceConsentPanelProps) {
  return (
    <Box data-testid="source-consent">
      <Typography component="h3" sx={{ fontSize: 16, fontWeight: 600, mb: 1 }}>
        Quelle verbinden
      </Typography>
      {pending ? (
        <Alert severity="success">
          {pending.accountLabel
            ? `Verbunden als „${pending.accountLabel}“.`
            : 'Die Quelle ist verbunden.'}{' '}
          Die Zustimmung gilt 60 Minuten für diese neue Bibliothek, bis{' '}
          {clockTime(pending.expiresAt)} Uhr. Legen Sie die Bibliothek bis dahin an; sonst verbinden
          Sie die Quelle erneut.
        </Alert>
      ) : (
        <>
          <Typography variant="body2" sx={{ mb: 1 }}>
            Die Bibliothek liest ihre Quelle über eine eigene Zustimmung beim Anbieter des Zugangs „
            {profileName}“. Sie werden dorthin weitergeleitet, melden sich mit einem Dienstkonto an
            und stimmen zu; Ihre bisherigen Eingaben bleiben erhalten. Ordnerauswahl und
            Verbindungstest folgen danach.
          </Typography>
          <ServiceAccountConfirmation
            idPrefix="library-create"
            checked={confirmed}
            onChange={onConfirmedChange}
            missing={confirmationMissing}
          />
          <BusyButton
            variant="contained"
            sx={{ mt: 1 }}
            busy={busy}
            busyAnnouncement="Die Weiterleitung zum Anbieter wird vorbereitet."
            onClick={onConnect}
          >
            Quelle verbinden
          </BusyButton>
        </>
      )}
    </Box>
  )
}
