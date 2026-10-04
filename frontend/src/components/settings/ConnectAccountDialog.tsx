import { useEffect, useRef, useState, type FormEvent } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { ConnectedAccount, PersonalSecretForm } from '../../types/api'
import { ConnectAccountError, connectMyAccount } from '../../services/connectedAccountApi'
import BusyButton from '../a11y/BusyButton'
import {
  connectErrorMessage,
  needsUsername,
  secretFieldLabel,
  secretFormLabel,
} from './connectedAccountLabels'

export interface ConnectTarget {
  profileId: string
  name: string
  secretForm: PersonalSecretForm
  /** The account name the caller entered last time, for a reconnection. */
  accountLabel?: string | null
  /** An existing connection is renewed rather than created. */
  reconnect: boolean
}

interface ConnectAccountDialogProps {
  target: ConnectTarget
  onClose: () => void
  onConnected: (account: ConnectedAccount) => void
}

/**
 * Enters the caller's own secret for one profile. The form follows `secretForm` alone - no
 * connector-specific fields. The secret lives only while this dialog is mounted and is cleared
 * after every attempt; the caller mounts the dialog per attempt.
 */
export default function ConnectAccountDialog({
  target,
  onClose,
  onConnected,
}: ConnectAccountDialogProps) {
  const withUsername = needsUsername(target.secretForm)
  const [username, setUsername] = useState(target.accountLabel ?? '')
  const [secret, setSecret] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const errorRef = useRef<HTMLDivElement>(null)
  const titleId = `connect-account-title-${target.profileId}`

  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  const canSubmit = secret !== '' && (!withUsername || username.trim() !== '')

  async function handleSubmit(event: FormEvent) {
    event.preventDefault()
    if (busy || !canSubmit) return
    setBusy(true)
    setError(null)
    const value = secret
    setSecret('')
    try {
      const account = await connectMyAccount(target.profileId, {
        username: withUsername ? username.trim() : null,
        secret: value,
      })
      onConnected(account)
    } catch (err: unknown) {
      setError(
        err instanceof ConnectAccountError
          ? connectErrorMessage(err.status, err.code, err.message)
          : connectErrorMessage(null, null, ''),
      )
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog
      open
      fullWidth
      maxWidth="sm"
      onClose={busy ? undefined : onClose}
      aria-labelledby={titleId}
    >
      <form onSubmit={(event) => void handleSubmit(event)} noValidate>
        <DialogTitle id={titleId}>
          {target.reconnect ? 'Konto neu verbinden' : 'Konto verbinden'}: {target.name}
        </DialogTitle>
        <DialogContent>
          <Stack spacing={2}>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Geben Sie Ihre eigenen Zugangsdaten für diesen Zugang ein (
              {secretFormLabel(target.secretForm)}). OPAA prüft die Anmeldung beim Anbieter, bevor
              es sie verschlüsselt speichert.
            </Typography>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              Wer was sieht: Ihren Kontonamen beim Anbieter und die Inhalte Ihrer privaten
              Bibliotheken sehen nur Sie. Die Systemverwaltung sieht nur gerundete Anzahlen
              verbundener Konten. Wann ein Konto verbunden, neu verbunden oder getrennt wurde, steht
              unter einem Pseudonym statt Ihres Namens im Verbindungsprotokoll der Revision.
            </Typography>
            {error && (
              <Alert severity="error" role="alert" tabIndex={-1} ref={errorRef}>
                {error}
              </Alert>
            )}
            {withUsername && (
              <TextField
                label="Benutzername"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                autoComplete="off"
                required
                slotProps={{ htmlInput: { maxLength: 255 } }}
              />
            )}
            <TextField
              label={secretFieldLabel(target.secretForm)}
              type="password"
              value={secret}
              onChange={(event) => setSecret(event.target.value)}
              autoComplete="new-password"
              required
              helperText="Wird nach dem Absenden aus dem Feld gelöscht und nie wieder angezeigt."
              slotProps={{ htmlInput: { maxLength: 4096 } }}
            />
          </Stack>
        </DialogContent>
        <DialogActions>
          <Button onClick={onClose} disabled={busy}>
            Abbrechen
          </Button>
          <BusyButton
            type="submit"
            variant="contained"
            busy={busy}
            busyAnnouncement="Anmeldung wird geprüft"
            disabled={!canSubmit && !busy}
          >
            {target.reconnect ? 'Neu verbinden' : 'Verbinden'}
          </BusyButton>
        </DialogActions>
      </form>
    </Dialog>
  )
}
