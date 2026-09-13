import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import MenuItem from '@mui/material/MenuItem'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import type { LocalUserResponse } from '../../../types/api'
import { useAuthStore } from '../../../stores/authStore'
import { notify } from '../../../stores/notificationStore'
import { useUserAdminStore } from '../../../stores/userAdminStore'
import { localUserErrorMessage } from './localUserLabels'

/** The same limit the creation reason has - and the same purpose binding (ADR-0033, E. 11). */
const REASON_MAX_LENGTH = 200

export const HANDOVER_CONSEQUENCE =
  'Die Person erhält einen einmaligen Link und schließt die Übergabe selbst ab, indem sie sich ' +
  'beim gewählten Anbieter anmeldet. Erst dann wechselt das Konto die Identität; Spaces, ' +
  'Mitgliedschaften und Rolle bleiben, das Passwort entfällt. Einen Rückweg gibt es nicht.'

export const REASON_HELPER =
  'Dienstlicher Anlass der Übergabe. Die Person liest ihn auf der Einlöseseite. Keine Angaben zu ' +
  'Gesundheit, Beschäftigungsverhältnis, Leistung, Disziplinarsachverhalten oder Dritten.'

export interface HandoverTarget {
  user: LocalUserResponse
}

/**
 * Der Anstoß einer Übergabe (#1563, ADR-0033 Entscheidung 12). Die Systemverwaltung wählt hier den
 * Anbieter und nennt den Anlass — mehr nicht: Das Subject kommt aus dem Token der Person, nie aus
 * einer Eingabe, und deshalb gibt es hier kein Feld dafür.
 *
 * Die Auswahl führt nur die aktivierten Identitätsanbieter der Installation; ohne einen solchen
 * gibt es nichts zu übergeben, und der Dialog sagt das statt eine leere Liste zu zeigen.
 */
export default function HandoverDialog({
  target,
  onClose,
  onLinkDisplayed,
}: {
  target: HandoverTarget | null
  onClose: () => void
  onLinkDisplayed: (user: LocalUserResponse, url: string) => void
}) {
  const providers = useAuthStore((s) => s.providers)
  const requestHandover = useUserAdminStore((s) => s.requestHandover)
  const [providerId, setProviderId] = useState(providers[0]?.id ?? '')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (!target) return null
  const { user } = target
  const reasonTooLong = reason.trim().length > REASON_MAX_LENGTH
  const canSubmit = providerId !== '' && reason.trim().length > 0 && !reasonTooLong && !busy

  async function submit() {
    setBusy(true)
    setError(null)
    try {
      const result = await requestHandover(user.id, providerId, reason)
      if (result.handoverUrl) {
        onLinkDisplayed(user, result.handoverUrl)
      } else {
        notify(`Der Übergabe-Link wurde an ${user.email} versendet.`, 'success')
      }
      onClose()
    } catch (err) {
      setError(localUserErrorMessage(err, 'Die Übergabe konnte nicht angestoßen werden.'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open fullWidth maxWidth="sm" onClose={onClose} aria-labelledby="handover-title">
      <DialogTitle id="handover-title">Übergabe anstoßen</DialogTitle>
      <DialogContent>
        <Typography sx={{ fontSize: 13.5, mb: 2 }}>
          Für „{user.displayName}“ ({user.email})
        </Typography>
        {error && (
          <Alert severity="error" sx={{ mb: 2 }}>
            {error}
          </Alert>
        )}
        {providers.length === 0 ? (
          <Alert severity="warning">
            Diese Installation hat keinen aktivierten Identitätsanbieter. Richten Sie zuerst einen
            ein — ohne ihn gibt es keine Identität, an die ein Konto übergeben werden könnte.
          </Alert>
        ) : (
          <Stack spacing={2}>
            <TextField
              select
              required
              fullWidth
              label="Identitätsanbieter"
              value={providerId}
              onChange={(e) => setProviderId(e.target.value)}
              helperText="Bei diesem Anbieter meldet sich die Person an, um die Übergabe abzuschließen."
            >
              {providers.map((provider) => (
                <MenuItem key={provider.id} value={provider.id}>
                  {provider.displayName}
                </MenuItem>
              ))}
            </TextField>
            <TextField
              required
              fullWidth
              multiline
              minRows={2}
              label="Anlass"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
              error={reasonTooLong}
              helperText={
                reasonTooLong
                  ? `Höchstens ${REASON_MAX_LENGTH} Zeichen sind erlaubt.`
                  : REASON_HELPER
              }
            />
            <Alert severity="warning">{HANDOVER_CONSEQUENCE}</Alert>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose} disabled={busy}>
          Abbrechen
        </Button>
        <Button variant="contained" onClick={() => void submit()} disabled={!canSubmit}>
          Übergabe anstoßen
        </Button>
      </DialogActions>
    </Dialog>
  )
}
