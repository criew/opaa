import { useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import AlertTitle from '@mui/material/AlertTitle'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import type {
  ConnectionProfileRef,
  SourceTypeKey,
  LibrarySchedule,
  LibrarySourceConnection,
  SourceBlock,
} from '@/shared/api/types'
import { formatCalendarDay } from '@/shared/utils/labels'
import { scheduleFrequencyLabel } from './scheduleLabels'
import EditLibrarySourceDialog from './EditLibrarySourceDialog'
import EditLibraryScheduleDialog from './EditLibraryScheduleDialog'
import PageSection from '@/shared/ui/PageSection'
import { CONTENT_WIDTH, TEXT_LINK } from './setup/rowStyles'
import SummaryRow from './setup/SummaryRow'
import type { RowStatus } from './setup/setupTypes'
import LibraryConnectionPanel from './LibraryConnectionPanel'
import LibrarySourceRows from './LibrarySourceRows'
import type { SourceSetupHandle } from './setup/SourceSetupRows'
import LibrarySourceConsent from './LibrarySourceConsent'
import ReconnectSourceDialog from './ReconnectSourceDialog'
import ConfluenceWebhookSection from './ConfluenceWebhookSection'
import S3EventSection from './S3EventSection'
import { s3SettingsFromLibrary } from '../utils/s3Source'
import { Link as RouterLink } from 'react-router'
import { CONNECTED_ACCOUNTS_ROUTE } from '@/shared/routes'
import { sourceRegistration } from './sources/registry'
import { scopeShort } from './scopeFacts'

/** Sources without a selection: their Umfang row names the whole source, without „Ändern“. */
const WHOLE_SOURCE_TYPES = new Set<SourceTypeKey>(['HTTP_DIRECTORY', 'RSS_FEED'])

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
  /** The connection's last known state from the newest run (D10). */
  connectionStatus?: RowStatus | null
  /** Names of the scope entries the newest run could not read (D8). */
  unreadable?: string[]
}

/**
 * The Reiter „Quelle" of a connector library: the rows Verbindung · Umfang · Zeitplan, and
 * Live-Aktualisierung where the source can notify (Confluence, S3). A reader sees Umfang only.
 * The runs live in their own tab „Verlauf“; the state of the library lives in the head.
 */
export default function LibrarySourceSection({
  libraryId,
  library,
  canEditSource,
  erasing = false,
  connectionStatus = null,
  unreadable = [],
}: LibrarySourceSectionProps) {
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

  // Confluence and S3 can tell OPAA about changes; the secret behind it only exists after creation.
  const liveUpdate =
    library.sourceType === 'CONFLUENCE'
      ? {
          on: Boolean(library.pushSecretSet),
          content: (
            <ConfluenceWebhookSection libraryId={libraryId} secretSet={library.pushSecretSet} />
          ),
        }
      : library.sourceType === 'S3'
        ? {
            on: Boolean(library.pushSecretSet),
            content: (
              <S3EventSection
                libraryId={libraryId}
                tokenSet={library.pushSecretSet}
                scopes={s3SettingsFromLibrary(library)?.scopes ?? []}
              />
            ),
          }
        : null

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
            fixedScope={WHOLE_SOURCE_TYPES.has(library.sourceType) ? scopeShort(library) : null}
            unreadable={unreadable}
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
          {canEditSource && !erasing && liveUpdate && (
            <SummaryRow
              id="edit-source-row-live"
              title="Live-Aktualisierung"
              summary={liveUpdate.on ? 'eingerichtet' : 'aus'}
              open={liveOpen}
              onOpen={() => setLiveOpen(true)}
              headingLevel="h2"
            >
              {liveUpdate.content}
              <Box sx={{ mt: 2, textAlign: 'right' }}>
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
            </SummaryRow>
          )}
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
    </Stack>
  )
}
