import { useEffect, useState } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Chip from '@mui/material/Chip'
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
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import PageHeading from '../components/a11y/PageHeading'
import GlobalScopeNote from '../components/GlobalScopeNote'
import SectionHead from '../components/SectionHead'

import type {
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
import { useAuthStore } from '../stores/authStore'
import { useSearchAdminStore } from '../stores/searchAdminStore'

const OWN_CONTEXT_VALUE = 'SELF'

/** German singular/plural, so the page never says "1 Bibliotheken". */
function plural(count: number, one: string, many: string) {
  return `${count} ${count === 1 ? one : many}`
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
  DOCUMENT_COMPLETION: 'Dokument-Vervollständigung',
}

const STAGE_STATUS_LABELS: Record<RetrievalStageStatus, string> = {
  EXECUTED: 'Ausgeführt',
  DISABLED: 'Abgeschaltet',
  NOT_REACHED: 'Nicht erreicht',
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

function LibraryStatusTable({ libraries }: { libraries: LibrarySearchStatusResponse[] }) {
  if (libraries.length === 0) {
    return (
      <Typography variant="body2" color="text.secondary">
        Es ist noch keine Wissensbibliothek angelegt.
      </Typography>
    )
  }
  return (
    <TableContainer component={Paper} variant="outlined">
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
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </TableContainer>
  )
}

function StagePanel({ stage }: { stage: RetrievalStageResponse }) {
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
                {note}
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
                </TableRow>
              </TableHead>
              <TableBody>
                {stage.verdicts.map((verdict) => (
                  <TableRow key={`${verdict.chunkId}-${verdict.listLabel ?? 'fusioniert'}`}>
                    <TableCell>{verdict.documentTitle ?? verdict.documentKey}</TableCell>
                    <TableCell>{verdict.libraryName ?? '—'}</TableCell>
                    <TableCell>{OUTCOME_LABELS[verdict.outcome]}</TableCell>
                    <TableCell>{REASON_LABELS[verdict.reason]}</TableCell>
                    <TableCell>{verdict.listLabel ?? 'fusioniert'}</TableCell>
                    <TableCell align="right">{verdict.rank ?? '—'}</TableCell>
                    <TableCell align="right">
                      {verdict.value == null ? '—' : verdict.value.toFixed(4)}
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

function DiagnosisResult({ diagnosis }: { diagnosis: SearchDiagnosisResponse }) {
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
          <StagePanel key={stage.stage} stage={stage} />
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
              </TableRow>
            </TableHead>
            <TableBody>
              {diagnosis.finalSelection.map((entry) => (
                <TableRow key={entry.chunkId}>
                  <TableCell align="right">{entry.rank}</TableCell>
                  <TableCell>{entry.documentTitle ?? entry.documentKey}</TableCell>
                  <TableCell>{entry.libraryName ?? '—'}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </TableContainer>
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
  const loadStatus = useSearchAdminStore((s) => s.loadStatus)
  const runDiagnosis = useSearchAdminStore((s) => s.runDiagnosis)

  const [question, setQuestion] = useState('')
  const [contextChoice, setContextChoice] = useState<string | null>(null)
  const [trackedDocumentId, setTrackedDocumentId] = useState('')

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
        Diese Seite zeigt die aktive Konfiguration an und ändert nichts. Sie beantwortet, warum ein
        Dokument in einer Antwort steht oder fehlt.
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
        <LibraryStatusTable libraries={status?.libraries ?? []} />
      </Box>

      <Box>
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
        {diagnosis && <DiagnosisResult diagnosis={diagnosis} />}
      </Box>
    </Box>
  )
}
