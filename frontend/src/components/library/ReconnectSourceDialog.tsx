import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Typography from '@mui/material/Typography'
import { startSourceAuthorization } from '../../services/connectedAccountApi'
import { leaveFor } from '../../services/leaveApp'
import BusyButton from '../a11y/BusyButton'
import ServiceAccountConfirmation from './ServiceAccountConfirmation'
import { forgetConsentIntent, rememberConsentIntent } from './sourceConsent'

interface ReconnectSourceDialogProps {
  open: boolean
  onClose: () => void
  /** „Quelle verbinden“ or „Quelle neu verbinden“ - the label of the action that opened it. */
  title: string
  libraryId: string
  profileId: string
  profileName: string
}

/**
 * Connects an existing library's source anew at the provider (LIBRARY_RECONNECT): the service
 * account is confirmed first, the provider returns to the library's Reiter „Quelle“.
 */
export default function ReconnectSourceDialog({
  open,
  onClose,
  title,
  libraryId,
  profileId,
  profileName,
}: ReconnectSourceDialogProps) {
  const [confirmed, setConfirmed] = useState(false)
  const [missing, setMissing] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function handleStart() {
    if (!confirmed) {
      setMissing(true)
      return
    }
    setBusy(true)
    setError(null)
    rememberConsentIntent({ purpose: 'LIBRARY_RECONNECT', profileId, libraryId })
    try {
      const started = await startSourceAuthorization({
        profileId,
        purpose: 'LIBRARY_RECONNECT',
        libraryId,
        serviceAccountConfirmed: true,
      })
      leaveFor(started.authorizationUrl)
    } catch (err) {
      forgetConsentIntent()
      setError(
        err instanceof Error && err.message
          ? err.message
          : 'Die Anmeldung beim Anbieter ließ sich nicht starten.',
      )
      setBusy(false)
    }
  }

  const titleId = 'reconnect-source-title'
  return (
    <Dialog open={open} onClose={busy ? undefined : onClose} aria-labelledby={titleId}>
      <DialogTitle id={titleId}>{title}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" sx={{ mb: 2 }}>
          Sie werden zum Anbieter des Zugangs „{profileName}“ weitergeleitet, melden sich dort mit
          dem Dienstkonto an und stimmen zu. Danach kehren Sie hierher zurück. Meldet der Anbieter
          ein anderes Konto als bisher, fragt OPAA vor dem Wechsel nach.
        </Typography>
        <ServiceAccountConfirmation
          idPrefix="reconnect-source"
          checked={confirmed}
          onChange={(checked) => {
            setConfirmed(checked)
            if (checked) setMissing(false)
          }}
          missing={missing}
        />
        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          Abbrechen
        </Button>
        <BusyButton
          variant="contained"
          busy={busy}
          busyAnnouncement="Die Weiterleitung zum Anbieter wird vorbereitet."
          onClick={() => void handleStart()}
        >
          Weiter zum Anbieter
        </BusyButton>
      </DialogActions>
    </Dialog>
  )
}
