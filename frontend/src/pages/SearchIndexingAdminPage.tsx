import { useEffect, useRef, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import MenuItem from '@mui/material/MenuItem'
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ArticleOutlinedIcon from '@mui/icons-material/ArticleOutlined'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import PageHeading from '../components/a11y/PageHeading'
import GlobalScopeNote from '../components/GlobalScopeNote'
import SectionHead from '../components/SectionHead'

import type {
  ChunkInspectionResponse,
  DocumentChunksResponse,
  LibraryIndexState,
  LibrarySearchStatusResponse,
  RetrievalCandidateOutcome,
  RetrievalStage,
  RetrievalStageResponse,
  RetrievalStageStatus,
  RetrievalVerdictReason,
  SearchDiagnosisContextType,
  SearchDiagnosisResponse,
  SearchModelRole,
  SearchModelRoleStatusResponse,
  SearchPathStatusResponse,
  TrackedDocumentResponse,
} from '../types/api'
import { translateListLabel, translateStageNote } from '../utils/retrievalProtocolText'
import { getSearchChunk } from '../services/api'
import { useAuthStore } from '../stores/authStore'
import { useSearchAdminStore, type MetadataBackfillRun } from '../stores/searchAdminStore'

const OWN_CONTEXT_VALUE = 'SELF'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/** A document key is only a loadable document id when it is a UUID; other keys stay plain text. */
function isUuid(value: string) {
  return UUID_PATTERN.test(value)
}

/** German singular/plural, so the page never says "1 Bibliotheken". */
function plural(count: number, one: string, many: string) {
  return `${count} ${count === 1 ? one : many}`
}

function formatMetadataValue(value: unknown): string {
  if (value == null) return '—'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}

const ROLE_LABELS: Record<SearchModelRole, string> = {
  CHAT: 'Chat',
  EMBEDDING: 'Einbettung',
  RERANK: 'Reranking',
}

const ROLE_STATE_LABELS: Record<SearchModelRoleStatusResponse['state'], string> = {
  ACTIVE: 'Aktiv und erreichbar',
  DISABLED: 'Ausdrücklich abgeschaltet',
  UNCONFIGURED: 'Eingeschaltet, aber unbelegt',
  UNREACHABLE: 'Eingeschaltet, aber nicht erreichbar',
}

const PATH_LABELS: Record<SearchPathStatusResponse['path'], string> = {
  VECTOR: 'Vektorsuche',
  FULL_TEXT: 'Volltextsuche',
}

const PATH_STATE_LABELS: Record<SearchPathStatusResponse['state'], string> = {
  ACTIVE: 'Aktiv',
  DISABLED: 'Abgeschaltet',
  INCOMPLETE: 'Unvollständig',
}

const INDEX_STATE_LABELS: Record<LibraryIndexState, string> = {
  EMPTY: 'Leer',
  READY: 'Vollständig',
  INCOMPLETE: 'Unvollständig',
}

const STAGE_LABELS: Record<RetrievalStage, string> = {
  SEARCH_SCOPE: 'Suchbereich',
  SUB_QUERY_DECOMPOSITION: 'Teilfragen',
  VECTOR_SEARCH: 'Vektorsuche',
  FULL_TEXT_SEARCH: 'Volltextsuche',
  MMR_SELECTION: 'Auswahl je Liste',
  RANK_FUSION: 'Fusion (RRF)',
  RERANK: 'Neubewertung (Reranking)',
  DOCUMENT_COMPLETION: 'Dokument-Vervollständigung',
}

const STAGE_STATUS_LABELS: Record<RetrievalStageStatus, string> = {
  EXECUTED: 'Ausgeführt',
  DISABLED: 'Abgeschaltet',
  NOT_REACHED: 'Nicht erreicht',
  UNAVAILABLE: 'Eingeschaltet, aber nicht verfügbar',
}

const OUTCOME_LABELS: Record<RetrievalCandidateOutcome, string> = {
  ADDED: 'Hereingekommen',
  KEPT: 'Behalten',
  DROPPED: 'Verdrängt',
}

const REASON_LABELS: Record<RetrievalVerdictReason, string> = {
  RETRIEVED_BY_SEARCH: 'Von der Suche gefunden',
  WITHIN_BUDGET: 'Innerhalb der Auswahlgrenze',
  OUTSIDE_LIST_BUDGET: 'Unterhalb der Auswahlgrenze der eigenen Liste',
  OUTSIDE_FUSION_BUDGET: 'Nach der Fusion unterhalb der Auswahlgrenze',
  OUTSIDE_RERANK_BUDGET: 'Nach der Neubewertung unterhalb der Auswahlgrenze',
  COMPLETED_AS_SIBLING: 'Als weiterer Abschnitt desselben Dokuments ergänzt',
  EVICTED_BY_DOCUMENT_COMPLETION_TIER_1:
    'Von der Dokument-Vervollständigung verdrängt (schwächster Abschnitt seines Dokuments)',
  EVICTED_BY_DOCUMENT_COMPLETION_TIER_2:
    'Von der Dokument-Vervollständigung verdrängt (schwächster Abschnitt der Auswahl)',
}

/**
 * The plain-language answer to "why does this document not appear?" - the one distinction the
 * whole diagnosis exists for: an indexing problem, a ranking problem, or a rights problem.
 */
function trackedDocumentMessage(tracked: TrackedDocumentResponse): {
  severity: 'success' | 'info' | 'warning'
  text: string
} {
  const name = tracked.fileName ?? 'Das Dokument'
  switch (tracked.outcome) {
    case 'IN_FINAL_SELECTION':
      return {
        severity: 'success',
        text: `„${name}“ steht mit ${plural(tracked.selectedChunkCount, 'Abschnitt', 'Abschnitten')} in der Endauswahl.`,
      }
    case 'OUTSIDE_SEARCH_SCOPE':
      return {
        severity: 'info',
        text:
          `„${name}“ liegt außerhalb des Suchbereichs dieses Rechtekontexts` +
          `${tracked.libraryName ? ` (Bibliothek „${tracked.libraryName}“)` : ''}. ` +
          'Keine Suchstufe konnte es erreichen - das ist eine Rechtefrage, keine Suchfrage.',
      }
    case 'NOT_RETRIEVED':
      return {
        severity: 'warning',
        text:
          `„${name}“ wurde von keiner Suchstufe gefunden. Es ist im Suchbereich, aber keiner ` +
          'seiner Abschnitte war Kandidat - das deutet auf ein Indexierungs- oder ' +
          'Zuschnittsproblem hin, nicht auf die Rangfolge.',
      }
    case 'DISPLACED':
    default:
      return {
        severity: 'warning',
        text:
          `„${name}“ wurde gefunden (${plural(tracked.retrievedChunkCount, 'Abschnitt', 'Abschnitte')}), aber in der ` +
          `Stufe „${tracked.displacedAtStage ? STAGE_LABELS[tracked.displacedAtStage] : 'unbekannt'}“ ` +
          `verdrängt${tracked.displacedReason ? `: ${REASON_LABELS[tracked.displacedReason]}` : ''}. ` +
          'Das ist ein Problem der Rangfolge, nicht der Indexierung.',
      }
  }
}

/** The two navigations every diagnosis row offers: open one chunk, or jump to its document. */
interface ChunkNavigation {
  onShowChunk: (chunkId: string) => void
  onShowDocument: (documentId: string) => void
}

function ModelRoleCard({ role }: { role: SearchModelRoleStatusResponse }) {
  return (
    <Paper variant="outlined" sx={{ p: 2.5, flex: 1, minWidth: 260 }}>
      <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 1 }}>
        <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>{ROLE_LABELS[role.role]}</Typography>
        <Chip
          size="small"
          label={ROLE_STATE_LABELS[role.state]}
          color={role.faulted ? 'error' : role.state === 'ACTIVE' ? 'success' : 'default'}
          aria-label={`${ROLE_LABELS[role.role]}: ${ROLE_STATE_LABELS[role.state]}`}
        />
      </Stack>
      {role.faulted ? (
        <Alert severity="error" sx={{ mb: 1 }}>
          {role.detail}
        </Alert>
      ) : (
        <Typography variant="body2" color="text.secondary" sx={{ mb: 1 }}>
          {role.detail}
        </Typography>
      )}
      <Typography variant="caption" color="text.secondary" component="div">
        Endpunkt: {role.endpoint ?? 'nicht hinterlegt'}
      </Typography>
      <Typography variant="caption" color="text.secondary" component="div">
        Modell-Kennung: {role.modelIdentifier ?? 'nicht hinterlegt'}
      </Typography>
    </Paper>
  )
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
  run: MetadataBackfillRun | undefined
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
              `${field.label} ${field.filledDocuments} (${Math.round(field.filledShare * 100)} %)`,
          )
          .join(' · ')}
      </Typography>
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

function LibraryStatusTable({
  libraries,
  backfillRuns,
  onStartBackfill,
  onPauseBackfill,
}: {
  libraries: LibrarySearchStatusResponse[]
  backfillRuns: Record<string, MetadataBackfillRun>
  onStartBackfill: (libraryId: string) => void
  onPauseBackfill: (libraryId: string) => void
}) {
  if (libraries.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Es ist noch keine Wissensbibliothek angelegt.
      </Typography>
    )
  }
  return (
    // Nine columns overflow narrower viewports into a horizontal scroll; a scrollable region must
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
                {library.fullTextSkippedChunks > 0 && (
                  <Typography variant="caption" color="warning.main" component="div">
                    {plural(
                      library.fullTextSkippedChunks,
                      'Abschnitt dauerhaft übersprungen',
                      'Abschnitte dauerhaft übersprungen',
                    )}
                  </Typography>
                )}
              </TableCell>
              <MetadataBackfillCell
                library={library}
                run={backfillRuns[library.libraryId]}
                onStart={onStartBackfill}
                onPause={onPauseBackfill}
              />
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

/** The document cell of a diagnosis row: a link into the document's chunk list where the key is an id. */
function DocumentTitleCell({
  documentKey,
  documentTitle,
  onShowDocument,
}: {
  documentKey: string
  documentTitle: string | null | undefined
  onShowDocument: (documentId: string) => void
}) {
  const label = documentTitle ?? documentKey
  if (!isUuid(documentKey)) {
    return <TableCell>{label}</TableCell>
  }
  return (
    <TableCell>
      <Link
        component="button"
        type="button"
        variant="body2"
        onClick={() => onShowDocument(documentKey)}
        sx={{ textAlign: 'left', wordBreak: 'break-word' }}
      >
        {label}
      </Link>
    </TableCell>
  )
}

function ShowChunkButton({
  chunkId,
  onShowChunk,
}: { chunkId: string } & Pick<ChunkNavigation, 'onShowChunk'>) {
  return (
    <IconButton size="small" aria-label="Chunk anzeigen" onClick={() => onShowChunk(chunkId)}>
      <ArticleOutlinedIcon fontSize="small" />
    </IconButton>
  )
}

function StagePanel({
  stage,
  navigation,
}: {
  stage: RetrievalStageResponse
  navigation: ChunkNavigation
}) {
  return (
    <Accordion variant="outlined" disableGutters slotProps={{ heading: { component: 'h3' } }}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexGrow: 1 }}>
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
            {STAGE_LABELS[stage.stage]}
          </Typography>
          <Chip
            size="small"
            label={STAGE_STATUS_LABELS[stage.status]}
            color={stage.status === 'EXECUTED' ? 'default' : 'warning'}
          />
          <Typography sx={{ fontSize: 13, color: 'text.secondary', ml: 'auto', mr: 1 }}>
            {stage.incomingCount} → {stage.outgoingCount} Kandidaten
          </Typography>
        </Stack>
      </AccordionSummary>
      <AccordionDetails>
        {stage.notes.length > 0 && (
          <Box sx={{ mb: 1.5 }}>
            {stage.notes.map((note) => (
              <Typography key={note} variant="body2" color="text.secondary">
                {translateStageNote(note)}
              </Typography>
            ))}
          </Box>
        )}
        {stage.verdicts.length === 0 ? (
          <Typography variant="body2" color="text.secondary">
            Diese Stufe hat über keinen einzelnen Kandidaten entschieden.
          </Typography>
        ) : (
          <TableContainer>
            <Table size="small" aria-label={`Kandidaten der Stufe ${STAGE_LABELS[stage.stage]}`}>
              <TableHead>
                <TableRow>
                  <TableCell>Dokument</TableCell>
                  <TableCell>Bibliothek</TableCell>
                  <TableCell>Ergebnis</TableCell>
                  <TableCell>Begründung</TableCell>
                  <TableCell>Liste</TableCell>
                  <TableCell align="right">Rang</TableCell>
                  <TableCell align="right">Wert</TableCell>
                  <TableCell align="center">Chunk</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {stage.verdicts.map((verdict) => (
                  <TableRow key={`${verdict.chunkId}-${verdict.listLabel ?? 'fusioniert'}`}>
                    <DocumentTitleCell
                      documentKey={verdict.documentKey}
                      documentTitle={verdict.documentTitle}
                      onShowDocument={navigation.onShowDocument}
                    />
                    <TableCell>{verdict.libraryName ?? '—'}</TableCell>
                    <TableCell>{OUTCOME_LABELS[verdict.outcome]}</TableCell>
                    <TableCell>{REASON_LABELS[verdict.reason]}</TableCell>
                    <TableCell>{translateListLabel(verdict.listLabel)}</TableCell>
                    <TableCell align="right">{verdict.rank ?? '—'}</TableCell>
                    <TableCell align="right">
                      {verdict.value == null ? '—' : verdict.value.toFixed(4)}
                    </TableCell>
                    <TableCell align="center">
                      <ShowChunkButton
                        chunkId={verdict.chunkId}
                        onShowChunk={navigation.onShowChunk}
                      />
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        )}
      </AccordionDetails>
    </Accordion>
  )
}

function DiagnosisResult({
  diagnosis,
  navigation,
}: {
  diagnosis: SearchDiagnosisResponse
  navigation: ChunkNavigation
}) {
  return (
    <Box sx={{ mt: 3 }}>
      <SectionHead component="h3">Ergebnis</SectionHead>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        {diagnosis.contextLabel} · Suchbereich:{' '}
        {diagnosis.searchScope.length === 0
          ? 'keine Bibliothek'
          : diagnosis.searchScope.map((library) => library.name).join(', ')}
        {diagnosis.searchQueries.length > 0 &&
          ` · Teilfragen: ${diagnosis.searchQueries.join(' · ')}`}
      </Typography>
      {diagnosis.trackedDocument && (
        <Alert severity={trackedDocumentMessage(diagnosis.trackedDocument).severity} sx={{ mb: 2 }}>
          {trackedDocumentMessage(diagnosis.trackedDocument).text}
        </Alert>
      )}
      <Stack spacing={1} sx={{ mb: 3 }}>
        {diagnosis.stages.map((stage) => (
          <StagePanel key={stage.stage} stage={stage} navigation={navigation} />
        ))}
      </Stack>
      <SectionHead component="h3">Endauswahl</SectionHead>
      {diagnosis.finalSelection.length === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Die Endauswahl ist leer - in diesem Rechtekontext hätte diese Frage keinen Beleg.
        </Typography>
      ) : (
        <TableContainer component={Paper} variant="outlined">
          <Table size="small" aria-label="Endauswahl">
            <TableHead>
              <TableRow>
                <TableCell align="right">Rang</TableCell>
                <TableCell>Dokument</TableCell>
                <TableCell>Bibliothek</TableCell>
                <TableCell align="center">Chunk</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {diagnosis.finalSelection.map((entry) => (
                <TableRow key={entry.chunkId}>
                  <TableCell align="right">{entry.rank}</TableCell>
                  <DocumentTitleCell
                    documentKey={entry.documentKey}
                    documentTitle={entry.documentTitle}
                    onShowDocument={navigation.onShowDocument}
                  />
                  <TableCell>{entry.libraryName ?? '—'}</TableCell>
                  <TableCell align="center">
                    <ShowChunkButton chunkId={entry.chunkId} onShowChunk={navigation.onShowChunk} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
      )}
    </Box>
  )
}

/** Monospace, line breaks preserved: the chunk exactly as the index holds it, prefix included. */
function ChunkContent({ content }: { content: string }) {
  return (
    <Box
      component="pre"
      sx={{
        m: 0,
        p: 1.5,
        fontFamily: 'monospace',
        fontSize: '0.8125rem',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-word',
        bgcolor: 'action.hover',
        borderRadius: 1,
        maxHeight: 420,
        overflow: 'auto',
      }}
    >
      {content}
    </Box>
  )
}

const CHUNK_DIALOG_TITLE_ID = 'chunk-preview-title'

/**
 * Lazy chunk preview: nothing is fetched until a row's "Chunk anzeigen" is clicked, and the fetched
 * text leaves the DOM with the dialog rather than lingering hidden.
 */
function ChunkPreviewDialog({ chunkId, onClose }: { chunkId: string | null; onClose: () => void }) {
  // Keyed by chunk id so a stale result never shows under a newly requested id; the loading state
  // is simply "nothing loaded for this id yet".
  const [loaded, setLoaded] = useState<{
    chunkId: string
    chunk?: ChunkInspectionResponse
    error?: string
  } | null>(null)

  useEffect(() => {
    if (chunkId == null) return
    let cancelled = false
    getSearchChunk(chunkId)
      .then((chunk) => {
        if (!cancelled) setLoaded({ chunkId, chunk })
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setLoaded({
            chunkId,
            error: err instanceof Error ? err.message : 'Chunk konnte nicht geladen werden',
          })
        }
      })
    return () => {
      cancelled = true
    }
  }, [chunkId])

  const current = loaded !== null && loaded.chunkId === chunkId ? loaded : null
  const chunk = current?.chunk ?? null
  const error = current?.error ?? null

  const metadataEntries = chunk
    ? Object.entries(chunk.metadata).sort(([a], [b]) => a.localeCompare(b))
    : []

  return (
    <Dialog
      open={chunkId != null}
      onClose={onClose}
      maxWidth="md"
      fullWidth
      scroll="paper"
      aria-labelledby={CHUNK_DIALOG_TITLE_ID}
    >
      <DialogTitle id={CHUNK_DIALOG_TITLE_ID}>Chunk-Vorschau</DialogTitle>
      <DialogContent dividers>
        {error && <Alert severity="error">{error}</Alert>}
        {!error && !chunk && (
          <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center' }}>
            <CircularProgress size={20} />
            <Typography variant="body2" color="text.secondary">
              Chunk wird geladen …
            </Typography>
          </Stack>
        )}
        {chunk && (
          <Stack spacing={2}>
            <Box>
              <Typography variant="body2">
                Dokument: <strong>{chunk.documentTitle ?? '—'}</strong>
              </Typography>
              <Typography variant="body2">Bibliothek: {chunk.libraryName ?? '—'}</Typography>
              <Typography variant="body2">Chunk-Index: {chunk.chunkIndex ?? '—'}</Typography>
              <Stack direction="row" spacing={0.5} sx={{ alignItems: 'center' }}>
                <Typography variant="body2" sx={{ wordBreak: 'break-all' }}>
                  Chunk-ID: <code>{chunk.chunkId}</code>
                </Typography>
                <IconButton
                  size="small"
                  aria-label="Chunk-ID kopieren"
                  onClick={() => void navigator.clipboard?.writeText(chunk.chunkId).catch(() => {})}
                >
                  <ContentCopyIcon fontSize="inherit" />
                </IconButton>
              </Stack>
            </Box>
            <Box>
              <Typography component="h3" sx={{ fontSize: 14, fontWeight: 600, mb: 0.5 }}>
                Inhalt
              </Typography>
              <ChunkContent content={chunk.content} />
            </Box>
            <Box>
              <Typography component="h3" sx={{ fontSize: 14, fontWeight: 600, mb: 0.5 }}>
                Metadaten
              </Typography>
              {metadataEntries.length === 0 ? (
                <Typography variant="body2" color="text.secondary">
                  Zu diesem Chunk sind keine Metadaten gespeichert.
                </Typography>
              ) : (
                <Table size="small" aria-label="Chunk-Metadaten">
                  <TableBody>
                    {metadataEntries.map(([key, value]) => (
                      <TableRow key={key}>
                        <TableCell component="th" scope="row" sx={{ width: '30%' }}>
                          <code>{key}</code>
                        </TableCell>
                        <TableCell sx={{ wordBreak: 'break-word' }}>
                          {formatMetadataValue(value)}
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              )}
            </Box>
          </Stack>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Schließen</Button>
      </DialogActions>
    </Dialog>
  )
}

function DocumentChunkList({ document }: { document: DocumentChunksResponse }) {
  const stored = document.chunks.length
  return (
    <Box sx={{ mt: 2 }}>
      <Typography sx={{ fontSize: 14.5, fontWeight: 600 }}>
        {document.documentTitle ?? document.documentId}
      </Typography>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 1.5 }}>
        Bibliothek: {document.libraryName ?? '—'} ·{' '}
        {plural(stored, 'gespeicherter Chunk', 'gespeicherte Chunks')}, laut Dokument{' '}
        {document.chunkCount}
      </Typography>
      {stored !== document.chunkCount && (
        <Alert severity="warning" sx={{ mb: 1.5 }}>
          Die Zahl der gespeicherten Chunks ({stored}) weicht von der im Dokument vermerkten Anzahl
          ({document.chunkCount}) ab - der Index ist veraltet oder unvollständig geschrieben.
        </Alert>
      )}
      {stored === 0 ? (
        <Typography variant="body2" color="text.secondary">
          Für dieses Dokument ist kein Chunk gespeichert.
        </Typography>
      ) : (
        <Stack spacing={1}>
          {document.chunks.map((chunk) => {
            const location = chunk.metadata.location
            return (
              <Accordion
                key={chunk.chunkId}
                variant="outlined"
                disableGutters
                slotProps={{ heading: { component: 'h4' } }}
              >
                <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                  <Stack
                    direction="row"
                    spacing={1.5}
                    sx={{ alignItems: 'center', flexWrap: 'wrap', rowGap: 0.5 }}
                  >
                    <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                      Chunk {chunk.chunkIndex ?? '?'}
                    </Typography>
                    <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                      {plural(chunk.content.length, 'Zeichen', 'Zeichen')}
                    </Typography>
                    {typeof location === 'string' && location !== '' && (
                      <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
                        Fundort: {location}
                      </Typography>
                    )}
                  </Stack>
                </AccordionSummary>
                <AccordionDetails>
                  <ChunkContent content={chunk.content} />
                </AccordionDetails>
              </Accordion>
            )
          })}
        </Stack>
      )}
    </Box>
  )
}

export default function SearchIndexingAdminPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const status = useSearchAdminStore((s) => s.status)
  const profiles = useSearchAdminStore((s) => s.profiles)
  const statusError = useSearchAdminStore((s) => s.statusError)
  const diagnosis = useSearchAdminStore((s) => s.diagnosis)
  const diagnosisError = useSearchAdminStore((s) => s.diagnosisError)
  const running = useSearchAdminStore((s) => s.isRunningDiagnosis)
  const documentChunks = useSearchAdminStore((s) => s.documentChunks)
  const documentChunksError = useSearchAdminStore((s) => s.documentChunksError)
  const loadingDocumentChunks = useSearchAdminStore((s) => s.isLoadingDocumentChunks)
  const loadStatus = useSearchAdminStore((s) => s.loadStatus)
  const runDiagnosis = useSearchAdminStore((s) => s.runDiagnosis)
  const loadDocumentChunks = useSearchAdminStore((s) => s.loadDocumentChunks)
  const backfillRuns = useSearchAdminStore((s) => s.metadataBackfillRuns)
  const startMetadataBackfill = useSearchAdminStore((s) => s.startMetadataBackfill)
  const pauseMetadataBackfill = useSearchAdminStore((s) => s.pauseMetadataBackfill)

  const [question, setQuestion] = useState('')
  const [contextChoice, setContextChoice] = useState<string | null>(null)
  const [trackedDocumentId, setTrackedDocumentId] = useState('')
  const [previewChunkId, setPreviewChunkId] = useState<string | null>(null)
  const [documentIdInput, setDocumentIdInput] = useState('')
  const documentChunksSectionRef = useRef<HTMLDivElement>(null)

  // Derived rather than set from an effect once the profiles arrive: the preselected context is a
  // permission profile wherever one exists, the caller's own context otherwise - never a person,
  // which is not offered at all until #1052 delivers the Befugnis- und Protokollmodell.
  const contextValue = contextChoice ?? (profiles.length > 0 ? profiles[0].id : OWN_CONTEXT_VALUE)

  useEffect(() => {
    if (isSystemAdmin) void loadStatus()
  }, [isSystemAdmin, loadStatus])

  async function handleDiagnosis() {
    const contextType: SearchDiagnosisContextType =
      contextValue === OWN_CONTEXT_VALUE ? 'SELF' : 'PERMISSION_PROFILE'
    await runDiagnosis({
      question: question.trim(),
      contextType,
      permissionProfileId: contextType === 'PERMISSION_PROFILE' ? contextValue : undefined,
      trackedDocumentId: trackedDocumentId.trim() === '' ? undefined : trackedDocumentId.trim(),
    })
  }

  function showDocumentChunks(documentId: string) {
    setDocumentIdInput(documentId)
    void loadDocumentChunks(documentId)
    documentChunksSectionRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }

  const navigation: ChunkNavigation = {
    onShowChunk: setPreviewChunkId,
    onShowDocument: showDocumentChunks,
  }

  if (!isSystemAdmin) {
    return (
      <Box sx={{ flexGrow: 1, p: 4, maxWidth: 720 }}>
        <PageHeading title="Suche & Indexierung" gutterBottom />
        <Alert severity="info">
          Suche und Indexierung werden von der Systemverwaltung betreut. Für Ihr Konto ist diese
          Seite nicht freigegeben.
        </Alert>
      </Box>
    )
  }

  return (
    <Box sx={{ flexGrow: 1, p: { xs: 2.5, md: 5 }, overflowY: 'auto' }}>
      <PageHeading title="Suche & Indexierung" gutterBottom />
      <GlobalScopeNote>
        Diese Seite zeigt die aktive Konfiguration an und ändert sie nicht. Sie beantwortet, warum
        ein Dokument in einer Antwort steht oder fehlt. Der einzige Eingriff ist das Nachrüsten der
        Kernfelder je Bibliothek - ein bewusster Start, kein Automatismus.
      </GlobalScopeNote>

      {statusError && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {statusError}
        </Alert>
      )}

      <Box sx={{ mb: 4 }}>
        <SectionHead>Modellrollen</SectionHead>
        <Stack direction="row" spacing={2} sx={{ flexWrap: 'wrap', rowGap: 2 }}>
          {status?.modelRoles.map((role) => (
            <ModelRoleCard key={role.role} role={role} />
          ))}
        </Stack>
      </Box>

      <Box sx={{ mb: 4 }}>
        <SectionHead>Suchpfade</SectionHead>
        <Stack spacing={1.5}>
          {status?.searchPaths.map((path) => (
            <Paper key={path.path} variant="outlined" sx={{ p: 2 }}>
              <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', mb: 0.5 }}>
                <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
                  {PATH_LABELS[path.path]}
                </Typography>
                <Chip
                  size="small"
                  label={PATH_STATE_LABELS[path.state]}
                  color={path.state === 'ACTIVE' ? 'success' : 'warning'}
                  aria-label={`${PATH_LABELS[path.path]}: ${PATH_STATE_LABELS[path.state]}`}
                />
              </Stack>
              <Typography variant="body2" color="text.secondary">
                {path.detail}
              </Typography>
            </Paper>
          ))}
        </Stack>
      </Box>

      <Box sx={{ mb: 4 }}>
        <SectionHead>Indexstatus je Bibliothek</SectionHead>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          „Kernfelder" zeigt, wie viele Dokumente die aktuelle Extraktion der Kernfelder (Titel,
          Dokumentart, Datum/Stand) tragen und wie gut jedes Feld befüllt ist. Das Nachrüsten liest
          die Originaldateien in Chargen erneut; die Suche bleibt währenddessen verfügbar, ein
          angehaltener Lauf setzt beim nächsten unverarbeiteten Dokument fort.
        </Typography>
        <LibraryStatusTable
          libraries={status?.libraries ?? []}
          backfillRuns={backfillRuns}
          onStartBackfill={(libraryId) => void startMetadataBackfill(libraryId)}
          onPauseBackfill={pauseMetadataBackfill}
        />
      </Box>

      <Box sx={{ mb: 4 }}>
        <SectionHead>Diagnose</SectionHead>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Die Diagnose führt eine frisch eingegebene Testfrage im gewählten Rechtekontext aus und
          zeigt jede Stufe einzeln. Sie liest keine bestehenden Gespräche und beantwortet nur den
          jetzigen Zustand - sie ist kein Nachweis über zurückliegende Zugriffe.
        </Typography>
        <Stack spacing={2} sx={{ maxWidth: 720 }}>
          <TextField
            label="Testfrage"
            required
            value={question}
            onChange={(e) => setQuestion(e.target.value)}
            size="small"
            fullWidth
          />
          {/* Every library the chosen context may read is diagnosable; the per-library
              diagnosis lock of Leitplanke (e) needs the Befugnismodell of #1052 and does not
              exist yet. */}
          <TextField
            select
            label="Sicht als"
            value={contextValue}
            onChange={(e) => setContextChoice(e.target.value)}
            helperText="Rechteprofile und der eigene Rechtekontext. Der Rechtekontext einer bestimmten Person steht hier nicht zur Wahl."
            size="small"
            fullWidth
          >
            <MenuItem value={OWN_CONTEXT_VALUE}>Eigener Rechtekontext</MenuItem>
            {profiles.map((profile) => (
              <MenuItem key={profile.id} value={profile.id}>
                {`Rechteprofil „${profile.name}“ (${plural(profile.libraryCount, 'Bibliothek', 'Bibliotheken')})`}
              </MenuItem>
            ))}
          </TextField>
          <TextField
            label="Dokument verfolgen (optional)"
            value={trackedDocumentId}
            onChange={(e) => setTrackedDocumentId(e.target.value)}
            helperText="Kennung eines Dokuments aus der Dokumentliste einer Bibliothek. Die Diagnose sagt dann, ob es gar nicht gefunden oder in einer bestimmten Stufe verdrängt wurde."
            size="small"
            fullWidth
          />
          <Box>
            <Button
              variant="contained"
              size="small"
              onClick={() => void handleDiagnosis()}
              disabled={running || question.trim() === ''}
            >
              {running ? 'Diagnose läuft …' : 'Diagnose ausführen'}
            </Button>
          </Box>
        </Stack>
        {diagnosisError && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {diagnosisError}
          </Alert>
        )}
        {diagnosis && <DiagnosisResult diagnosis={diagnosis} navigation={navigation} />}
      </Box>

      <Box ref={documentChunksSectionRef}>
        <SectionHead>Chunks eines Dokuments</SectionHead>
        <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
          Zeigt alle gespeicherten Chunks eines Dokuments in Reihenfolge - so, wie die
          Indexierungs-Pipeline sie abgelegt hat, mit Zuschnitt, Kontextpräfix und Fundort. Ein
          Klick auf einen Dokumenttitel in der Diagnose führt hierher.
        </Typography>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'flex-start', maxWidth: 720 }}>
          <TextField
            label="Dokument-ID"
            value={documentIdInput}
            onChange={(e) => setDocumentIdInput(e.target.value)}
            size="small"
            fullWidth
          />
          <Button
            variant="outlined"
            size="small"
            onClick={() => showDocumentChunks(documentIdInput.trim())}
            disabled={loadingDocumentChunks || documentIdInput.trim() === ''}
            sx={{ flex: 'none', mt: 0.25 }}
          >
            {loadingDocumentChunks ? 'Lädt …' : 'Chunks laden'}
          </Button>
        </Stack>
        {documentChunksError && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {documentChunksError}
          </Alert>
        )}
        {documentChunks && <DocumentChunkList document={documentChunks} />}
      </Box>

      <ChunkPreviewDialog chunkId={previewChunkId} onClose={() => setPreviewChunkId(null)} />
    </Box>
  )
}
