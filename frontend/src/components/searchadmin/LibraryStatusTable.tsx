import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import Paper from '@mui/material/Paper'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'

import type { LibraryIndexState, LibrarySearchStatusResponse } from '../../types/api'
import { formatShare } from '../../utils/labels'
import type { LibraryBatchRun } from '../../stores/searchAdminStore'
import { plural } from './format'

const INDEX_STATE_LABELS: Record<LibraryIndexState, string> = {
  EMPTY: 'Leer',
  READY: 'Vollständig',
  INCOMPLETE: 'Unvollständig',
}

/**
 * The core-metadata extraction state of one library and the control that drives its backfill
 * (#1067). Start, Weiter and Anhalten are one button: the run is a loop of batch calls this page
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
    <TableCell>
      <Typography variant="body2" component="div">
        {backfill.currentDocuments} / {backfill.totalDocuments} aktuell
      </Typography>
      {backfill.pendingDocuments > 0 && (
        <Typography variant="caption" color="warning.main" component="div">
          {plural(backfill.pendingDocuments, 'Dokument ausstehend', 'Dokumente ausstehend')}
        </Typography>
      )}
      {backfill.awaitingConnectorRunDocuments > 0 && (
        <Typography variant="caption" color="text.secondary" component="div">
          {backfill.awaitingConnectorRunDocuments === 1
            ? 'davon 1 Dokument wartet auf den nächsten Konnektorlauf'
            : `davon ${backfill.awaitingConnectorRunDocuments} Dokumente warten auf den nächsten Konnektorlauf`}
        </Typography>
      )}
      {backfill.lastSkippedDocuments > 0 && (
        <Typography variant="caption" color="warning.main" component="div">
          {plural(
            backfill.lastSkippedDocuments,
            'Dokument zuletzt übersprungen',
            'Dokumente zuletzt übersprungen',
          )}
        </Typography>
      )}
      <Typography
        variant="caption"
        color="text.secondary"
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
      {/* #1069: the Pflege-Anker in the operational view - the same definition the library's own
          settings show, counted over the organization here. */}
      <Typography
        variant="caption"
        color="text.secondary"
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
      {/* #1073: the Zählwerk of the model-backed extraction - without it the only feedback about
          what this capability costs is the model provider's invoice. */}
      {library.modelExtraction.calls > 0 && (
        <Typography
          variant="caption"
          color="text.secondary"
          component="div"
          aria-label={`Modellgestützte Extraktion: ${library.libraryName}`}
        >
          {`Modellaufrufe ${library.modelExtraction.calls} · übernommen ${library.modelExtraction.acceptedValues}`}
          {` · verworfen ${library.modelExtraction.rejectedBelowThreshold + library.modelExtraction.rejectedOutsideVocabulary}`}
          {` · Fehler ${library.modelExtraction.failures}`}
          {` · übersprungen ${library.modelExtraction.rejectedPoolFull}`}
          {` · Schlagworte ${library.modelExtraction.keywordsAssigned}`}
        </Typography>
      )}
      {run?.error && (
        <Typography variant="caption" color="error.main" component="div" role="alert">
          {run.error}
        </Typography>
      )}
      {(backfill.pendingDocuments > 0 || running) && (
        <Button
          size="small"
          variant={running ? 'outlined' : 'contained'}
          sx={{ mt: 0.5 }}
          aria-label={`${buttonLabel}: ${library.libraryName}`}
          onClick={() => (running ? onPause(library.libraryId) : onStart(library.libraryId))}
        >
          {buttonLabel}
        </Button>
      )}
    </TableCell>
  )
}

/**
 * The Kontextpräfix state of one library and the control that drives its Nachlauf (#1072). Start,
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
    <TableCell>
      <Typography variant="body2" component="div">
        {rerun.currentDocuments} / {rerun.totalDocuments} mit aktuellem Präfix
      </Typography>
      {rerun.pendingDocuments > 0 && (
        <Typography variant="caption" color="warning.main" component="div">
          {plural(
            rerun.pendingDocuments,
            'Dokument wartet auf Neu-Einbetten',
            'Dokumente warten auf Neu-Einbetten',
          )}
        </Typography>
      )}
      {rerun.lastSkippedDocuments > 0 && (
        <Typography variant="caption" color="warning.main" component="div">
          {plural(
            rerun.lastSkippedDocuments,
            'Dokument zuletzt fehlgeschlagen',
            'Dokumente zuletzt fehlgeschlagen',
          )}
        </Typography>
      )}
      {run?.error && (
        <Typography variant="caption" color="error.main" component="div" role="alert">
          {run.error}
        </Typography>
      )}
      {(rerun.pendingDocuments > 0 || running) && (
        <Button
          size="small"
          variant={running ? 'outlined' : 'contained'}
          sx={{ mt: 0.5 }}
          aria-label={`${buttonLabel}: ${library.libraryName}`}
          onClick={() => (running ? onPause(library.libraryId) : onStart(library.libraryId))}
        >
          {buttonLabel}
        </Button>
      )}
    </TableCell>
  )
}

/** The index state of every library, with the two chargen controls per row. */
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
  if (libraries.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Es ist noch keine Wissensbibliothek angelegt.
      </Typography>
    )
  }
  return (
    // Ten columns overflow narrower viewports into a horizontal scroll; a scrollable region must
    // be reachable and scrollable by keyboard (axe scrollable-region-focusable), hence the tab stop.
    <TableContainer
      component={Paper}
      variant="outlined"
      tabIndex={0}
      role="region"
      aria-label="Tabelle Indexstatus je Bibliothek, horizontal scrollbar"
    >
      <Table size="small" aria-label="Indexstatus je Bibliothek">
        <TableHead>
          <TableRow>
            <TableCell>Bibliothek</TableCell>
            <TableCell align="right">Dokumente</TableCell>
            <TableCell align="right">Rückstand</TableCell>
            <TableCell align="right">Abschnitte (im Index / laut Dokumenten)</TableCell>
            <TableCell align="right">Ohne oder mit auffällig wenigen Abschnitten</TableCell>
            <TableCell>Letzter Lauf</TableCell>
            <TableCell>Vektorindex</TableCell>
            <TableCell>Volltextindex</TableCell>
            <TableCell>Kernfelder</TableCell>
            <TableCell>Kontextpräfix</TableCell>
          </TableRow>
        </TableHead>
        <TableBody>
          {libraries.map((library) => (
            <TableRow key={library.libraryId}>
              <TableCell>{library.libraryName}</TableCell>
              <TableCell align="right">
                {library.indexedDocumentCount} / {library.documentCount}
              </TableCell>
              <TableCell align="right">{library.pendingDocumentCount}</TableCell>
              <TableCell align="right">
                {`${library.vectorChunkCount} / ${library.chunkCount}`}
                {library.vectorChunkCount !== library.chunkCount && (
                  <Typography variant="caption" color="text.secondary" component="div">
                    Vektorindex und Dokumentzählung weichen ab
                  </Typography>
                )}
              </TableCell>
              <TableCell align="right">{library.lowChunkDocumentCount}</TableCell>
              <TableCell>
                {library.lastIndexedAt
                  ? new Date(library.lastIndexedAt).toLocaleString('de-DE')
                  : 'noch nie'}
              </TableCell>
              <TableCell>
                <Chip
                  size="small"
                  label={INDEX_STATE_LABELS[library.vectorIndexState]}
                  color={library.vectorIndexState === 'INCOMPLETE' ? 'warning' : 'default'}
                />
              </TableCell>
              <TableCell>
                <Chip
                  size="small"
                  label={INDEX_STATE_LABELS[library.fullTextIndexState]}
                  color={library.fullTextIndexState === 'INCOMPLETE' ? 'warning' : 'default'}
                />
                {library.fullTextMissingChunks > 0 && (
                  <Typography variant="caption" color="text.secondary" component="div">
                    {plural(library.fullTextMissingChunks, 'Abschnitt fehlt', 'Abschnitte fehlen')}
                  </Typography>
                )}
              </TableCell>
              <MetadataBackfillCell
                library={library}
                run={backfillRuns[library.libraryId]}
                onStart={onStartBackfill}
                onPause={onPauseBackfill}
              />
              <ContextPrefixCell
                library={library}
                run={contextPrefixRuns[library.libraryId]}
                onStart={onStartContextPrefixRerun}
                onPause={onPauseContextPrefixRerun}
              />
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}
