import { useState } from 'react'
import Button from '@mui/material/Button'
import StateDot from '@/shared/ui/StateDot'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'

import type { LibraryIndexState, LibrarySearchStatusResponse } from '@/shared/api/types'
import { formatShare } from '@/shared/utils/labels'
import type { LibraryBatchRun } from '../stores/searchAdminStore'
import { plural } from '@/shared/utils/format'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import { definitionListSx } from '@/shared/ui/list/adminListStyles'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { formatDateTime } from '@/shared/utils/formatDate'

const INDEX_STATE_LABELS: Record<LibraryIndexState, string> = {
  EMPTY: 'Leer',
  READY: 'Vollständig',
  INCOMPLETE: 'Unvollständig',
  OUTDATED: 'Nachzug ausstehend',
}

/**
 * The core-metadata extraction state of one library and the control that drives its backfill.
 * Start, Weiter and Anhalten are one button: the run is a loop of batch calls this page
 * repeats, so pausing is simply not calling again and resuming is calling again - the server
 * re-derives the remaining work on every call.
 */
function MetadataBackfillCell({
  library,
  run,
  onStart,
  onPause,
}: {
  library: LibrarySearchStatusResponse
  run: LibraryBatchRun | undefined
  onStart: (libraryId: string) => void
  onPause: (libraryId: string) => void
}) {
  const backfill = library.metadataBackfill
  const running = run?.running ?? false
  const resumable = !running && run != null && !run.done && run.error == null
  const buttonLabel = running ? 'Anhalten' : resumable ? 'Weiter' : 'Kernfelder nachrüsten'
  return (
    <>
      <Typography variant="body2" component="div">
        {backfill.currentDocuments} / {backfill.totalDocuments} aktuell
      </Typography>
      {backfill.pendingDocuments > 0 && (
        <Typography variant="caption" sx={{ color: 'warning.main' }} component="div">
          {plural(backfill.pendingDocuments, 'Dokument ausstehend', 'Dokumente ausstehend')}
        </Typography>
      )}
      {backfill.awaitingConnectorRunDocuments > 0 && (
        <Typography variant="caption" sx={{ color: 'text.secondary' }} component="div">
          {backfill.awaitingConnectorRunDocuments === 1
            ? 'davon 1 Dokument wartet auf den nächsten Konnektorlauf'
            : `davon ${backfill.awaitingConnectorRunDocuments} Dokumente warten auf den nächsten Konnektorlauf`}
        </Typography>
      )}
      {backfill.lastSkippedDocuments > 0 && (
        <Typography variant="caption" sx={{ color: 'warning.main' }} component="div">
          {plural(
            backfill.lastSkippedDocuments,
            'Dokument zuletzt übersprungen',
            'Dokumente zuletzt übersprungen',
          )}
        </Typography>
      )}
      <Typography
        variant="caption"
        sx={{ color: 'text.secondary' }}
        component="div"
        aria-label={`Füllgrad je Kernfeld: ${library.libraryName}`}
      >
        {backfill.fields
          .map(
            (field) =>
              `${field.label} ${field.filledDocuments} (${formatShare(field.filledShare)})`,
          )
          .join(' · ')}
      </Typography>
      {/* The Pflege-Anker in the operational view - the same definition the library's own
          settings show, counted over the organization here. */}
      <Typography
        variant="caption"
        sx={{ color: 'text.secondary' }}
        component="div"
        aria-label={`Dokumente ohne Wert je Kernfeld: ${library.libraryName}`}
      >
        {backfill.fields
          .map(
            (field) =>
              `${field.label} ${field.documentsWithoutValue} ohne Wert (${formatShare(
                field.missingShare,
              )})`,
          )
          .join(' · ')}
      </Typography>
      {/* The Zählwerk of the model-backed extraction - without it the only feedback about
          what this capability costs is the model provider's invoice. */}
      {library.modelExtraction.calls > 0 && (
        <Typography
          variant="caption"
          sx={{ color: 'text.secondary' }}
          component="div"
          aria-label={`Modellgestützte Extraktion: ${library.libraryName}`}
        >
          {`Modellaufrufe ${library.modelExtraction.calls} · übernommen ${library.modelExtraction.acceptedValues}`}
          {` · verworfen ${library.modelExtraction.rejectedBelowThreshold + library.modelExtraction.rejectedOutsideVocabulary}`}
          {` · Fehler ${library.modelExtraction.failures}`}
          {` · nicht angefragt ${library.modelExtraction.rejectedPoolFull}`}
          {` · Schlagworte ${library.modelExtraction.keywordsAssigned}`}
        </Typography>
      )}
      {run?.error && (
        <Typography variant="caption" sx={{ color: 'error.main' }} component="div" role="alert">
          {run.error}
        </Typography>
      )}
      {(backfill.pendingDocuments > 0 || running) && (
        <Button
          size="small"
          variant="outlined"
          sx={{ mt: 0.5 }}
          aria-label={`${buttonLabel}: ${library.libraryName}`}
          onClick={() => (running ? onPause(library.libraryId) : onStart(library.libraryId))}
        >
          {buttonLabel}
        </Button>
      )}
    </>
  )
}

/**
 * The Kontextpräfix state of one library and the control that drives its Nachlauf. Start,
 * Weiter and Anhalten are one button for the same reason the backfill's are: the run is a loop of
 * batch calls this page repeats, and the server re-derives the remaining work on every call.
 */
function ContextPrefixCell({
  library,
  run,
  onStart,
  onPause,
}: {
  library: LibrarySearchStatusResponse
  run: LibraryBatchRun | undefined
  onStart: (libraryId: string) => void
  onPause: (libraryId: string) => void
}) {
  const rerun = library.contextPrefixRerun
  const running = run?.running ?? false
  const resumable = !running && run != null && !run.done && run.error == null
  const buttonLabel = running ? 'Anhalten' : resumable ? 'Weiter' : 'Neu einbetten'
  return (
    <>
      <Typography variant="body2" component="div">
        {rerun.currentDocuments} / {rerun.totalDocuments} mit aktuellem Präfix
      </Typography>
      {rerun.pendingDocuments > 0 && (
        <Typography variant="caption" sx={{ color: 'warning.main' }} component="div">
          {plural(
            rerun.pendingDocuments,
            'Dokument wartet auf Neu-Einbetten',
            'Dokumente warten auf Neu-Einbetten',
          )}
        </Typography>
      )}
      {rerun.lastSkippedDocuments > 0 && (
        <Typography variant="caption" sx={{ color: 'warning.main' }} component="div">
          {plural(
            rerun.lastSkippedDocuments,
            'Dokument zuletzt fehlgeschlagen',
            'Dokumente zuletzt fehlgeschlagen',
          )}
        </Typography>
      )}
      {run?.error && (
        <Typography variant="caption" sx={{ color: 'error.main' }} component="div" role="alert">
          {run.error}
        </Typography>
      )}
      {(rerun.pendingDocuments > 0 || running) && (
        <Button
          size="small"
          variant="outlined"
          sx={{ mt: 0.5 }}
          aria-label={`${buttonLabel}: ${library.libraryName}`}
          onClick={() => (running ? onPause(library.libraryId) : onStart(library.libraryId))}
        >
          {buttonLabel}
        </Button>
      )}
    </>
  )
}

/**
 * A library's running re-mappings and field deletions. Display only: the run is driven by
 * whoever holds the management right on the library, from its settings - this
 * page makes visible the mixed state a library is currently in.
 */
function MetadataSchemaChangeCell({ library }: { library: LibrarySearchStatusResponse }) {
  const changes = library.metadataSchemaChanges
  if (changes.pendingChanges === 0) {
    return (
      <>
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          keine
        </Typography>
      </>
    )
  }
  return (
    <>
      <Typography variant="body2" component="div">
        {plural(changes.pendingChanges, 'Änderung läuft', 'Änderungen laufen')}
      </Typography>
      <Typography variant="caption" sx={{ color: 'warning.main' }} component="div">
        {plural(
          changes.pendingDocuments,
          'Dokument wird noch umgeschrieben',
          'Dokumente werden noch umgeschrieben',
        )}
      </Typography>
      {changes.lastSkippedDocuments > 0 && (
        <Typography variant="caption" sx={{ color: 'warning.main' }} component="div">
          {plural(
            changes.lastSkippedDocuments,
            'Dokument zuletzt fehlgeschlagen',
            'Dokumente zuletzt fehlgeschlagen',
          )}
        </Typography>
      )}
    </>
  )
}

/** What is still open in a library's index, in the words of the detail below the row. */
function openPoints(library: LibrarySearchStatusResponse, metadataEnabled: boolean): string[] {
  return [
    (library.vectorIndexState === 'INCOMPLETE' ||
      library.vectorChunkCount !== library.chunkCount) &&
      'Vektorindex',
    library.fullTextIndexState === 'OUTDATED' && 'Volltextindex',
    metadataEnabled && library.metadataBackfill.pendingDocuments > 0 && 'Kernfelder',
    metadataEnabled && library.contextPrefixRerun.pendingDocuments > 0 && 'Kontextpräfix',
    metadataEnabled && library.metadataSchemaChanges.pendingChanges > 0 && 'Schemaänderungen',
  ].filter((point): point is string => Boolean(point))
}

/** One word for the row, and below it what is open - the figures stand in the row's detail. */
function StateCell({ points }: { points: string[] }) {
  if (points.length === 0) return <StateDot tone="neutral" label="Vollständig" />
  return (
    <Box sx={{ minWidth: 0 }}>
      <StateDot tone="warning" label="Offen" />
      <ListText secondary>{points.join(' · ')}</ListText>
    </Box>
  )
}

/**
 * The index state of every library in two levels (guidelines 5.3): the row carries documents,
 * backlog, last run and one state; the row opens its detail with the figures per index and,
 * with metadata, the two batch runs and their controls.
 */
export default function LibraryStatusTable({
  libraries,
  backfillRuns,
  contextPrefixRuns,
  onStartBackfill,
  onPauseBackfill,
  onStartContextPrefixRerun,
  onPauseContextPrefixRerun,
}: {
  libraries: LibrarySearchStatusResponse[]
  backfillRuns: Record<string, LibraryBatchRun>
  contextPrefixRuns: Record<string, LibraryBatchRun>
  onStartBackfill: (libraryId: string) => void
  onPauseBackfill: (libraryId: string) => void
  onStartContextPrefixRerun: (libraryId: string) => void
  onPauseContextPrefixRerun: (libraryId: string) => void
}) {
  // Without metadata (ADR-0044) neither run exists, and neither do their three entries.
  const metadataEnabled = useFeatureEnabled('metadata')
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(new Set())
  if (libraries.length === 0) {
    return (
      <Typography variant="body2" sx={{ color: 'text.secondary' }}>
        Es ist noch keine Wissensbibliothek angelegt.
      </Typography>
    )
  }
  function toggle(libraryId: string) {
    setExpanded((current) => {
      const next = new Set(current)
      if (!next.delete(libraryId)) next.add(libraryId)
      return next
    })
  }
  return (
    <AdminList
      label="Indexstatus je Bibliothek"
      rows={libraries}
      rowKey={(library) => library.libraryId}
      rowLabel={(library) => library.libraryName}
      rowTarget={(library) => ({
        onOpen: () => toggle(library.libraryId),
        expanded: expanded.has(library.libraryId),
      })}
      columns={[
        {
          id: 'library',
          head: 'Bibliothek',
          cell: (library) => <ListText strong>{library.libraryName}</ListText>,
        },
        {
          id: 'documents',
          head: 'Dokumente',
          width: 128,
          align: 'right',
          cell: (library) => `${library.indexedDocumentCount} / ${library.documentCount}`,
        },
        {
          id: 'pending',
          head: 'Rückstand',
          width: 112,
          align: 'right',
          cell: (library) => library.pendingDocumentCount,
        },
        {
          id: 'lastRun',
          head: 'Letzter Lauf',
          width: 168,
          cell: (library) =>
            library.lastIndexedAt ? formatDateTime(library.lastIndexedAt) : 'noch nie',
        },
        {
          id: 'state',
          head: 'Zustand',
          width: '28%',
          cell: (library) => <StateCell points={openPoints(library, metadataEnabled)} />,
        },
      ]}
      rowDetail={(library) =>
        expanded.has(library.libraryId) ? (
          <Box
            component="dl"
            aria-label={`Indexdetails: ${library.libraryName}`}
            sx={{ ...definitionListSx, mt: 0, mb: 1 }}
          >
            <dt>Abschnitte</dt>
            <dd>
              {`${library.vectorChunkCount} im Index, ${library.chunkCount} laut Dokumenten`}
              {library.vectorChunkCount !== library.chunkCount && (
                <Typography variant="caption" sx={{ color: 'text.secondary' }} component="div">
                  Vektorindex und Dokumentzählung weichen ab
                </Typography>
              )}
            </dd>
            <dt>Wenige Abschnitte</dt>
            <dd>
              {plural(
                library.lowChunkDocumentCount,
                'Dokument ohne oder mit auffällig wenigen Abschnitten',
                'Dokumente ohne oder mit auffällig wenigen Abschnitten',
              )}
            </dd>
            <dt>Vektorindex</dt>
            <dd>
              <StateDot
                tone={library.vectorIndexState === 'INCOMPLETE' ? 'warning' : 'neutral'}
                label={INDEX_STATE_LABELS[library.vectorIndexState]}
              />
            </dd>
            <dt>Volltextindex</dt>
            <dd>
              <StateDot
                tone={library.fullTextIndexState === 'OUTDATED' ? 'warning' : 'neutral'}
                label={INDEX_STATE_LABELS[library.fullTextIndexState]}
              />
              {library.fullTextOutdatedChunks > 0 && (
                <Typography variant="caption" sx={{ color: 'text.secondary' }} component="div">
                  {plural(
                    library.fullTextOutdatedChunks,
                    'Abschnitt in älterer Fassung',
                    'Abschnitte in älterer Fassung',
                  )}
                </Typography>
              )}
            </dd>
            {metadataEnabled && (
              <>
                <dt>Kernfelder</dt>
                <dd>
                  <MetadataBackfillCell
                    library={library}
                    run={backfillRuns[library.libraryId]}
                    onStart={onStartBackfill}
                    onPause={onPauseBackfill}
                  />
                </dd>
                <dt>Kontextpräfix</dt>
                <dd>
                  <ContextPrefixCell
                    library={library}
                    run={contextPrefixRuns[library.libraryId]}
                    onStart={onStartContextPrefixRerun}
                    onPause={onPauseContextPrefixRerun}
                  />
                </dd>
                <dt>Schemaänderungen</dt>
                <dd>
                  <MetadataSchemaChangeCell library={library} />
                </dd>
              </>
            )}
          </Box>
        ) : null
      }
    />
  )
}
