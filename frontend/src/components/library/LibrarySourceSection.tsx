import { useEffect, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined'
import type {
  ConfluenceEdition,
  ConfluenceSpaceRef,
  DocumentSourceType,
  IndexingRunResponse,
  LibrarySchedule,
  S3Settings,
} from '../../types/api'
import { useIndexingStore } from '../../stores/indexingStore'
import {
  confluenceEditionLabel,
  documentSourceTypeConfigKind,
  formatFileSize,
  indexingRunEventCategoryLabel,
  indexingRunModeLabel,
  indexingTriggerSourceLabel,
  scheduleFrequencyLabel,
} from '../../utils/labels'
import type { GenericSourceValues } from '../../utils/librarySourceConfig'
import EditLibrarySourceDialog from '../EditLibrarySourceDialog'
import EditLibraryScheduleDialog from '../EditLibraryScheduleDialog'
import PageSection from '../PageSection'
import ConfluenceWebhookSection from './ConfluenceWebhookSection'
import S3EventSection from './S3EventSection'
import SourceConnectionTest from './SourceConnectionTest'

export interface LibrarySourceSectionProps {
  libraryId: string
  library: {
    name: string
    description?: string | null
    listed: boolean
    sourceType: DocumentSourceType
    sourcePath?: string | null
    sourceUrl?: string | null
    sourceProxy?: string | null
    sourceInsecureSsl?: boolean | null
    sourceCredentialsSet?: boolean | null
    confluenceEdition?: ConfluenceEdition | null
    confluenceSpaces?: ConfluenceSpaceRef[] | null
    confluenceWebhookSecretSet?: boolean | null
    s3EventsTokenSet?: boolean | null
    confluenceFullSyncIntervalDays?: number | null
    confluenceFullSyncIntervalDefaultDays?: number | null
    s3Settings?: S3Settings | null
    schedule?: LibrarySchedule | null
    lastScheduledRunsFailed?: boolean | null
  }
  canEditSource: boolean
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
}: LibrarySourceSectionProps) {
  const [editSourceOpen, setEditSourceOpen] = useState(false)
  const [editScheduleOpen, setEditScheduleOpen] = useState(false)
  const configKind = documentSourceTypeConfigKind[library.sourceType]
  const hasScope = configKind === 'confluence' || configKind === 's3'

  // The stored configuration as the connection test reads it; the credentials field stays blank,
  // which is exactly what makes the backend fall back to the stored ones (#1856).
  const storedSource: GenericSourceValues = {
    sourcePath: library.sourcePath ?? '',
    sourceUrl: library.sourceUrl ?? '',
    sourceProxy: library.sourceProxy ?? '',
    sourceCredentials: '',
    sourceInsecureSsl: Boolean(library.sourceInsecureSsl),
  }

  return (
    <Stack>
      {/* #1138 (ADR-0023) / ADR-0027: Umfang und seine Freigabefolge gelten für alle
          Leseberechtigten - seit #1939 stehen sie hier statt im Kopf, mit Erklärung. */}
      {hasScope && (
        <PageSection
          title="Umfang"
          description="Was diese Bibliothek aus ihrer Quelle aufnimmt — ihr Geltungsbereich. Er gilt für alle Leseberechtigten der Bibliothek."
        >
          <Stack spacing={1}>
            {configKind === 'confluence' && (
              <>
                <Typography variant="body2">
                  <strong>Edition:</strong>{' '}
                  {library.confluenceEdition
                    ? confluenceEditionLabel(library.confluenceEdition)
                    : '—'}{' '}
                  <Typography component="span" variant="caption" sx={{ color: 'text.secondary' }}>
                    (erkannt, nach der Anlage nicht änderbar)
                  </Typography>
                </Typography>
                <ConfluenceSpacesSummary spaces={library.confluenceSpaces} />
                <Stack
                  direction="row"
                  spacing={1}
                  role="note"
                  data-testid="confluence-sharing-consequence"
                  sx={{ alignItems: 'flex-start', mt: 0.5 }}
                >
                  <InfoOutlinedIcon
                    aria-hidden
                    sx={{ fontSize: 16, color: 'primary.main', mt: '2px', flexShrink: 0 }}
                  />
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    Ein Geltungsbereich ist der Ausschnitt der Quelle, den diese Bibliothek spiegelt
                    — hier die ausgewählten Confluence-Spaces. Alles, was daraus indiziert wurde,
                    ist für alle Leseberechtigten dieser Bibliothek sichtbar — unabhängig davon, wer
                    es in Confluence lesen dürfte. Was das hinterlegte Dienstkonto in Confluence
                    nicht lesen darf, nimmt OPAA nicht auf: Seiten, die es gar nicht erst sieht,
                    tauchen nirgends auf; wo ein Abruf oder ein ganzer Space scheitert, weist das
                    Laufprotokoll das aus.
                  </Typography>
                </Stack>
              </>
            )}
            {configKind === 's3' && (
              <>
                <S3ScopesSummary settings={library.s3Settings} />
                <Stack
                  direction="row"
                  spacing={1}
                  role="note"
                  data-testid="s3-sharing-consequence"
                  sx={{ alignItems: 'flex-start', mt: 0.5 }}
                >
                  <InfoOutlinedIcon
                    aria-hidden
                    sx={{ fontSize: 16, color: 'primary.main', mt: '2px', flexShrink: 0 }}
                  />
                  <Typography sx={{ fontSize: 12.5, color: 'text.secondary' }}>
                    Ein Geltungsbereich ist ein Bucket des Objektspeichers, wahlweise eingegrenzt
                    auf ein Präfix darin — nur was darunter liegt, nimmt diese Bibliothek auf.
                    Alles, was daraus indiziert wurde, ist für alle Leseberechtigten dieser
                    Bibliothek sichtbar — unabhängig davon, wer es im Objektspeicher lesen dürfte.
                    Was der hinterlegte Schlüssel nicht lesen darf, nimmt OPAA nicht auf.
                  </Typography>
                </Stack>
              </>
            )}
          </Stack>
        </PageSection>
      )}

      {!canEditSource && (
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Anbindung, Zeitplan und Läufe dieser Bibliothek sehen nur Verwaltende.
        </Typography>
      )}

      {canEditSource && (
        <>
          <PageSection
            title="Anbindung"
            description="Woher diese Bibliothek ihre Dokumente bezieht und wie die Quelle erreicht wird. Nur für Verwaltende sichtbar."
            action={
              <Button
                size="small"
                variant="outlined"
                onClick={() => setEditSourceOpen(true)}
                aria-label="Quellkonfiguration bearbeiten"
                sx={{ flexShrink: 0 }}
              >
                Bearbeiten
              </Button>
            }
          >
            {/* #507: sourcePath/sourceUrl/sourceProxy expose internal server paths, source URLs and
                proxy hosts - the backend only serves them to a caller with at least MANAGER, and
                this whole area only renders behind the same bar. */}
            <Stack spacing={0.75}>
              {configKind === 'path' && (
                <Typography variant="body2">
                  <strong>Verzeichnispfad:</strong> {library.sourcePath ?? '—'}
                </Typography>
              )}
              {configKind === 'url' && (
                <Typography variant="body2">
                  <strong>Adresse (URL):</strong> {library.sourceUrl ?? '—'}
                </Typography>
              )}
              {configKind === 'confluence' && (
                <Typography variant="body2">
                  <strong>Adresse:</strong> {library.sourceUrl ?? '—'}
                </Typography>
              )}
              {configKind === 's3' && (
                <>
                  <Typography variant="body2">
                    <strong>Endpoint:</strong> {library.sourceUrl ?? '—'}
                  </Typography>
                  <Typography variant="body2">
                    <strong>Region:</strong> {library.s3Settings?.region ?? 'us-east-1 (Vorgabe)'} ·{' '}
                    <strong>Adressstil:</strong>{' '}
                    {library.s3Settings?.pathStyle ? 'Path-Style' : 'Virtual-Host'}
                  </Typography>
                  {(library.s3Settings?.includePatterns?.length ?? 0) > 0 && (
                    <Typography variant="body2">
                      <strong>Einschlussmuster:</strong>{' '}
                      {library.s3Settings?.includePatterns?.join(', ')}
                    </Typography>
                  )}
                  {(library.s3Settings?.excludePatterns?.length ?? 0) > 0 && (
                    <Typography variant="body2">
                      <strong>Ausschlussmuster:</strong>{' '}
                      {library.s3Settings?.excludePatterns?.join(', ')}
                    </Typography>
                  )}
                </>
              )}
              {(configKind === 'url' || configKind === 'confluence' || configKind === 's3') && (
                <>
                  <Typography variant="body2">
                    <strong>Proxy:</strong> {library.sourceProxy ?? 'nicht konfiguriert'}
                  </Typography>
                  <Typography variant="body2">
                    <strong>Zertifikatsprüfung aussetzen:</strong>{' '}
                    {library.sourceInsecureSsl ? 'ja' : 'nein'}
                  </Typography>
                  <Typography variant="caption" sx={{ color: 'text.secondary' }}>
                    Zugangsdaten sind aus Sicherheitsgründen nie Teil einer API-Antwort - diese
                    Ansicht zeigt sie deshalb weder ein noch aus.
                  </Typography>
                </>
              )}
              {/* #1940: the stored configuration can be probed without opening the editor first -
                  the same button the Anlage-Assistent offers, here against what is saved. A
                  Confluence/S3 source is probed inside its own staged form, which needs the
                  credentials and the detected edition the read view does not carry. */}
              {(configKind === 'path' || configKind === 'url') && (
                <Box sx={{ pt: 1 }}>
                  <SourceConnectionTest
                    sourceType={library.sourceType}
                    values={storedSource}
                    libraryId={libraryId}
                    size="small"
                  />
                </Box>
              )}
              {configKind === 'confluence' && (
                <ConfluenceWebhookSection
                  libraryId={libraryId}
                  secretSet={library.confluenceWebhookSecretSet}
                />
              )}
              {configKind === 's3' && (
                <S3EventSection
                  libraryId={libraryId}
                  tokenSet={library.s3EventsTokenSet}
                  scopes={library.s3Settings?.scopes ?? []}
                />
              )}
            </Stack>
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
            {configKind === 'confluence' && (
              <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                {(() => {
                  const days =
                    library.confluenceFullSyncIntervalDays ??
                    library.confluenceFullSyncIntervalDefaultDays ??
                    7
                  const rhythm =
                    days === 1 ? 'Vollabgleich täglich' : `Vollabgleich alle ${days} Tage`
                  return library.confluenceFullSyncIntervalDays == null
                    ? `${rhythm} (Vorgabe der Instanz)`
                    : rhythm
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
                configKind === 'confluence'
                  ? {
                      intervalDays: library.confluenceFullSyncIntervalDays ?? null,
                      defaultDays: library.confluenceFullSyncIntervalDefaultDays ?? null,
                    }
                  : undefined
              }
              library={library}
            />
          </PageSection>

          <PageSection
            title="Läufe"
            description="Jeder Lauf mit seinen Kennzahlen und seinem Protokoll — aufklappen für die Einzelheiten."
          >
            {configKind === 'confluence' && (
              <Typography sx={{ fontSize: 12.5, color: 'text.secondary', mb: 2 }}>
                „Jetzt indizieren“ nimmt in der Regel nur Änderungen seit dem letzten Lauf auf. Nach
                einer Änderung der Space-Auswahl und im vom Betrieb eingestellten Abstand läuft es
                automatisch als Vollabgleich — dieser prüft alle ausgewählten Spaces vollständig und
                entfernt, was in Confluence nicht mehr vorhanden ist. „Vollabgleich starten“
                erzwingt ihn sofort.
              </Typography>
            )}
            <LibraryIndexingHistorySection libraryId={libraryId} sourceType={library.sourceType} />
          </PageSection>
        </>
      )}
    </Stack>
  )
}

// #1138 (ADR-0023): the selected spaces are the scope every reader of the library sees.
function ConfluenceSpacesSummary({ spaces }: { spaces?: ConfluenceSpaceRef[] | null }) {
  return (
    <Typography variant="body2" component="div">
      <strong>Ausgewählte Spaces:</strong>{' '}
      <Stack direction="row" spacing={0.5} useFlexGap component="span" sx={{ flexWrap: 'wrap' }}>
        {(spaces ?? []).map((space) => (
          <Chip
            key={space.key}
            size="small"
            label={space.name ? `${space.name} (${space.key})` : space.key}
          />
        ))}
      </Stack>
    </Typography>
  )
}

// ADR-0027: the scopes of an S3 library, one chip per bucket/prefix, for every reader.
function S3ScopesSummary({ settings }: { settings?: S3Settings | null }) {
  return (
    <Typography variant="body2" component="div">
      <strong>Geltungsbereiche:</strong>{' '}
      <Stack direction="row" spacing={0.5} useFlexGap component="span" sx={{ flexWrap: 'wrap' }}>
        {(settings?.scopes ?? []).map((scope) => (
          <Chip
            key={`${scope.bucket}/${scope.prefix ?? ''}`}
            size="small"
            label={scope.prefix ? `${scope.bucket}/${scope.prefix}` : scope.bucket}
            sx={{ fontFamily: 'monospace' }}
          />
        ))}
      </Stack>
    </Typography>
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
}: {
  libraryId: string
  sourceType: DocumentSourceType
}) {
  const runs = useIndexingStore((s) => s.runHistoryByLibrary[libraryId] ?? EMPTY_RUN_HISTORY)
  // ADR-0023, Entscheidung 4: only Confluence knows two Betriebsarten - for every other type the
  // mode is implied by the source type and a chip would only repeat it.
  const showRunMode = sourceType === 'CONFLUENCE'
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
                      label="unvollständig, wird fortgesetzt"
                      size="small"
                      color="warning"
                      data-testid={`run-incomplete-${run.id}`}
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
