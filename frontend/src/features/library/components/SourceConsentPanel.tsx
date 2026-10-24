import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { PendingSourceConnection } from '@/shared/api/types'
import BusyButton from '@/shared/ui/a11y/BusyButton'
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
 * The part „Quelle verbinden“ of the wizard's step „Quelle einrichten“ on a profile whose
 * libraries consent at the provider themselves: the confirmation of the service account and the way
 * to the provider, then the account the consent was given as.
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
      <Typography component="h4" sx={{ fontSize: 12, color: 'text.secondary', mb: '5px' }}>
        Quelle verbinden
      </Typography>
      {pending ? (
        <Typography sx={{ fontSize: 13.5, color: 'text.primary' }}>
          {pending.accountLabel ? `Verbunden als „${pending.accountLabel}“` : 'Verbunden'}
          {` · bis ${clockTime(pending.expiresAt)} Uhr anlegen`}
        </Typography>
      ) : (
        <>
          <Typography variant="body2" sx={{ mb: 1 }}>
            Weiter zu „{profileName}“; Ihre Eingaben bleiben erhalten.
          </Typography>
          <ServiceAccountConfirmation
            idPrefix="library-create"
            checked={confirmed}
            onChange={onConfirmedChange}
            missing={confirmationMissing}
          />
          <BusyButton
            variant="outlined"
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
