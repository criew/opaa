import { useEffect, useRef, useState } from 'react'
import { Link as RouterLink } from 'react-router'
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
import Paper from '@mui/material/Paper'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import ArticleOutlinedIcon from '@mui/icons-material/ArticleOutlined'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import PageHeading from '../components/a11y/PageHeading'
import GlobalScopeNote from '../components/GlobalScopeNote'
import SectionHead from '../components/SectionHead'
import ChunkContent from '../components/searchadmin/ChunkContent'
import DiagnosisForm from '../components/searchadmin/DiagnosisForm'
import DocumentChunkSection, {
  type DocumentChunkSectionHandle,
} from '../components/searchadmin/DocumentChunkSection'
import LibraryStatusTable from '../components/searchadmin/LibraryStatusTable'
import { isUuid, plural } from '../components/searchadmin/format'

import type {
  ChunkInspectionResponse,
  RetrievalCandidateOutcome,
  RetrievalStage,
  RetrievalStageResponse,
  RetrievalStageStatus,
  RetrievalVerdictReason,
  SearchDiagnosisResponse,
  SearchModelRole,
  SearchModelRoleStatusResponse,
  SearchPathStatusResponse,
  TrackedDocumentResponse,
} from '../types/api'
import { translateListLabel, translateStageNote } from '../utils/retrievalProtocolText'
import { getSearchChunk } from '../services/api'
import { useAuthStore } from '../stores/authStore'
import { useSearchAdminStore } from '../stores/searchAdminStore'

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

const STAGE_LABELS: Record<RetrievalStage, string> = {
  SEARCH_SCOPE: 'Suchbereich',
  METADATA_FILTER: 'Metadatenfilter',
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
    case 'IN_LOCKED_AREA':
      return {
        severity: 'info',
        text:
          'Das verfolgte Dokument liegt in einem gesperrten Suchbereich. Die Diagnose trifft dazu ' +
          'keine Aussage - weder zu seinem Titel noch dazu, ob die Person es lesen darf.',
      }
    case 'OUTSIDE_SEARCH_SCOPE':
      // Without a name the run is a person context: it may then not say "Rechtefrage" either,
      // because a locked area is excluded for its own reason (Leitplanke (e)).
      return {
        severity: 'info',
        text: tracked.fileName
          ? `„${name}“ liegt außerhalb des Suchbereichs dieses Rechtekontexts` +
            `${tracked.libraryName ? ` (Bibliothek „${tracked.libraryName}“)` : ''}. ` +
            'Keine Suchstufe konnte es erreichen - das ist eine Rechtefrage, keine Suchfrage.'
          : 'Das verfolgte Dokument liegt außerhalb des durchsuchten Rechtekontexts. Keine ' +
            'Suchstufe konnte es erreichen; aus dieser Bibliothek zeigt die Diagnose nichts.',
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
      {diagnosis.lockedLibraryCount > 0 && (
        <Alert severity="info" sx={{ mb: 2 }}>
          {`In dieser Organisation ${diagnosis.lockedLibraryCount === 1 ? 'ist' : 'sind'} ${plural(diagnosis.lockedLibraryCount, 'Bibliothek', 'Bibliotheken')} für die Diagnose gesperrt; daraus zeigt die Diagnose nichts - weder Treffer noch Titel. Die Zahl gilt für den gesamten Bestand und sagt nichts darüber, was die betrachtete Person lesen darf. Aufheben kann die Sperre nur die für die Bibliothek zuständige Stelle, nicht die Systemverwaltung.`}{' '}
          {/* #1257/#1278 review: die Sperre selbst wird auf der Detailseite der jeweiligen
              Bibliothek bedient, nicht hier - der Link führt bewusst nur zur Bibliotheksliste,
              ohne eine der gesperrten Bibliotheken selbst zu nennen (Leitplanke (e): kein Titel
              aus einem gesperrten Bereich). */}
          Bedient wird sie in den{' '}
          <Link component={RouterLink} to="/libraries">
            Bibliotheken
          </Link>
          .
        </Alert>
      )}
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

export default function SearchIndexingAdminPage() {
  const isSystemAdmin = useAuthStore((s) => s.user?.systemRole === 'SYSTEM_ADMIN')
  const status = useSearchAdminStore((s) => s.status)
  const profiles = useSearchAdminStore((s) => s.profiles)
  const personContextAvailable = useSearchAdminStore((s) => s.personContextAvailable)
  const personContextHint = useSearchAdminStore((s) => s.personContextHint)
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

  const [previewChunkId, setPreviewChunkId] = useState<string | null>(null)
  const documentChunkSectionRef = useRef<DocumentChunkSectionHandle>(null)

  useEffect(() => {
    if (isSystemAdmin) void loadStatus()
  }, [isSystemAdmin, loadStatus])

  const navigation: ChunkNavigation = {
    onShowChunk: setPreviewChunkId,
    onShowDocument: (documentId) => documentChunkSectionRef.current?.showDocument(documentId),
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
        Kernfelder je Bibliothek — ein bewusster Start, kein Automatismus.
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
          „Kernfelder" zeigt, wie viele Dokumente die aktuelle Extraktion der Kernfelder tragen, wie
          gut jedes Feld befüllt ist und wie viele Dokumente je Feld noch ohne Wert sind — derselbe
          Pflege-Anker, den die Einstellungen der Bibliothek zeigen; von Hand als „kein Wert
          ermittelbar" gekennzeichnete Felder zählen nicht mit. Das Nachrüsten liest die
          Originaldateien in Chargen erneut; die Suche bleibt währenddessen verfügbar, ein
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
        <DiagnosisForm
          profiles={profiles}
          personContextAvailable={personContextAvailable}
          personContextHint={personContextHint}
          running={running}
          onRunDiagnosis={runDiagnosis}
        />
        {diagnosisError && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {diagnosisError}
          </Alert>
        )}
        {diagnosis && <DiagnosisResult diagnosis={diagnosis} navigation={navigation} />}
      </Box>

      <DocumentChunkSection
        ref={documentChunkSectionRef}
        documentChunks={documentChunks}
        documentChunksError={documentChunksError}
        loading={loadingDocumentChunks}
        onLoadDocumentChunks={(documentId) => void loadDocumentChunks(documentId)}
      />

      <ChunkPreviewDialog chunkId={previewChunkId} onClose={() => setPreviewChunkId(null)} />
    </Box>
  )
}
