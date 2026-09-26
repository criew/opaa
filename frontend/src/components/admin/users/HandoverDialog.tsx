import { useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
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
  'Einen Rückweg gibt es nicht: Nach dem Abschluss meldet sich die Person nur noch über den ' +
  'gewählten Anbieter an, ihr bisheriges Passwort gilt dann nicht mehr.'

/** What a handover is, in plain words - the dialog is often the first place anyone meets it. */
export const HANDOVER_EXPLANATION =
  'Mit einer Übergabe meldet sich die Person künftig nicht mehr mit einem Passwort an, sondern ' +
  'über einen Identitätsanbieter, zum Beispiel den Verzeichnisdienst Ihres Hauses. Das Konto bleibt ' +
  'dasselbe: Spaces, Gruppen und Rolle bleiben erhalten.'

const HANDOVER_STEPS = [
  'Sie wählen den Anbieter und nennen den Anlass.',
  'Die Person bekommt per E-Mail einen Link, der 72 Stunden gilt.',
  'Sie öffnet den Link und meldet sich beim Anbieter an. Erst damit ist die Übergabe abgeschlossen – bis dahin kann sie sich weiter mit ihrem Passwort anmelden.',
]

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
        <Typography sx={{ fontSize: 13.5, mb: 1.5 }}>
          Für „{user.displayName}“ ({user.email})
        </Typography>
        <Typography sx={{ fontSize: 13.5, mb: 1 }}>{HANDOVER_EXPLANATION}</Typography>
        <Typography component="h3" sx={{ fontSize: 13.5, fontWeight: 600, mb: 0.5 }}>
          So läuft es ab
        </Typography>
        <Box component="ol" sx={{ m: 0, mb: 2, pl: 2.5, fontSize: 13.5 }}>
          {HANDOVER_STEPS.map((step) => (
            <Box component="li" key={step} sx={{ mb: 0.25 }}>
              {step}
            </Box>
          ))}
        </Box>
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
