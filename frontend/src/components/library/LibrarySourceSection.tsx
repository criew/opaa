import { useEffect, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
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
} from '../../types/api'
import { useIndexingStore } from '../../stores/indexingStore'
import {
  formatCalendarDay,
  formatFileSize,
  indexingRunEventCategoryLabel,
  indexingRunModeLabel,
  indexingTriggerSourceLabel,
  scheduleFrequencyLabel,
} from '../../utils/labels'
import EditLibrarySourceDialog from '../EditLibrarySourceDialog'
import EditLibraryScheduleDialog from '../EditLibraryScheduleDialog'
import PageSection from '../PageSection'
import LibraryConnectionPanel from './LibraryConnectionPanel'
import LibrarySourceConsent from './LibrarySourceConsent'
import ReconnectSourceDialog from './ReconnectSourceDialog'
import { Link as RouterLink } from 'react-router'
import { CONNECTED_ACCOUNTS_ROUTE } from '../../routes'
import { sourceRegistration } from './sources/registry'
import {
  LIBRARY_QUOTA_EXHAUSTED_REMEDY,
  QUOTA_EXHAUSTED_LABEL,
  QUOTA_EXHAUSTED_REMEDY,
} from './privateLibrary'

export interface LibrarySourceSectionProps {
  libraryId: string
  library: {
    name: string
    description?: string | null
    sourceType: SourceTypeKey
    sourcePath?: string | null
    sourceUrl?: string | null
    sourceProxy?: string | null
    sourceInsecureSsl?: boolean | null
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
 * The Reiter „Quelle" of a connector library (#1940), in four sections: **Umfang** for every
 * reader, then - behind the MANAGER bar (#507/#604) - **Anbindung** (source configuration,
 * connection test, webhook or events), **Zeitplan** and **Läufe**. Triggering and the live progress
 * live in the page head, visible from every area.
 */
export default function LibrarySourceSection({
  libraryId,
  library,
  canEditSource,
  erasing = false,
}: LibrarySourceSectionProps) {
  const [editSourceOpen, setEditSourceOpen] = useState(false)
  const [editScheduleOpen, setEditScheduleOpen] = useState(false)
  const [connectOpen, setConnectOpen] = useState(false)
  const [reconnectOpen, setReconnectOpen] = useState(false)
  const connectAction = (
    <Button color="inherit" size="small" onClick={() => setConnectOpen(true)}>
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
    <Button color="inherit" size="small" onClick={() => setEditSourceOpen(true)}>
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
  const Scope = configuration?.Scope
  const StoredView = configuration?.StoredView
  const fullSyncIntervalDays = configuration?.fullSyncRhythm
    ? ((library.sourceSettings?.fullSyncIntervalDays as number | null | undefined) ?? null)
    : null

  return (
    <Stack>
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
      {/* #1138 (ADR-0023) / ADR-0027: Umfang und seine Freigabefolge gelten für alle
          Leseberechtigten - seit #1939 stehen sie hier statt im Kopf, mit Erklärung. */}
      {Scope && (
        <PageSection
          title="Umfang"
          description="Was diese Bibliothek aus ihrer Quelle aufnimmt — ihr Geltungsbereich. Er gilt für alle Leseberechtigten der Bibliothek."
        >
          <Stack spacing={1}>
            <Scope library={library} />
          </Stack>
        </PageSection>
      )}

      {!canEditSource && (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Anbindung, Zeitplan und Läufe dieser Bibliothek sehen nur Verwaltende.
        </Typography>
      )}

      {canEditSource && !erasing && (
        <>
          <PageSection
            title="Anbindung"
            description="Woher diese Bibliothek ihre Dokumente bezieht und wie die Quelle erreicht wird. Nur für Verwaltende sichtbar."
            action={
              configuration ? (
                <Button
                  size="small"
                  variant="outlined"
                  onClick={() => setEditSourceOpen(true)}
                  aria-label="Quellkonfiguration bearbeiten"
                  sx={{ flexShrink: 0 }}
                >
                  Bearbeiten
                </Button>
              ) : undefined
            }
          >
            {/* #507: sourcePath/sourceUrl/sourceProxy expose internal server paths, source URLs and
                proxy hosts - the backend only serves them to a caller with at least MANAGER, and
                this whole area only renders behind the same bar. */}
            <Stack spacing={0.75}>
              <LibraryConnectionPanel
                libraryId={libraryId}
                sourceType={library.sourceType}
                library={library}
                connectionProfile={library.connectionProfile}
                connectionProfileRemoved={library.connectionProfileRemoved}
                privateLibrary={Boolean(library.privateLibrary)}
                onEditSource={() => setEditSourceOpen(true)}
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
              {StoredView ? (
                <StoredView library={library} libraryId={libraryId} />
              ) : (
                <Typography variant="body2" data-testid="source-not-configurable">
                  Für diese Quellart gibt es in dieser Oberfläche keine Eingabemaske; ihre Anbindung
                  ist hier nicht konfigurierbar.
                </Typography>
              )}
            </Stack>
            {configuration && (
              <EditLibrarySourceDialog
                // Forces a remount every time the dialog opens, so its internal field state always
                // starts fresh from the current library configuration without an effect calling
                // setState on open (react-hooks/set-state-in-effect).
                key={editSourceOpen ? 'source-edit-open' : 'source-edit-closed'}
                open={editSourceOpen}
                onClose={() => setEditSourceOpen(false)}
                libraryId={libraryId}
                library={library}
              />
            )}
          </PageSection>

          {/* #485: Zeitplan - dieselbe Schwelle wie die Anbindung (canEditSource). */}
          <PageSection
            title="Zeitplan"
            description="Wann diese Bibliothek automatisch indiziert wird."
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
            {configuration?.fullSyncRhythm && (
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                {(() => {
                  const days = fullSyncIntervalDays ?? library.fullSyncIntervalDefaultDays ?? 7
                  const rhythm =
                    days === 1 ? 'Vollabgleich täglich' : `Vollabgleich alle ${days} Tage`
                  return fullSyncIntervalDays == null ? `${rhythm} (Vorgabe der Instanz)` : rhythm
                })()}
              </Typography>
            )}
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
              // Mirrors EditLibrarySourceDialog's own remount-on-open pattern above.
              key={editScheduleOpen ? 'schedule-edit-open' : 'schedule-edit-closed'}
              open={editScheduleOpen}
              onClose={() => setEditScheduleOpen(false)}
              libraryId={libraryId}
              schedule={library.schedule}
              confluence={
                configuration?.fullSyncRhythm
                  ? {
                      intervalDays: fullSyncIntervalDays,
                      defaultDays: library.fullSyncIntervalDefaultDays ?? null,
                    }
                  : undefined
              }
              settingsBase={configuration?.rhythmSettingsBase?.(library) ?? undefined}
              library={library}
            />
          </PageSection>
        </>
      )}

      {canEditSource && (
        <PageSection
          title="Läufe"
          description="Jeder Lauf mit seinen Kennzahlen und seinem Protokoll — aufklappen für die Einzelheiten."
        >
          {configuration?.runsHint && (
            <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>
              {configuration.runsHint}
            </Typography>
          )}
          <LibraryIndexingHistorySection
            libraryId={libraryId}
            sourceType={library.sourceType}
            privateLibrary={Boolean(library.privateLibrary)}
          />
        </PageSection>
      )}
    </Stack>
  )
}

function formatRunTimestamp(value: string | null | undefined): string {
  if (!value) return '—'
  return new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
}

function runStatusChipColor(
  status: IndexingRunResponse['status'],
): 'success' | 'warning' | 'error' | 'default' {
  if (status === 'COMPLETED') return 'success'
  if (status === 'FAILED') return 'error'
  if (status === 'RUNNING') return 'warning'
  return 'default'
}

// #1138 (ADR-0023): a run that could not read a space or a page must not look like any other
// completed run - the header names how many of its events are about unreadable content.
function countUnreadable(events: IndexingRunResponse['events']): number {
  return events.filter((e) => e.category === 'REJECTED' || e.category === 'UNREACHABLE').length
}

function runEventsLabel(events: IndexingRunResponse['events']): string {
  const base = `${events.length} Ereignis${events.length === 1 ? '' : 'se'}`
  const unreadable = countUnreadable(events)
  return unreadable > 0 ? `${base}, davon ${unreadable} nicht lesbar` : base
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

// #513: einklappbares Protokoll der letzten Läufe einer Bibliothek - Kopfdaten immer sichtbar, die
// Ereignisliste nur nach dem Aufklappen. Getrennt von der Fortschrittsanzeige im Seitenkopf, deren
// runsByLibrary nur den aktuellen/letzten Lauf trägt.
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
          Es liegen noch keine Läufe vor. Der erste Lauf startet über „Jetzt indizieren“ oben auf
          der Seite oder über den Zeitplan.
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
                  <Chip
                    label={runStatusLabel(run.status)}
                    size="small"
                    color={runStatusChipColor(run.status)}
                    variant="outlined"
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
                    <Chip
                      label={indexingRunModeLabel(run.runMode)}
                      size="small"
                      variant="outlined"
                      data-testid={`run-mode-${run.id}`}
                    />
                  )}
                  {run.incomplete && (
                    <Chip
                      label={
                        quotaExhausted(run)
                          ? QUOTA_EXHAUSTED_LABEL
                          : 'unvollständig, wird fortgesetzt'
                      }
                      size="small"
                      color="warning"
                      data-testid={`run-incomplete-${run.id}`}
                    />
                  )}
                  {run.unreadableScopeCount != null && run.unreadableScopeCount > 0 && (
                    <Chip
                      label={`${run.unreadableScopeCount} ${run.unreadableScopeCount === 1 ? 'Bereich' : 'Bereiche'} nicht lesbar`}
                      size="small"
                      color="warning"
                      data-testid={`run-unreadable-${run.id}`}
                    />
                  )}
                  {run.events.length > 0 && (
                    <Chip
                      label={runEventsLabel(run.events)}
                      size="small"
                      color={countUnreadable(run.events) > 0 ? 'warning' : 'default'}
                    />
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
                    {run.message}
                  </Typography>
                )}
                <RunMetricsLine run={run} />
                {run.events.length === 0 ? (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    Dieser Lauf hat keine übersprungenen, fehlgeschlagenen oder abweichend erkannten
                    Elemente protokolliert.
                  </Typography>
                ) : (
                  <Stack spacing={1}>
                    {run.events.map((event, index) => (
                      // #513: message/reference are already German and scrubbed of raw
                      // challenge-/redirect-URLs by the backend. Events carry no id of their own,
                      // so the key combines the run and the position within its event list.
                      <Box key={`${run.id}-${index}`}>
                        <Stack direction="row" spacing={1} sx={{ alignItems: 'baseline' }}>
                          <Chip
                            label={indexingRunEventCategoryLabel(event.category)}
                            size="small"
                            variant="outlined"
                          />
                          <Typography variant="body2">{event.message}</Typography>
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
