import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Drawer from '@mui/material/Drawer'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import CloseIcon from '@mui/icons-material/Close'
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
import { apiErrorCode, apiErrorMessage } from '../../../services/apiErrorDetails'
import { notify } from '../../../stores/notificationStore'
import MetaBadge from '../../MetaBadge'
import SectionHead from '../../SectionHead'
import StatusLine from '../../StatusLine'
import DirectoryConnectorSection from './DirectoryConnectorSection'
import DirectorySyncReportView from './DirectorySyncReportView'
import DirectorySyncScheduleSection from './DirectorySyncScheduleSection'
import PendingPlanSection from './PendingPlanSection'
import { agoLabel, attentionOf, runBlocker, syncStateOf } from './directorySyncState'
import {
  DIRECTORY_SYNC_CONFLICT_MESSAGES,
  formatDateTime,
  formatFraction,
  outcomeLabel,
  outcomeTone,
} from './directorySyncLabels'

type ResultKind = 'DRY_RUN' | 'RUN' | 'CONFIRM'

const RESULT_TITLES: Record<ResultKind, string> = {
  DRY_RUN: 'Ergebnis des Trockenlaufs',
  RUN: 'Ergebnis des Laufs',
  CONFIRM: 'Ergebnis der Bestätigung',
}

function PanelSection({ title, children }: { title: string; children: ReactNode }) {
  const id = useId()
  return (
    <Box component="section" aria-labelledby={id}>
      <SectionHead id={id} component="h3">
        {title}
      </SectionHead>
      {children}
    </Box>
  )
}

interface DirectorySyncPanelProps {
  /** The provider to show; null keeps the panel closed. */
  provider: OidcProviderResponse | null
  status: DirectorySyncStatusResponse | undefined
  /** The loaded pending plan; null while none is loaded or none waits. */
  plan: DirectorySyncPendingPlanResponse | null
  onPlanReplaced: (plan: DirectorySyncPendingPlanResponse | null) => void
  onClose: () => void
  /** Reloads providers and status; the panel reads the new state from its props afterwards. */
  onChanged: () => Promise<void>
}

/**
 * Everything about one provider's directory sync (#2498): a pending plan first, because it waits
 * for a decision, then the last run with the manual runs, the schedule and the directory access.
 * The overview only shows; changes happen here.
 */
export default function DirectorySyncPanel({
  provider,
  status,
  plan,
  onPlanReplaced,
  onClose,
  onChanged,
}: DirectorySyncPanelProps) {
  const [result, setResult] = useState<{
    kind: ResultKind
    report: DirectorySyncReportResponse
  } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const errorRef = useRef<HTMLDivElement>(null)

  // After a failed action the focus goes to the message (docs/design/accessibility.md, 2.7).
  useEffect(() => {
    if (error) errorRef.current?.focus()
  }, [error])

  if (!provider) return <Drawer anchor="right" open={false} onClose={onClose} />

  const providerId = provider.id
  const pendingSummary = status?.pendingPlan ?? null
  const state = syncStateOf(provider)
  const attention = attentionOf(provider, status)
  const blocker = runBlocker(provider)

  /**
   * A refused confirmation leaves a **new** plan with a new id behind on the server
   * (`DIRECTORY_SYNC_PLAN_CHANGED`). The error path loads it, so the next confirmation does not
   * run into the old id (404) and the report shown is the one that would now be applied.
   */
  async function run(action: () => Promise<void>, fallback: string): Promise<boolean> {
    setBusy(true)
    setError(null)
    try {
      await action()
      return true
    } catch (err) {
      setError(apiErrorMessage(err, DIRECTORY_SYNC_CONFLICT_MESSAGES, fallback))
      if (apiErrorCode(err) === 'DIRECTORY_SYNC_PLAN_CHANGED') {
        onPlanReplaced(await getPendingPlan(providerId).catch(() => null))
        await onChanged()
      }
      return false
    } finally {
      setBusy(false)
    }
  }

  const saveSchedule = (enabled: boolean, intervalMinutes: number | null, savedInterval: boolean) =>
    void run(async () => {
      await setDirectorySync(providerId, { enabled, intervalMinutes })
      if (savedInterval) notify('Das Intervall wurde gespeichert.', 'success')
      await onChanged()
    }, 'Der Abgleich konnte nicht umgeschaltet werden.')

  return (
    <Drawer
      anchor="right"
      open
      onClose={busy ? undefined : onClose}
      slotProps={{
        paper: {
          role: 'dialog',
          'aria-labelledby': 'directory-sync-panel-heading',
          sx: { width: { xs: '100%', sm: 560 }, display: 'flex', flexDirection: 'column' },
        },
      }}
    >
      <Box
        sx={{
          display: 'flex',
          alignItems: 'flex-start',
          gap: 1,
          px: 3,
          pt: 2.5,
          pb: 2,
          borderBottom: 1,
          borderColor: 'divider',
        }}
      >
        <Box sx={{ flex: 1, minWidth: 0 }}>
          <Typography
            id="directory-sync-panel-heading"
            component="h2"
            sx={{ fontSize: 18, fontWeight: 600, overflowWrap: 'anywhere' }}
          >
            {provider.displayName}
          </Typography>
          <Stack
            direction="row"
            spacing={1}
            sx={{ mt: 0.75, alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
          >
            <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
              Abgleich: {state.label}
            </Typography>
            {provider.isExternal && <MetaBadge>extern</MetaBadge>}
            {!provider.enabled && <MetaBadge>deaktiviert</MetaBadge>}
          </Stack>
        </Box>
        <IconButton aria-label="Schließen" onClick={onClose} disabled={busy} sx={{ mt: -0.5 }}>
          <CloseIcon />
        </IconButton>
      </Box>

      <Stack spacing={3.5} sx={{ flex: 1, overflowY: 'auto', px: 3, py: 2.5 }}>
        {error && (
          <Alert ref={errorRef} tabIndex={-1} severity="error" onClose={() => setError(null)}>
            {error}
          </Alert>
        )}

        {/* Shown only while the status names a pending plan - a confirmed or discarded one leaves
            with the next status reload. */}
        {pendingSummary && plan && (
          <PanelSection title="Ausstehender Plan">
            <PendingPlanSection
              key={plan.id}
              plan={plan}
              busy={busy}
              onConfirm={(reason) =>
                run(async () => {
                  setResult({
                    kind: 'CONFIRM',
                    report: await confirmPendingPlan(providerId, plan.id, reason),
                  })
                  notify('Der Plan wurde angewendet.', 'success')
                  await onChanged()
                }, 'Der Plan konnte nicht angewendet werden.')
              }
              onDiscard={(reason) =>
                run(async () => {
                  await discardPendingPlan(providerId, plan.id, reason)
                  onPlanReplaced(null)
                  notify('Der Plan wurde verworfen.', 'success')
                  await onChanged()
                }, 'Der Plan konnte nicht verworfen werden.')
              }
            />
          </PanelSection>
        )}
        {pendingSummary && !plan && (
          <Typography sx={{ color: 'text.secondary', fontSize: 13 }}>
            Der ausstehende Plan wird geladen …
          </Typography>
        )}

        <PanelSection title="Letzter Lauf">
          <StatusLine
            headline={outcomeLabel(status?.lastOutcome)}
            tone={outcomeTone(status?.lastOutcome)}
            detail={
              <>
                {status?.lastMessage ?? 'Für diesen Anbieter lief noch kein Abgleich.'}
                {status?.lastRunAt &&
                  ` · ${formatDateTime(status.lastRunAt)} (${agoLabel(status.lastRunAt)})`}
                {status?.lastChangedFraction !== null &&
                  status?.lastChangedFraction !== undefined &&
                  ` · Änderungsanteil ${formatFraction(status.lastChangedFraction)}`}
              </>
            }
          />
          {/* A failed run speaks in the status line itself; only a missing access needs a word. */}
          {attention && attention.tone === 'warning' && !pendingSummary && (
            <Alert severity={attention.tone} sx={{ mt: 1.5 }}>
              {attention.detail}
            </Alert>
          )}
          <Stack direction="row" spacing={1} sx={{ mt: 2 }}>
            <Button
              variant="outlined"
              size="small"
              disabled={busy || Boolean(blocker)}
              onClick={() =>
                void run(async () => {
                  setResult({ kind: 'DRY_RUN', report: await runDirectorySyncDryRun(providerId) })
                }, 'Der Trockenlauf ist fehlgeschlagen.')
              }
            >
              Trockenlauf
            </Button>
            <Button
              variant="outlined"
              size="small"
              disabled={busy || Boolean(blocker)}
              onClick={() =>
                void run(async () => {
                  setResult({ kind: 'RUN', report: await runDirectorySync(providerId) })
                  await onChanged()
                }, 'Der Lauf ist fehlgeschlagen.')
              }
            >
              Jetzt abgleichen
            </Button>
          </Stack>
          <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mt: 1 }}>
            {blocker ??
              'Der Trockenlauf zeigt, was sich ändern würde, und schreibt nichts. „Jetzt abgleichen“ wendet an – oder legt einen Plan vor, wenn der Lauf zu viel entziehen würde.'}
          </Typography>
          {result && (
            <Box
              component="section"
              aria-label={RESULT_TITLES[result.kind]}
              sx={{ mt: 2, pt: 1.5, borderTop: 1, borderColor: 'divider' }}
            >
              <Typography component="h4" sx={{ fontSize: 13, fontWeight: 600 }}>
                {RESULT_TITLES[result.kind]}
              </Typography>
              <DirectorySyncReportView report={result.report} />
            </Box>
          )}
        </PanelSection>

        <PanelSection title="Zeitplan">
          <DirectorySyncScheduleSection
            provider={provider}
            status={status}
            busy={busy}
            onSave={saveSchedule}
          />
        </PanelSection>

        <PanelSection title="Verzeichniszugang">
          <DirectoryConnectorSection provider={provider} onChanged={onChanged} />
        </PanelSection>

        <Link
          component={RouterLink}
          to={`/admin/identity-providers/${provider.id}/groups`}
          sx={{ fontSize: 13.5, alignSelf: 'flex-start' }}
        >
          Arbeitsliste der Gruppen dieses Anbieters
        </Link>
      </Stack>
    </Drawer>
  )
}
