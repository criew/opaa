import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Skeleton from '@mui/material/Skeleton'
import Typography from '@mui/material/Typography'
import type {
  OidcProviderImpactResponse,
  OidcProviderResponse,
  PersonCount,
} from '../../../types/api'
import BusyButton from '../../a11y/BusyButton'
import { apiErrorCode, apiErrorMessage } from '../../../services/apiErrorDetails'
import { getOidcProviderImpact } from '../../../services/identityProviderApi'
import { notify } from '../../../stores/notificationStore'
import { useOidcProviderStore } from '../../../stores/oidcProviderStore'
import { PROVIDER_CONFLICT_MESSAGES } from '../oidcProviderConflicts'

export type ProviderShutdownAction = 'disable' | 'delete'

export const DISABLE_CONSEQUENCE =
  'Nutzer dieses Anbieters können sich ab sofort nicht mehr anmelden; laufende Sitzungen enden ' +
  'mit der nächsten Anfrage. Die Konten und ihre Rechte bleiben erhalten.'

export const DELETE_CONSEQUENCE =
  'Nutzer dieses Anbieters können sich nicht mehr anmelden. Die Konten bleiben erhalten und ' +
  'werden wieder nutzbar, sobald ein Anbieter mit derselben Issuer-URI existiert.'

export const LAST_PROVIDER_CONSEQUENCE =
  'Dies ist der letzte aktivierte Identitätsanbieter. Danach können sich nur noch lokale Konten ' +
  'anmelden – über die Anmeldeseite und, für die Systemverwaltung, über /login/system. Ein ' +
  'vertippter Anbieter lässt sich aus der lokalen Anmeldung heraus korrigieren, ohne ' +
  'Datenbankzugriff und ohne Umgebungsvariable.'

export const CONNECTIONS_REST =
  'Die verbundenen Konten der Personen dieses Anbieters ruhen: Es wird nichts gelöscht, und ihre ' +
  'privaten Bibliotheken werden nur nicht mehr aktualisiert. Nach dem Aktivieren geht es ohne ' +
  'neues Verbinden weiter.'

export const CONNECTIONS_END =
  'Die gespeicherten Zugangsdaten der verbundenen Konten dieser Personen werden sofort gelöscht – ' +
  'das lässt sich nicht rückgängig machen. Für ihre privaten Bibliotheken beginnt die Löschfrist.'

export const DISABLE_WITHOUT_IMPACT =
  'Deaktivieren bleibt möglich, denn dabei wird nichts gelöscht; etwaige verbundene Konten ruhen.'

export const DELETE_NEEDS_IMPACT =
  'Löschen ist erst möglich, wenn die Folgen geladen sind, denn es löscht Zugangsdaten unumkehrbar.'

export const NUMBERS_HINT =
  '„Keine Angabe“ heißt nicht „keine“: Zahlen je Anbieter nennt OPAA nur, wo sie keinen ' +
  'Rückschluss auf einzelne Personen zulassen.'

/** The number as the backend tells it - never computed here. */
function personCountText(count?: PersonCount | null): string {
  if (count == null) return 'keine Angabe'
  if (count.atLeast != null) return `mindestens ${count.atLeast}`
  if (count.fewerThan != null) return `weniger als ${count.fewerThan}`
  return String(count.count)
}

interface ProviderShutdownDialogProps {
  provider: OidcProviderResponse
  action: ProviderShutdownAction
  /** The one enabled provider left: confirming here *is* the backend's acknowledgement. */
  isLastEnabled: boolean
  onClose: () => void
}

/**
 * Die Rückfrage vor dem Deaktivieren oder Löschen eines Identitätsanbieters (ADR-0041,
 * Entscheidung 4). Sie lädt die Folgen für verbundene Konten, nennt, was umkehrbar ist und was
 * nicht, und schickt erst danach die Bestätigungen, die das Backend verlangt: die gelesene
 * Rückfrage ist die Bestätigung. Jede Rückfrage ist ein eigener Dialog: Er lädt die Folgen beim
 * Öffnen und wird mit dem Schließen verworfen.
 */
export default function ProviderShutdownDialog({
  provider,
  action,
  isLastEnabled,
  onClose,
}: ProviderShutdownDialogProps) {
  const setProviderEnabled = useOidcProviderStore((s) => s.setProviderEnabled)
  const deleteExistingProvider = useOidcProviderStore((s) => s.deleteExistingProvider)
  const navigate = useNavigate()
  const [impact, setImpact] = useState<OidcProviderImpactResponse | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    getOidcProviderImpact(provider.id)
      .then((loaded) => {
        if (current) setImpact(loaded)
      })
      .catch((err: unknown) => {
        if (current) {
          setLoadError(
            err instanceof Error ? err.message : 'Die Folgen ließen sich nicht ermitteln.',
          )
        }
      })
    return () => {
      current = false
    }
  }, [provider.id])

  const deleting = action === 'delete'
  const verb = deleting ? 'Löschen' : 'Deaktivieren'
  const consequence = deleting ? DELETE_CONSEQUENCE : DISABLE_CONSEQUENCE
  // Disabling deletes nothing, so it must not hang on the numbers: a provider that has to go off
  // goes off even when they cannot be loaded. Deleting is irreversible and waits for them.
  const impactUnknown = loadError !== null
  const ready = impact !== null || (impactUnknown && !deleting)

  async function submit() {
    if (!ready) return
    const confirmations = {
      acknowledgeLastProvider: isLastEnabled,
      confirmConnections: impact === null || impact.confirmationRequired,
    }
    setBusy(true)
    try {
      if (deleting) {
        await deleteExistingProvider(provider.id, confirmations)
        notify(`„${provider.displayName}“ wurde gelöscht.`, 'success')
      } else {
        await setProviderEnabled(provider.id, false, confirmations)
        notify(`„${provider.displayName}“ wurde deaktiviert.`, 'success')
      }
    } catch (err) {
      const fallback = deleting ? 'Löschen fehlgeschlagen' : 'Änderung fehlgeschlagen'
      notify(apiErrorMessage(err, PROVIDER_CONFLICT_MESSAGES, fallback), 'error')
      // Die Ablehnung wegen wirkender Gruppen ist kein Endpunkt, sondern ein Verweis: die
      // Arbeitsliste des Anbieters führt jede Gruppe mit ihren Wirkungen (ADR-0036/2).
      if (apiErrorCode(err) === 'PROVIDER_GROUPS_IN_EFFECT') {
        void navigate(`/admin/identity-providers/${provider.id}/groups`)
      }
    } finally {
      setBusy(false)
      onClose()
    }
  }

  return (
    <Dialog
      open
      onClose={busy ? undefined : onClose}
      maxWidth="xs"
      fullWidth
      aria-labelledby="provider-shutdown-question"
      slotProps={{
        paper: {
          sx: { borderLeft: 3, borderLeftColor: deleting ? 'error.main' : 'warning.main' },
        },
      }}
    >
      <DialogTitle id="provider-shutdown-question" sx={{ fontSize: 17, pb: 1 }}>
        {`„${provider.displayName}“ ${deleting ? 'löschen' : 'deaktivieren'}?`}
      </DialogTitle>
      <DialogContent sx={{ pt: 0 }}>
        <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
          {consequence}
        </Typography>
        {isLastEnabled && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
            {LAST_PROVIDER_CONSEQUENCE}
          </Typography>
        )}
        {loadError && (
          <Alert severity="error" sx={{ fontSize: 13, mb: 1.5 }}>
            Die Folgen für verbundene Konten ließen sich nicht ermitteln: {loadError}{' '}
            {deleting ? DELETE_NEEDS_IMPACT : DISABLE_WITHOUT_IMPACT}
          </Alert>
        )}
        {impactUnknown && !deleting && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
            {CONNECTIONS_REST}
          </Typography>
        )}
        {!loadError && impact === null && (
          <Box aria-busy="true">
            <Skeleton variant="text" width="80%" />
            <Skeleton variant="text" width="60%" />
          </Box>
        )}
        {impact?.confirmationRequired && (
          <Box component="section" aria-labelledby="provider-shutdown-connections">
            <Typography
              id="provider-shutdown-connections"
              component="h3"
              sx={{ fontSize: 13.5, fontWeight: 600, mb: 0.5 }}
            >
              Verbundene Konten
            </Typography>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1 }}>
              {deleting ? CONNECTIONS_END : CONNECTIONS_REST}
            </Typography>
            <Box component="dl" sx={{ m: 0, fontSize: 13.5 }}>
              <NumberLine term="Verbundene Konten" count={impact.connections} />
              <NumberLine term="Private Bibliotheken" count={impact.privateLibraries} />
            </Box>
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
              {NUMBERS_HINT}
            </Typography>
          </Box>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5, pt: 2 }}>
        {/* Bei einer unwiderruflichen Handlung liegt der Startfokus auf dem Abbruch. */}
        {/* eslint-disable-next-line jsx-a11y-x/no-autofocus */}
        <Button onClick={onClose} disabled={busy} autoFocus={deleting}>
          Abbrechen
        </Button>
        <BusyButton
          variant="contained"
          color={deleting ? 'error' : 'primary'}
          busy={busy}
          busyAnnouncement={deleting ? 'Anbieter wird gelöscht …' : 'Anbieter wird deaktiviert …'}
          disabled={!ready}
          onClick={() => void submit()}
        >
          {verb}
        </BusyButton>
      </DialogActions>
    </Dialog>
  )
}

function NumberLine({ term, count }: { term: string; count?: PersonCount | null }) {
  return (
    <Box sx={{ display: 'flex', gap: 1 }}>
      <Box component="dt" sx={{ color: 'text.secondary' }}>
        {term}:
      </Box>
      <Box component="dd" sx={{ m: 0, fontWeight: 500 }}>
        {personCountText(count)}
      </Box>
    </Box>
  )
}
