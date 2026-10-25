import { useEffect, useRef, useState } from 'react'
import { useLocation, useSearchParams } from 'react-router'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  ConnectionProfileRef,
  SourceTypeKey,
  IndexingRunResponse,
  LibrarySchedule,
  LibrarySourceConnection,
  SourceBlock,
} from '@/shared/api/types'
import { formatCalendarDay } from '@/shared/utils/labels'
import DetailSection, { TextAction } from '@/shared/ui/DetailSection'
import LibraryRunList, { type RunningProgress } from './LibraryRunList'
import LibraryLiveUpdateRow from './LibraryLiveUpdateRow'
import { formatDay, runWord } from './libraryState'
import { unreadableScopeNames } from './scopeMarks'
import type { SourceSetupRowsMarks } from './setup/SourceSetupRows'
import type { RowStatus } from './setup/setupTypes'
import { scheduleFrequencyLabel } from './scheduleLabels'
import EditLibraryScheduleDialog from './EditLibraryScheduleDialog'
import PageSection from '@/shared/ui/PageSection'
import LibraryConnectionPanel from './LibraryConnectionPanel'
import LibrarySourceRows from './LibrarySourceRows'
import type { SourceSetupHandle } from './setup/SourceSetupRows'
import LibrarySourceConsent from './LibrarySourceConsent'
import ReconnectSourceDialog from './ReconnectSourceDialog'
import { Link as RouterLink } from 'react-router'
import { CONNECTED_ACCOUNTS_ROUTE } from '@/shared/routes'
import { sourceRegistration } from './sources/registry'
import { LIBRARY_QUOTA_EXHAUSTED_REMEDY, QUOTA_EXHAUSTED_REMEDY } from './privateLibrary'
import ScopeConsequence from './sources/ScopeConsequence'

const NO_RUNS: IndexingRunResponse[] = []
const LIVE_UPDATE_TYPES = new Set<SourceTypeKey>(['CONFLUENCE', 'S3'])
const NO_KEYS: string[] = []

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
  /** The last ten runs, newest first. */
  runs?: IndexingRunResponse[]
  /** The polled progress of a running run - the same count as the page head shows. */
  running?: RunningProgress | null
  /** Scope entries the last listing could not read. */
  unlistedScopeKeys?: string[]
  /** Starts a full reconciliation; absent where the source has none or the caller may not. */
  onFullSync?: () => void
  /** Polling the running run gave up; the last known state stays. */
  statusUnknown?: boolean
  onRetryStatus?: () => void
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
  runs = NO_RUNS,
  running = null,
  unlistedScopeKeys = NO_KEYS,
  onFullSync,
  statusUnknown = false,
  onRetryStatus,
}: LibrarySourceSectionProps) {
  const [searchParams] = useSearchParams()
  const location = useLocation()
  const marks = rowMarks(library, runs, unlistedScopeKeys)
  // the head's state leads here with an anchor: scroll its row into view
  useEffect(() => {
    const target = ANCHORS[location.hash.slice(1)]
    if (!target) return
    requestAnimationFrame(() =>
      document.getElementById(target)?.scrollIntoView?.({ block: 'start' }),
    )
  }, [location.hash])
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
      {configuration ? (
        <Box sx={{ mb: 5 }}>
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
            marks={{
              ...marks,
              extraRows:
                // only Confluence and S3 can report their changes as they happen
                canEditSource && LIVE_UPDATE_TYPES.has(library.sourceType) ? (
                  <LibraryLiveUpdateRow
                    libraryId={libraryId}
                    library={library}
                    canEdit={!erasing}
                  />
                ) : undefined,
            }}
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
        <DetailSection
          title="Läufe"
          id="library-runs"
          action={
            statusUnknown && onRetryStatus ? (
              <TextAction onClick={onRetryStatus}>Stand erneut abrufen</TextAction>
            ) : onFullSync && !erasing && runs.length > 0 ? (
              <TextAction onClick={onFullSync} disabled={running !== null}>
                Vollabgleich starten
              </TextAction>
            ) : undefined
          }
        >
          <LibraryRunList
            // a fresh list when the head's state asks for the newest run open
            key={searchParams.get('lauf') === 'neu' ? 'runs-newest' : 'runs'}
            runs={runs}
            running={running}
            showMode={Boolean(configuration?.fullSyncRhythm)}
            signsIn={configuration?.setup?.signsIn ?? true}
            fundortPrefix={
              library.sourceType === 'HTTP_DIRECTORY' || library.sourceType === 'RSS_FEED'
                ? (library.sourceUrl ?? '').replace(/[^/]*$/, '') || null
                : library.sourceType === 'FILESYSTEM' && library.sourcePath
                  ? `${library.sourcePath.replace(/\/$/, '')}/`
                  : null
            }
            unreadableNames={unreadableScopeNames(
              {
                sourceType: library.sourceType,
                sourceSettings: library.sourceSettings,
                sourcePath: library.sourcePath,
                sourceUrl: library.sourceUrl,
              },
              unlistedScopeKeys,
            )}
            quotaRemedy={
              library.privateLibrary ? QUOTA_EXHAUSTED_REMEDY : LIBRARY_QUOTA_EXHAUSTED_REMEDY
            }
            openNewest={searchParams.get('lauf') === 'neu'}
          />
        </DetailSection>
      )}
    </Stack>
  )
}

/** The anchors the head's state links to, mapped to the rows they mean. */
const ANCHORS: Record<string, string> = {
  verbindung: 'edit-source-row-connection',
  umfang: 'edit-source-row-scope',
  zeitplan: 'edit-source-row-schedule',
  laeufe: 'library-runs',
  live: 'edit-source-row-live',
}

/** What the runs know of the rows: the connection's last state, unreadable scope, failed plans. */
function rowMarks(
  library: LibrarySourceSectionProps['library'],
  runs: IndexingRunResponse[],
  unlistedScopeKeys: string[] | undefined,
): SourceSetupRowsMarks {
  const signsIn = sourceRegistration(library.sourceType)?.configuration?.setup?.signsIn ?? true
  // the last run that ended says what is known of the connection
  const finished = runs.find((run) => run.status !== 'RUNNING') ?? null
  const word = finished ? runWord(finished, signsIn) : null
  const connectionFailed =
    word === 'Anmeldung abgelehnt' ||
    word === 'Nicht erreichbar' ||
    word === 'Zustimmung abgelaufen'
  const storedStatus = ((): RowStatus | null => {
    if (!finished) return null
    if (connectionFailed) {
      return { tone: 'error', label: `${word} am ${formatDay(finished.startedAt)}` }
    }
    if (finished.status === 'FAILED') return null
    return {
      tone: 'success',
      label: `${signsIn ? 'Anmeldung ok' : 'Erreichbar'} · zuletzt ${formatDay(finished.startedAt)}`,
    }
  })()
  const keys = unlistedScopeKeys ?? []
  const names = unreadableScopeNames(
    {
      sourceType: library.sourceType,
      sourceSettings: library.sourceSettings,
      sourcePath: library.sourcePath,
      sourceUrl: library.sourceUrl,
    },
    keys,
  )
  // the scheduler's failures only matter where a schedule runs and the connection does not
  // already name the cause
  const scheduleRuns = library.schedule && library.schedule.frequency !== 'DISABLED'
  const scheduledFailure = runs.some(
    (run) => run.triggeredBy === 'SCHEDULED' && run.status === 'FAILED',
  )
  return {
    storedStatus,
    // only entries with a name can be marked; without names the run says it (D8)
    // a directory or web address has no listed entries: its unreadable parts are listed in the
    // entries' form; elsewhere the listed entry carries the mark
    scopeStatus:
      names.length === 0
        ? null
        : library.sourceType === 'FILESYSTEM' || library.sourceType === 'HTTP_DIRECTORY'
          ? { tone: 'warning', label: 'nicht lesbar', entries: names }
          : { tone: 'warning', label: `${names.length} nicht lesbar` },
    scopeMarked: names.length > 0 ? [...keys, ...names] : undefined,
    scheduleStatus:
      library.lastScheduledRunsFailed && scheduleRuns && scheduledFailure && !connectionFailed
        ? { tone: 'error', label: 'Letzte geplante Läufe fehlgeschlagen' }
        : null,
    scopeFallback:
      library.sourceType === 'RSS_FEED'
        ? 'Alle Einträge des Feeds'
        : library.sourceType === 'HTTP_DIRECTORY'
          ? 'Ganzes Verzeichnis'
          : undefined,
  }
}
