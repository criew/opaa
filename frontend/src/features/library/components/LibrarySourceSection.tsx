import { useEffect, useRef, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type {
  ConnectionProfileRef,
  SourceTypeKey,
  IndexingRunResponse,
  LibrarySchedule,
  LibrarySourceConnection,
  SourceBlock,
} from '@/shared/api/types'
import { useIndexingStore } from '../stores/indexingStore'
import { formatCalendarDay, formatFileSize } from '@/shared/utils/labels'
import {
  indexingRunEventCategoryLabel,
  indexingRunModeLabel,
  indexingTriggerSourceLabel,
} from './indexingLabels'
import { scheduleFrequencyLabel } from './scheduleLabels'
import EditLibraryScheduleDialog from './EditLibraryScheduleDialog'
import PageSection from '@/shared/ui/PageSection'
import { CONTENT_WIDTH } from './setup/rowStyles'
import LibraryConnectionPanel from './LibraryConnectionPanel'
import LibrarySourceRows from './LibrarySourceRows'
import type { SourceSetupHandle } from './setup/SourceSetupRows'
import LibrarySourceConsent from './LibrarySourceConsent'
import ReconnectSourceDialog from './ReconnectSourceDialog'
import { Link as RouterLink } from 'react-router'
import { CONNECTED_ACCOUNTS_ROUTE } from '@/shared/routes'
import { sourceRegistration } from './sources/registry'
import {
  LIBRARY_QUOTA_EXHAUSTED_REMEDY,
  QUOTA_EXHAUSTED_LABEL,
  QUOTA_EXHAUSTED_REMEDY,
} from './privateLibrary'
import StateDot from '@/shared/ui/StateDot'
import { plainMessage } from './sourceKit/plainMessage'
import type { StatusTone } from '@/shared/ui/StatusLine'
import ScopeConsequence from './sources/ScopeConsequence'

export interface LibrarySourceSectionProps {
  libraryId: string
  library: {
    name: string
    description?: string | null
    sourceType: SourceTypeKey
    sourcePath?: string | null
    sourceUrl?: string | null
    sourceCredentialsSet?: boolean | null
    sourceSettings?: Record<string, unknown> | null
    pushSecretSet?: boolean | null
    fullSyncIntervalDefaultDays?: number | null
    schedule?: LibrarySchedule | null
    lastScheduledRunsFailed?: boolean | null
    connectionProfile?: ConnectionProfileRef | null
    connectionProfileRemoved?: boolean
    sourceBlock?: SourceBlock | null
    /** The library's own source connection; only its managers receive it. */
    sourceConnection?: LibrarySourceConnection | null
    privateLibrary?: boolean
  }
  canEditSource: boolean
  /** A private library marked for erasure: no source, profile or schedule change any more. */
  erasing?: boolean
}

/**
 * The Reiter „Quelle" of a connector library: the rows Verbindung, Umfang and Zeitplan of its
 * setup, then - behind the MANAGER bar - webhook or events and the **Läufe**. A type this client
 * has no registration for shows **Anbindung** without a configuration and its **Zeitplan**.
 * Triggering and the live progress live in the page head, visible from every area.
 */
export default function LibrarySourceSection({
  libraryId,
  library,
  canEditSource,
  erasing = false,
}: LibrarySourceSectionProps) {
  const [editScheduleOpen, setEditScheduleOpen] = useState(false)
  const [connectOpen, setConnectOpen] = useState(false)
  const [reconnectOpen, setReconnectOpen] = useState(false)
  const rowsRef = useRef<SourceSetupHandle>(null)
  const connectAction = (
    <Button
      color="inherit"
      size="small"
      onClick={() => {
        // the dialog belongs to the row „Verbindung“, which opens with it
        rowsRef.current?.openRow('connection')
        setConnectOpen(true)
      }}
    >
      Zugang zuordnen
    </Button>
  )
  const block = library.sourceBlock ?? null
  const accountAction = (
    <Button color="inherit" size="small" component={RouterLink} to={CONNECTED_ACCOUNTS_ROUTE}>
      {block?.reason === 'NOT_CONNECTED' ? 'Konto verbinden' : 'Konto neu verbinden'}
    </Button>
  )
  const consent = library.sourceConnection ?? null
  const consentProfile = library.connectionProfile ?? null
  const reconnectLabel =
    block?.reason === 'NOT_CONNECTED' && !consent ? 'Quelle verbinden' : 'Quelle neu verbinden'
  const reconnectAction = consentProfile ? (
    <Button color="inherit" size="small" onClick={() => setReconnectOpen(true)}>
      {reconnectLabel}
    </Button>
  ) : undefined
  const configuration = sourceRegistration(library.sourceType)?.configuration ?? null
  const editAction = (
    <Button color="inherit" size="small" onClick={() => rowsRef.current?.openRow('connection')}>
      Quelle bearbeiten
    </Button>
  )
  // The action that lifts the block comes with it; only those who manage the source act on it.
  const blockAction = (() => {
    if (!canEditSource || erasing || !block?.action) return undefined
    const action = block.action
    switch (action) {
      case 'ASSIGN_PROFILE':
        return connectAction
      case 'CONNECT_OWN_ACCOUNT':
        return accountAction
      case 'EDIT_SOURCE':
        return configuration ? editAction : undefined
      case 'CONNECT_SOURCE':
        return reconnectAction
      default: {
        const unknown: never = action
        throw new Error(`Unknown source block action ${String(unknown)}`)
      }
    }
  })()
  const Push = configuration?.Push

  return (
    <Stack sx={{ maxWidth: CONTENT_WIDTH }}>
      {library.sourceBlock?.notice && (
        <Alert
          severity="warning"
          sx={{ mb: 2 }}
          data-testid="source-lock-notice"
          action={blockAction}
        >
          {library.sourceBlock.contentDeletedOn && (
            <AlertTitle>
              Löschung ab dem {formatCalendarDay(library.sourceBlock.contentDeletedOn)}
            </AlertTitle>
          )}
          {library.sourceBlock.notice}
        </Alert>
      )}
      {canEditSource &&
        !erasing &&
        consentProfile &&
        (consent || block?.action === 'CONNECT_SOURCE') && (
          <ReconnectSourceDialog
            // a fresh instance on every opening starts unconfirmed
            key={reconnectOpen ? 'reconnect-open' : 'reconnect-closed'}
            open={reconnectOpen}
            onClose={() => setReconnectOpen(false)}
            title={consent ? 'Quelle neu verbinden' : reconnectLabel}
            libraryId={libraryId}
            profileId={consentProfile.id}
            profileName={consentProfile.name}
          />
        )}
      {library.connectionProfileRemoved && (
        <Alert
          severity="warning"
          sx={{ mb: 2 }}
          data-testid="connection-profile-removed"
          action={canEditSource ? connectAction : undefined}
        >
          Zugang entfernt: Der Zugang dieser Bibliothek wurde von der Systemverwaltung gelöscht. Der
          Inhalt bleibt durchsuchbar, wird aber nicht mehr aktualisiert. Die Verwaltenden der
          Bibliothek ordnen sie einem anderen Zugang zu oder löschen sie.
        </Alert>
      )}
      {configuration ? (
        <Box sx={{ mb: 4 }}>
          <LibrarySourceRows
            // a fresh instance whenever the stored source changes, so the rows start from it; the
            // next run time moves after every run and must not discard a row being edited
            key={JSON.stringify([
              library.sourceUrl,
              library.sourcePath,
              library.sourceSettings,
              library.schedule && { ...library.schedule, nextRunAt: undefined },
              library.sourceCredentialsSet,
              library.connectionProfile?.id,
            ])}
            ref={rowsRef}
            libraryId={libraryId}
            library={library}
            canEdit={canEditSource && !erasing}
            connectionPrelude={
              canEditSource && !erasing ? (
                <Stack spacing={1.5}>
                  <LibraryConnectionPanel
                    libraryId={libraryId}
                    sourceType={library.sourceType}
                    library={library}
                    connectionProfile={library.connectionProfile}
                    connectionProfileRemoved={library.connectionProfileRemoved}
                    privateLibrary={Boolean(library.privateLibrary)}
                    onEditSource={() => rowsRef.current?.openRow('connection')}
                    dialogOpen={connectOpen}
                    onDialogOpenChange={setConnectOpen}
                  />
                  {consent && (
                    <LibrarySourceConsent
                      libraryId={libraryId}
                      consent={consent}
                      onReconnect={consentProfile ? () => setReconnectOpen(true) : undefined}
                    />
                  )}
                </Stack>
              ) : undefined
            }
          />
          {/* ADR-0023 / ADR-0027: the release sequence of the scope applies to every reader */}
          {configuration.scopeNoteId && (
            <ScopeConsequence testId={configuration.scopeNoteId}>
              Alles, was daraus indiziert wird, ist für alle Leseberechtigten dieser Bibliothek
              sichtbar – unabhängig von den Rechten in der Quelle.
            </ScopeConsequence>
          )}
          {Push && canEditSource && !erasing && (
            <Box sx={{ mt: 3 }}>
              <Push library={library} libraryId={libraryId} />
            </Box>
          )}
          {!canEditSource && (
            <Typography variant="body2" sx={{ color: 'text.secondary', mt: 2 }}>
              Verbindung, Zeitplan und Läufe sehen nur Verwaltende.
            </Typography>
          )}
        </Box>
      ) : (
        <>
          {!canEditSource && (
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
              Anbindung, Zeitplan und Läufe dieser Bibliothek sehen nur Verwaltende.
            </Typography>
          )}

          {canEditSource && !erasing && (
            <>
              <PageSection title="Anbindung">
                <Stack spacing={1}>
                  <LibraryConnectionPanel
                    libraryId={libraryId}
                    sourceType={library.sourceType}
                    library={library}
                    connectionProfile={library.connectionProfile}
                    connectionProfileRemoved={library.connectionProfileRemoved}
                    privateLibrary={Boolean(library.privateLibrary)}
                    dialogOpen={connectOpen}
                    onDialogOpenChange={setConnectOpen}
                  />
                  {consent && (
                    <LibrarySourceConsent
                      libraryId={libraryId}
                      consent={consent}
                      onReconnect={consentProfile ? () => setReconnectOpen(true) : undefined}
                    />
                  )}
                  <Typography variant="body2" data-testid="source-not-configurable">
                    Für diese Quellart gibt es in dieser Oberfläche keine Eingabemaske; ihre
                    Anbindung ist hier nicht konfigurierbar.
                  </Typography>
                </Stack>
              </PageSection>

              <PageSection
                title="Zeitplan"
                action={
                  <Button
                    size="small"
                    variant="outlined"
                    onClick={() => setEditScheduleOpen(true)}
                    aria-label="Zeitplan bearbeiten"
                    sx={{ flexShrink: 0 }}
                  >
                    Bearbeiten
                  </Button>
                }
              >
                <Typography variant="body2">
                  {scheduleFrequencyLabel(library.schedule?.frequency ?? 'DISABLED')}
                </Typography>
                {library.schedule?.nextRunAt && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    Nächster geplanter Lauf:{' '}
                    {new Date(library.schedule.nextRunAt).toLocaleString('de-DE', {
                      dateStyle: 'medium',
                      timeStyle: 'short',
                    })}
                  </Typography>
                )}
                {library.lastScheduledRunsFailed && (
                  <Alert severity="warning" sx={{ mt: 1 }}>
                    Die letzten geplanten Läufe dieser Bibliothek sind fehlgeschlagen. Der Zeitplan
                    bleibt aktiv und versucht es beim nächsten Termin erneut.
                  </Alert>
                )}
                <EditLibraryScheduleDialog
                  // a fresh instance on every opening starts from the stored schedule
                  key={editScheduleOpen ? 'schedule-edit-open' : 'schedule-edit-closed'}
                  open={editScheduleOpen}
                  onClose={() => setEditScheduleOpen(false)}
                  libraryId={libraryId}
                  schedule={library.schedule}
                  library={library}
                />
              </PageSection>
            </>
          )}
        </>
      )}

      {canEditSource && (
        <Box component="section" aria-labelledby="library-runs-title">
          {/* the same heading as the rows above, no overline */}
          <Typography
            id="library-runs-title"
            component="h3"
            sx={{ fontSize: 15, fontWeight: 600, mb: 1.5, color: 'text.primary' }}
          >
            Läufe
          </Typography>
          <LibraryIndexingHistorySection
            libraryId={libraryId}
            sourceType={library.sourceType}
            privateLibrary={Boolean(library.privateLibrary)}
          />
        </Box>
      )}
    </Stack>
  )
}

function formatRunTimestamp(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

// A run's state is a dot before its word (guidelines 5.5); a running run is no warning.
/** A completed run that still failed on documents or content: not shown as plain success. */
function partlyFailed(run: IndexingRunResponse): boolean {
  return run.status === 'COMPLETED' && (run.documentsFailed > 0 || countUnreadable(run.events) > 0)
}

function runStatusTone(status: IndexingRunResponse['status']): StatusTone {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'error'
  return 'neutral'
}

// ADR-0023: a run that could not read a space or a page must not look like any other
// completed run - the header names how many of its events are about unreadable content.
function countUnreadable(events: IndexingRunResponse['events']): number {
  return events.filter((e) => e.category === 'REJECTED' || e.category === 'UNREACHABLE').length
}

// The operator's line on what a run cost. Every connector records the attachment share and the
// duration; requests and throttles only appear when a source actually counted them (Confluence).
function runMetricsLabel(run: IndexingRunResponse): string | null {
  const metrics = run.metrics
  if (!metrics) return null
  const parts: string[] = []
  if (metrics.requestsSent > 0 || metrics.throttleCount > 0) {
    parts.push(`${metrics.requestsSent} Anfragen an die Quelle`)
  }
  if (metrics.throttleCount > 0) {
    parts.push(
      `${metrics.throttleCount}-mal gedrosselt (${metrics.throttleWaitSeconds} s gewartet)`,
    )
  }
  if (metrics.bytesDownloaded) {
    parts.push(`${formatFileSize(metrics.bytesDownloaded)} geladen`)
  }
  parts.push(
    `Anhänge: ${metrics.attachmentsProcessed} indiziert, ${metrics.attachmentsSkipped} übersprungen, ${metrics.attachmentsFailed} fehlgeschlagen`,
  )
  if (run.completedAt) {
    const seconds = Math.max(
      0,
      Math.round((new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()) / 1000),
    )
    const hours = Math.floor(seconds / 3600)
    const minutes = Math.floor((seconds % 3600) / 60)
    parts.push(
      hours > 0 ? `Dauer ${hours} h ${minutes} min` : `Dauer ${minutes} min ${seconds % 60} s`,
    )
  }
  return parts.join(' · ')
}

function RunMetricsLine({ run }: { run: IndexingRunResponse }) {
  const label = runMetricsLabel(run)
  if (!label) return null
  return (
    <Typography
      variant="body2"
      sx={{ color: 'text.secondary', mb: 1.5 }}
      data-testid={`run-metrics-${run.id}`}
    >
      {label}
    </Typography>
  )
}

/** The run ended at a storage quota: the library's own or, for a private one, its owner's. */
function quotaExhausted(run: IndexingRunResponse): boolean {
  return run.failureCategory === 'QUOTA_EXHAUSTED'
}

function runStatusLabel(status: IndexingRunResponse['status']): string {
  if (status === 'COMPLETED') return 'Abgeschlossen'
  if (status === 'FAILED') return 'Fehlgeschlagen'
  if (status === 'RUNNING') return 'Läuft'
  return 'Nie ausgeführt'
}

// A stable module-level reference (not a fresh `[]` literal per render) - a Zustand selector must
// never return a new array identity for an unchanged state slice, or useSyncExternalStore treats
// every render as a change and re-renders in an infinite loop.
const EMPTY_RUN_HISTORY: IndexingRunResponse[] = []

// Collapsible log of a library's recent runs - header data always visible, the
// event list only after expanding. Separate from the progress display in the page header, whose
// runsByLibrary carries only the current/last run.
function LibraryIndexingHistorySection({
  libraryId,
  sourceType,
  privateLibrary,
}: {
  libraryId: string
  sourceType: SourceTypeKey
  privateLibrary: boolean
}) {
  // both quotas share the run category: a private library may have hit either, a shared one only
  // its own
  const quotaRemedy = privateLibrary ? QUOTA_EXHAUSTED_REMEDY : LIBRARY_QUOTA_EXHAUSTED_REMEDY
  const runs = useIndexingStore((s) => s.runHistoryByLibrary[libraryId] ?? EMPTY_RUN_HISTORY)
  // ADR-0023, Entscheidung 4: only a source with two Betriebsarten shows the mode - for every other
  // type it is implied by the source type and a chip would only repeat it.
  const showRunMode = Boolean(sourceRegistration(sourceType)?.configuration?.fullSyncRhythm)
  const loadRunHistory = useIndexingStore((s) => s.loadRunHistory)

  useEffect(() => {
    void loadRunHistory(libraryId)
  }, [libraryId, loadRunHistory])

  return (
    <Box>
      {runs.length === 0 ? (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Noch keine Läufe.
        </Typography>
      ) : (
        <Stack spacing={1}>
          {runs.map((run) => (
            <Accordion key={run.id}>
              <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                <Stack
                  direction="row"
                  spacing={1.5}
                  sx={{ alignItems: 'center', flexWrap: 'wrap', width: '100%' }}
                >
                  <StateDot
                    tone={partlyFailed(run) ? 'warning' : runStatusTone(run.status)}
                    label={
                      partlyFailed(run) ? 'Teilweise fehlgeschlagen' : runStatusLabel(run.status)
                    }
                  />
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    {formatRunTimestamp(run.startedAt)}
                  </Typography>
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    {indexingTriggerSourceLabel(run.triggeredBy)}
                  </Typography>
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    {run.documentCount} verarbeitet
                    {run.documentsSkipped > 0 ? `, ${run.documentsSkipped} übersprungen` : ''}
                    {run.documentsFailed > 0 ? `, ${run.documentsFailed} fehlgeschlagen` : ''}
                  </Typography>
                  {showRunMode && (
                    <Typography
                      variant="body2"
                      sx={{ color: 'text.secondary' }}
                      data-testid={`run-mode-${run.id}`}
                    >
                      {indexingRunModeLabel(run.runMode)}
                    </Typography>
                  )}
                  {run.incomplete && (
                    <Typography
                      variant="body2"
                      sx={{ color: 'text.secondary' }}
                      data-testid={`run-incomplete-${run.id}`}
                    >
                      {quotaExhausted(run)
                        ? QUOTA_EXHAUSTED_LABEL
                        : 'unvollständig, wird fortgesetzt'}
                    </Typography>
                  )}
                  {run.unreadableScopeCount != null && run.unreadableScopeCount > 0 && (
                    <Typography
                      variant="body2"
                      sx={{ color: 'text.secondary' }}
                      data-testid={`run-unreadable-${run.id}`}
                    >
                      {`${run.unreadableScopeCount} ${run.unreadableScopeCount === 1 ? 'Bereich' : 'Bereiche'} nicht lesbar`}
                    </Typography>
                  )}
                </Stack>
              </AccordionSummary>
              <AccordionDetails>
                {quotaExhausted(run) && (
                  <Alert
                    severity="warning"
                    sx={{ mb: 1.5 }}
                    data-testid={`run-quota-remedy-${run.id}`}
                  >
                    {quotaRemedy}
                  </Alert>
                )}
                {run.message && (
                  <Typography variant="body2" sx={{ color: 'text.secondary', mb: 1.5 }}>
                    {plainMessage(run.message)}
                  </Typography>
                )}
                <RunMetricsLine run={run} />
                {run.events.length === 0 ? (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    Nichts übersprungen oder fehlgeschlagen.
                  </Typography>
                ) : (
                  <Stack spacing={1}>
                    {run.events.map((event, index) => (
                      // message/reference are already German and scrubbed of raw
                      // challenge-/redirect-URLs by the backend. Events carry no id of their own,
                      // so the key combines the run and the position within its event list.
                      <Box key={`${run.id}-${index}`}>
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
                          <Typography variant="body2" sx={{ fontWeight: 600, flexShrink: 0 }}>
                            {indexingRunEventCategoryLabel(event.category)}
                          </Typography>
                          <Typography variant="body2">{plainMessage(event.message)}</Typography>
                        </Stack>
                        {event.reference && (
                          <Typography
                            variant="caption"
                            sx={{
                              color: 'text.secondary',
                              display: 'block',
                              wordBreak: 'break-word',
                            }}
                          >
                            {event.reference}
                          </Typography>
                        )}
                      </Box>
                    ))}
                    {run.eventsTruncatedCount > 0 && (
                      <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                        … und {run.eventsTruncatedCount} weitere
                      </Typography>
                    )}
                  </Stack>
                )}
              </AccordionDetails>
            </Accordion>
          ))}
        </Stack>
      )}
    </Box>
  )
}
