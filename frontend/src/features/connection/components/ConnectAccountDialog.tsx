import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Stack from '@mui/material/Stack'
import FormField from '@/shared/ui/form/FormField'
import Typography from '@mui/material/Typography'
import type { ConnectedAccount, PersonalSecretForm } from '@/shared/api/types'
import { ConnectAccountError, connectMyAccount } from '../api/connectedAccountApi'
import {
  connectErrorMessage,
  needsUsername,
  secretFieldLabel,
  secretFormLabel,
} from './connectedAccountLabels'
import { fontSize } from '@/shared/theme/tokens'
import FormDialog from '@/shared/ui/dialog/FormDialog'

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
  // Set by a submit with missing input; the fields then say what is missing.
  const [attempted, setAttempted] = useState(false)
  const errorRef = useRef<HTMLDivElement>(null)
  const titleId = `connect-account-title-${target.profileId}`

  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  const usernameMissing = withUsername && username.trim() === ''
  const secretMissing = secret === ''

  async function handleSubmit() {
    if (usernameMissing || secretMissing) {
      setAttempted(true)
      return
    }
    setAttempted(false)
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
    <FormDialog
      titleId={titleId}
      title={`${target.reconnect ? 'Konto neu verbinden' : 'Konto verbinden'}: ${target.name}`}
      submitLabel="Verbinden"
      onSubmit={() => void handleSubmit()}
      onClose={onClose}
      busy={busy}
      busyAnnouncement="Anmeldung wird geprüft"
    >
      <Stack spacing={2}>
        <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
          Geben Sie Ihre eigenen Zugangsdaten für diesen Zugang ein (
          {secretFormLabel(target.secretForm)}). OPAA prüft die Anmeldung beim Anbieter, bevor es
          sie verschlüsselt speichert.
        </Typography>
        <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary' }}>
          Wer was sieht: Kontonamen und Inhalte Ihrer privaten Bibliotheken sehen nur Sie. Die
          Systemverwaltung sieht nur gerundete Anzahlen; Verbinden und Trennen stehen unter einem
          Pseudonym im Verbindungsprotokoll der Revision.
        </Typography>
        {error && (
          <Alert severity="error" role="alert" tabIndex={-1} ref={errorRef}>
            {error}
          </Alert>
        )}
        {withUsername && (
          <FormField
            label="Benutzername"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="off"
            required
            error={attempted && usernameMissing}
            helperText={
              attempted && usernameMissing ? 'Bitte den Benutzernamen angeben.' : undefined
            }
            slotProps={{ htmlInput: { maxLength: 255 } }}
          />
        )}
        <FormField
          label={secretFieldLabel(target.secretForm)}
          type="password"
          value={secret}
          onChange={(event) => setSecret(event.target.value)}
          autoComplete="new-password"
          required
          error={attempted && secretMissing}
          helperText={
            attempted && secretMissing
              ? 'Bitte ausfüllen. Wird nach dem Absenden aus dem Feld gelöscht und nie wieder angezeigt.'
              : 'Wird nach dem Absenden aus dem Feld gelöscht und nie wieder angezeigt.'
          }
          slotProps={{ htmlInput: { maxLength: 4096 } }}
        />
      </Stack>
    </FormDialog>
  )
}
