import { useRef, useState } from 'react'
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
import { useIndexingStore } from '../stores/indexingStore'
import { formatCalendarDay } from '@/shared/utils/labels'
import { scheduleFrequencyLabel } from './scheduleLabels'
import EditLibrarySourceDialog from './EditLibrarySourceDialog'
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
import StateDot from '@/shared/ui/StateDot'
import Link from '@mui/material/Link'
import ConfluenceWebhookSection from './ConfluenceWebhookSection'
import S3EventSection from './S3EventSection'
import SummaryRow from './setup/SummaryRow'
import { TEXT_LINK } from './setup/rowStyles'
import type { RowStatus } from './setup/setupTypes'
import LibraryRunList from './LibraryRunList'
import { formatDay } from './steckbrief/libraryState'
import { fixedScopeOf, unreadableScopeNames } from './steckbrief/sourceSummary'
import { s3SettingsFromLibrary } from '../utils/s3Source'
import type { LibraryResponse } from '@/shared/api/types'

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
  /** May start runs (EDITOR and above): „Vollabgleich starten“ at the runs. */
  mayStartRuns?: boolean
}

const REJECTED_SIGN_IN = new Set(['AUTH', 'CREDENTIALS_REJECTED'])
const NO_RUNS: IndexingRunResponse[] = []
const NO_KEYS: string[] = []

/**
 * The Reiter „Quelle" of a connector library, in four sections: **Umfang** for every
 * reader, then - behind the MANAGER bar - **Anbindung** (source configuration,
 * connection test, webhook or events), **Zeitplan** and **Läufe**. Triggering and the live progress
 * live in the page head, visible from every area.
 */
export default function LibrarySourceSection({
  libraryId,
  library,
  canEditSource,
  erasing = false,
  mayStartRuns = false,
}: LibrarySourceSectionProps) {
  const runs = useIndexingStore((s) => s.runHistoryByLibrary[libraryId] ?? NO_RUNS)
  const unlistedKeys = useIndexingStore(
    (s) => s.runsByLibrary[libraryId]?.unlistedScopeKeys ?? NO_KEYS,
  )
  const isRunning = useIndexingStore((s) => s.runsByLibrary[libraryId]?.status === 'RUNNING')
  const triggerIndexing = useIndexingStore((s) => s.triggerIndexing)
  const [liveOpen, setLiveOpen] = useState(false)
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
  const rowsRef = useRef<SourceSetupHandle>(null)
  const editAction = (
    <Button
      color="inherit"
      size="small"
      onClick={() =>
        configuration?.setup ? rowsRef.current?.openRow('connection') : setEditSourceOpen(true)
      }
    >
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

  // D10: the connection carries what the last finished run knows of the sign-in
  const finished = runs.find((run) => run.status !== 'RUNNING') ?? null
  const connectionStatus: RowStatus | null = !finished
    ? null
    : finished.status === 'FAILED' &&
        finished.failureCategory != null &&
        REJECTED_SIGN_IN.has(finished.failureCategory)
      ? { tone: 'error', label: 'Anmeldung abgelehnt' }
      : { tone: 'success', label: `Anmeldung ok · zuletzt ${formatDay(finished.startedAt)}` }
  const unreadable = unreadableScopeNames(unlistedKeys, library as LibraryResponse)
  const scopeNote =
    unreadable.length > 0 ? (
      <Box sx={{ mt: 0.5 }} data-testid="scope-unreadable">
        <StateDot tone="warning" label={`nicht lesbar: ${unreadable.join(', ')}`} />
      </Box>
    ) : undefined
  const scheduleStatus: RowStatus | null = library.lastScheduledRunsFailed
    ? { tone: 'error', label: 'letzte geplante Läufe fehlgeschlagen' }
    : null
  const liveKind =
    library.sourceType === 'CONFLUENCE' ? 'webhook' : library.sourceType === 'S3' ? 'events' : null
  const liveRow =
    liveKind && canEditSource && !erasing ? (
      <SummaryRow
        id="edit-source-row-live"
        title="Live-Aktualisierung"
        summary={
          library.pushSecretSet
            ? liveKind === 'webhook'
              ? 'Webhook eingerichtet'
              : 'Ereignisse eingerichtet'
            : 'aus'
        }
        actionLabel={library.pushSecretSet ? 'Ändern' : 'Einrichten'}
        open={liveOpen}
        onOpen={() => setLiveOpen(true)}
        headingLevel="h2"
      >
        <Stack spacing={2}>
          {liveKind === 'webhook' ? (
            <ConfluenceWebhookSection libraryId={libraryId} secretSet={library.pushSecretSet} />
          ) : (
            <S3EventSection
              libraryId={libraryId}
              tokenSet={library.pushSecretSet}
              scopes={s3SettingsFromLibrary(library)?.scopes ?? []}
            />
          )}
          <Box sx={{ textAlign: 'right' }}>
            <Link
              component="button"
              type="button"
              underline="hover"
              onClick={() => setLiveOpen(false)}
              sx={TEXT_LINK}
            >
              Schließen
            </Link>
          </Box>
        </Stack>
      </SummaryRow>
    ) : undefined

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
      {configuration?.setup ? (
        <Box sx={{ mb: 4 }}>
          <LibrarySourceRows
            // a fresh instance whenever the stored source changes, so the rows start from it
            key={JSON.stringify([
              library.sourceUrl,
              library.sourcePath,
              library.sourceSettings,
              library.schedule,
              library.sourceCredentialsSet,
              library.connectionProfile?.id,
            ])}
            ref={rowsRef}
            libraryId={libraryId}
            library={library}
            canEdit={canEditSource && !erasing}
            connectionStatus={connectionStatus}
            scopeNote={scopeNote}
            fixedScope={fixedScopeOf(library.sourceType)}
            scheduleStatus={scheduleStatus}
            extraRows={liveRow}
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
        </Box>
      ) : (
        <>
          {/* ADR-0023 / ADR-0027: scope and its release sequence apply to everyone
         with read access - they are here instead of in the header, with an explanation. */}
          {Scope && (
            <PageSection title="Umfang">
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
                {/* sourcePath/sourceUrl expose internal server paths and source URLs - the backend only
              * serves them to a caller with at least MANAGER, and
                this whole area only renders behind the same bar. */}
                <Stack spacing={1}>
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
                      Für diese Quellart gibt es in dieser Oberfläche keine Eingabemaske; ihre
                      Anbindung ist hier nicht konfigurierbar.
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

              {/* Schedule - the same threshold as the source connection (canEditSource). */}
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
                {configuration?.fullSyncRhythm && (
                  <Typography variant="body2" sx={{ color: 'text.secondary' }}>
                    {(() => {
                      const days = fullSyncIntervalDays ?? library.fullSyncIntervalDefaultDays ?? 7
                      const rhythm =
                        days === 1 ? 'Vollabgleich täglich' : `Vollabgleich alle ${days} Tage`
                      return fullSyncIntervalDays == null
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
        </>
      )}

      {canEditSource && (
        <LibraryRunList
          libraryId={libraryId}
          sourceType={library.sourceType}
          privateLibrary={Boolean(library.privateLibrary)}
          connectionAction={
            configuration?.setup && !erasing ? (
              <Link
                component="button"
                type="button"
                underline="hover"
                onClick={() => rowsRef.current?.openRow('connection')}
                sx={TEXT_LINK}
              >
                Verbindung ändern
              </Link>
            ) : undefined
          }
          actions={
            mayStartRuns && configuration?.fullSyncRhythm && !erasing ? (
              <Link
                component="button"
                type="button"
                underline="hover"
                disabled={isRunning}
                onClick={() => void triggerIndexing(libraryId, library.sourceType, 'FULL')}
                sx={TEXT_LINK}
              >
                Vollabgleich starten
              </Link>
            ) : undefined
          }
        />
      )}
    </Stack>
  )
}
