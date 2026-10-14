import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import Typography from '@mui/material/Typography'
import type { OidcProviderImpactResponse, OidcProviderResponse } from '@/shared/api/types'
import BusyButton from '@/shared/ui/a11y/BusyButton'
import { apiErrorCode, apiErrorMessage } from '@/shared/api/apiErrorDetails'
import { getOidcProviderImpact } from '../api/identityProviderApi'
import { notify } from '@/shared/stores/notificationStore'
import { useOidcProviderStore } from '../stores/oidcProviderStore'
import { PROVIDER_CONFLICT_MESSAGES } from './oidcProviderConflicts'

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

const EFFECT_TEXT: Record<OidcProviderImpactResponse['disableEffect'], string> = {
  CONNECTIONS_REST:
    'Etwaige verbundene Konten der Personen dieses Anbieters ruhen: Es wird nichts gelöscht, und ' +
    'ihre privaten Bibliotheken werden nur nicht mehr aktualisiert. Nach dem Aktivieren geht es ' +
    'ohne neues Verbinden weiter.',
  CONNECTIONS_END:
    'Die gespeicherten Zugangsdaten etwaiger verbundener Konten dieser Personen werden sofort ' +
    'gelöscht – das lässt sich nicht rückgängig machen. Für ihre privaten Bibliotheken beginnt ' +
    'die Löschfrist.',
}

interface ProviderShutdownDialogProps {
  provider: OidcProviderResponse
  action: ProviderShutdownAction
  /** The one enabled provider left: confirming here *is* the backend's acknowledgement. */
  isLastEnabled: boolean
  onClose: () => void
}

/**
 * The confirmation before deactivating or deleting an identity provider (ADR-0041,
 * Entscheidung 4). It names the effect on any connected accounts, never a number per provider,
 * and only then sends the confirmations the backend requires: the confirmation that was read is
 * the acknowledgement. If it cannot be loaded whether connection profiles admit persons, it
 * names the effect as a precaution.
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
  const [loadFailed, setLoadFailed] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let current = true
    getOidcProviderImpact(provider.id)
      .then((loaded) => {
        if (current) setImpact(loaded)
      })
      .catch(() => {
        if (current) setLoadFailed(true)
      })
    return () => {
      current = false
    }
  }, [provider.id])

  const deleting = action === 'delete'
  const ready = impact !== null || loadFailed
  const connectionsConcerned = impact?.confirmationRequired ?? loadFailed
  const effect = deleting
    ? (impact?.deleteEffect ?? 'CONNECTIONS_END')
    : (impact?.disableEffect ?? 'CONNECTIONS_REST')

  async function submit() {
    if (!ready) return
    const confirmations = {
      acknowledgeLastProvider: isLastEnabled,
      confirmConnections: connectionsConcerned,
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
      // The rejection because of effective groups is no end point but a pointer: the
      // provider's worklist lists every group with its effects (ADR-0036/2).
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
          {deleting ? DELETE_CONSEQUENCE : DISABLE_CONSEQUENCE}
        </Typography>
        {isLastEnabled && (
          <Typography sx={{ fontSize: 13.5, color: 'text.secondary', mb: 1.5 }}>
            {LAST_PROVIDER_CONSEQUENCE}
          </Typography>
        )}
        {connectionsConcerned && (
          <Box component="section" aria-labelledby="provider-shutdown-connections">
            <Typography
              id="provider-shutdown-connections"
              component="h3"
              sx={{ fontSize: 13.5, fontWeight: 600, mb: 0.5 }}
            >
              Verbundene Konten
            </Typography>
            <Typography sx={{ fontSize: 13.5, color: 'text.secondary' }}>
              {EFFECT_TEXT[effect]}
            </Typography>
          </Box>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2.5, pt: 2 }}>
        {/* For an irreversible action the initial focus is on the cancel button. */}
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
          {deleting ? 'Löschen' : 'Deaktivieren'}
        </BusyButton>
      </DialogActions>
    </Dialog>
  )
}
