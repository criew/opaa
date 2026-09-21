import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Divider from '@mui/material/Divider'
import FormControlLabel from '@mui/material/FormControlLabel'
import Stack from '@mui/material/Stack'
import Switch from '@mui/material/Switch'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import { Link as RouterLink } from 'react-router'
import Link from '@mui/material/Link'
import type {
  DirectorySyncPendingPlanResponse,
  DirectorySyncReportResponse,
  DirectorySyncStatusResponse,
  OidcProviderResponse,
} from '../../../types/api'
import {
  confirmPendingPlan,
  discardPendingPlan,
  getPendingPlan,
  runDirectorySync,
  runDirectorySyncDryRun,
  setDirectorySync,
} from '../../../services/directorySyncApi'
import { apiErrorMessage } from '../../../services/apiErrorDetails'
import { notify } from '../../../stores/notificationStore'
import MetaBadge from '../../MetaBadge'
import SectionHead from '../../SectionHead'
import StatusLine from '../../StatusLine'
import { ageLabel } from '../../groups/groupOriginLabels'
import DirectoryConnectorSection from './DirectoryConnectorSection'
import DirectorySyncReportView from './DirectorySyncReportView'
import {
  DIRECTORY_SYNC_CONFLICT_MESSAGES,
  formatDateTime,
  formatFraction,
  outcomeLabel,
  outcomeTone,
} from './directorySyncLabels'

interface DirectorySyncProviderCardProps {
  provider: OidcProviderResponse
  status: DirectorySyncStatusResponse | undefined
  onChanged: () => Promise<void>
}

/**
 * Der Verzeichnisabgleich eines Anbieters (#1816, ADR-0036 Entscheidung 3): Schalter, Intervall,
 * Status, Trockenlauf, Lauf — und der ausstehende Plan als lauter Zustand mit seinem Alter.
 */
export default function DirectorySyncProviderCard({
  provider,
  status,
  onChanged,
}: DirectorySyncProviderCardProps) {
  const [interval, setIntervalMinutes] = useState(
    String(provider.directorySyncIntervalMinutes ?? 360),
  )
  const [report, setReport] = useState<DirectorySyncReportResponse | null>(null)
  const [plan, setPlan] = useState<DirectorySyncPendingPlanResponse | null>(null)
  const [reason, setReason] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const pendingSummary = status?.pendingPlan ?? null

  useEffect(() => {
    if (!pendingSummary) return
    void getPendingPlan(provider.id)
      .then(setPlan)
      .catch(() => setPlan(null))
  }, [pendingSummary, provider.id])

  async function run(action: () => Promise<void>, fallback: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
    } catch (err) {
      setError(apiErrorMessage(err, DIRECTORY_SYNC_CONFLICT_MESSAGES, fallback))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Box
      component="section"
      aria-label={`Verzeichnisabgleich ${provider.displayName}`}
      sx={{ borderBottom: 1, borderColor: 'divider', pb: 2.5 }}
    >
      <Stack direction="row" spacing={1} sx={{ alignItems: 'center', mb: 1 }}>
        <Typography component="h3" sx={{ fontSize: 14.5, fontWeight: 600 }}>
          {provider.displayName}
        </Typography>
        {provider.isExternal && <MetaBadge>extern</MetaBadge>}
        {!provider.enabled && <MetaBadge>deaktiviert</MetaBadge>}
        <Link
          component={RouterLink}
          to={`/admin/identity-providers/${provider.id}/groups`}
          sx={{ fontSize: 13, ml: 'auto' }}
        >
          Arbeitsliste der Gruppen
        </Link>
      </Stack>

      <StatusLine
        headline={outcomeLabel(status?.lastOutcome)}
        tone={outcomeTone(status?.lastOutcome)}
        label={`Abgleichstand ${provider.displayName}`}
        detail={
          <>
            {status?.lastMessage ?? 'Für diesen Anbieter lief noch kein Abgleich.'}
            {status?.lastRunAt && ` · zuletzt ${formatDateTime(status.lastRunAt)}`}
            {status?.lastChangedFraction !== null &&
              status?.lastChangedFraction !== undefined &&
              ` · Anteil ${formatFraction(status.lastChangedFraction)}`}
          </>
        }
      />

      {!provider.enabled && (
        <Alert severity="info" sx={{ mt: 1 }}>
          Der Anbieter ist deaktiviert; sein Abgleich pausiert, solange das so bleibt.
        </Alert>
      )}

      {pendingSummary && (
        <Alert severity="warning" sx={{ mt: 1.5 }}>
          Ein Plan wartet seit {ageLabel(pendingSummary.createdAt)} auf eine Entscheidung:{' '}
          {pendingSummary.membershipsRemoved} Mitgliedschaften würden entzogen,{' '}
          {pendingSummary.accountsLocked ?? 0} Konten gesperrt (Anteil{' '}
          {formatFraction(pendingSummary.changedFraction)}).
        </Alert>
      )}

      {error && (
        <Alert severity="error" sx={{ mt: 1.5 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      <Divider sx={{ my: 1.5 }} />

      <Stack direction={{ xs: 'column', md: 'row' }} spacing={1} sx={{ alignItems: 'center' }}>
        <FormControlLabel
          control={
            <Switch
              checked={provider.directorySyncEnabled}
              onChange={(e) =>
                void run(async () => {
                  await setDirectorySync(provider.id, {
                    enabled: e.target.checked,
                    intervalMinutes: Number(interval) || null,
                  })
                  await onChanged()
                }, 'Der Abgleich konnte nicht umgeschaltet werden.')
              }
              slotProps={{
                input: { 'aria-label': `Verzeichnisabgleich für ${provider.displayName}` },
              }}
            />
          }
          label="Verzeichnisabgleich eingeschaltet"
        />
        <TextField
          size="small"
          label="Intervall (Minuten)"
          value={interval}
          onChange={(e) => setIntervalMinutes(e.target.value)}
          sx={{ width: 180 }}
        />
        <Button
          size="small"
          disabled={busy || !provider.directorySyncEnabled}
          onClick={() =>
            void run(async () => {
              await setDirectorySync(provider.id, {
                enabled: true,
                intervalMinutes: Number(interval) || null,
              })
              notify('Das Intervall wurde gespeichert.', 'success')
              await onChanged()
            }, 'Das Intervall konnte nicht gespeichert werden.')
          }
        >
          Intervall speichern
        </Button>
      </Stack>

      <Stack direction="row" spacing={1} sx={{ mt: 1.5 }}>
        <Button
          variant="outlined"
          size="small"
          disabled={busy}
          onClick={() =>
            void run(async () => {
              setReport(await runDirectorySyncDryRun(provider.id))
            }, 'Der Trockenlauf ist fehlgeschlagen.')
          }
        >
          Trockenlauf
        </Button>
        <Button
          variant="contained"
          size="small"
          disabled={busy}
          onClick={() =>
            void run(async () => {
              setReport(await runDirectorySync(provider.id))
              await onChanged()
            }, 'Der Lauf ist fehlgeschlagen.')
          }
        >
          Jetzt abgleichen
        </Button>
      </Stack>

      {report && <DirectorySyncReportView report={report} />}

      {/* Der geladene Plan wird nur gezeigt, solange die Statuszeile einen ausstehenden meldet -
          ein bestätigter oder verworfener Plan verschwindet damit mit dem nächsten Statusabruf. */}
      {pendingSummary && plan && (
        <Box sx={{ mt: 2, borderTop: 1, borderColor: 'divider', pt: 1.5 }}>
          <SectionHead>Ausstehender Plan</SectionHead>
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Vorgelegt am {formatDateTime(plan.createdAt)} — Alter {ageLabel(plan.createdAt)}.
            Bestätigt wird gegen einen frischen Schnappschuss; weicht er ab, wird der Plan neu
            vorgelegt statt angewendet.
          </Typography>
          <DirectorySyncReportView report={plan.report} />
          <TextField
            size="small"
            fullWidth
            label="Grund (Pflicht, geht ins Protokoll)"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            sx={{ mt: 1.5 }}
          />
          <Stack direction="row" spacing={1} sx={{ mt: 1 }}>
            <Button
              variant="contained"
              size="small"
              disabled={busy || reason.trim() === ''}
              onClick={() =>
                void run(async () => {
                  setReport(await confirmPendingPlan(provider.id, plan.id, reason.trim()))
                  setReason('')
                  notify('Der Plan wurde angewendet.', 'success')
                  await onChanged()
                }, 'Der Plan konnte nicht angewendet werden.')
              }
            >
              Plan bestätigen
            </Button>
            <Button
              size="small"
              color="error"
              disabled={busy || reason.trim() === ''}
              onClick={() =>
                void run(async () => {
                  await discardPendingPlan(provider.id, plan.id, reason.trim())
                  setReason('')
                  setPlan(null)
                  notify('Der Plan wurde verworfen.', 'success')
                  await onChanged()
                }, 'Der Plan konnte nicht verworfen werden.')
              }
            >
              Plan verwerfen
            </Button>
          </Stack>
        </Box>
      )}

      <DirectoryConnectorSection provider={provider} onChanged={onChanged} />
    </Box>
  )
}
