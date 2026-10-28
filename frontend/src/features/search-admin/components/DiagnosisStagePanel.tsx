import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Box from '@mui/material/Box'
import StateDot from '@/shared/ui/StateDot'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import ArticleOutlinedIcon from '@mui/icons-material/ArticleOutlined'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type {
  RetrievalFusionContributionResponse,
  RetrievalStageResponse,
} from '@/shared/api/types'
import { isUuid } from '@/shared/utils/format'
import { translateListLabel, translateStageNote } from '../utils/retrievalProtocolText'
import {
  OUTCOME_LABELS,
  REASON_LABELS,
  STAGE_LABELS,
  STAGE_STATUS_LABELS,
} from '../utils/searchAdminLabels'

/** The two navigations every diagnosis row offers: open one chunk, or jump to its document. */
export interface ChunkNavigation {
  onShowChunk: (chunkId: string) => void
  onShowDocument: (documentId: string) => void
}

/** The document cell of a diagnosis row: a link into the document's chunk list where the key is an id. */
export function DocumentTitleCell({
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

export function ShowChunkButton({
  chunkId,
  onShowChunk,
}: { chunkId: string } & Pick<ChunkNavigation, 'onShowChunk'>) {
  return (
    <IconButton size="small" aria-label="Chunk anzeigen" onClick={() => onShowChunk(chunkId)}>
      <ArticleOutlinedIcon fontSize="small" />
    </IconButton>
  )
}

/** What each list added to a fused candidate's score: list, rank and share of the total. */
function ContributionList({
  contributions,
}: {
  contributions: RetrievalFusionContributionResponse[]
}) {
  if (contributions.length === 0) {
    return <>—</>
  }
  const total = contributions.reduce((sum, contribution) => sum + contribution.value, 0)
  return (
    <Box component="ul" sx={{ m: 0, pl: 2 }}>
      {contributions.map((contribution) => (
        <li key={contribution.listLabel}>
          {translateListLabel(contribution.listLabel)}: Rang {contribution.rank}
          {total > 0 ? `, ${Math.round((100 * contribution.value) / total)} %` : ''}
        </li>
      ))}
    </Box>
  )
}

export default function StagePanel({
  stage,
  navigation,
}: {
  stage: RetrievalStageResponse
  navigation: ChunkNavigation
}) {
  const showContributions = stage.verdicts.some(
    (verdict) => (verdict.contributions?.length ?? 0) > 0,
  )
  return (
    <Accordion slotProps={{ heading: { component: 'h3' } }}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexGrow: 1 }}>
          <Typography sx={{ fontSize: 14, fontWeight: 600 }}>
            {STAGE_LABELS[stage.stage]}
          </Typography>
          <StateDot
            tone={stage.status === 'EXECUTED' ? 'neutral' : 'warning'}
            label={STAGE_STATUS_LABELS[stage.status]}
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
              <Typography key={note} variant="body2" sx={{ color: 'text.secondary' }}>
                {translateStageNote(note)}
              </Typography>
            ))}
          </Box>
        )}
        {stage.verdicts.length === 0 ? (
          <Typography variant="body2" sx={{ color: 'text.secondary' }}>
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
                  {showContributions && <TableCell>Beiträge</TableCell>}
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
                    {showContributions && (
                      <TableCell>
                        <ContributionList contributions={verdict.contributions ?? []} />
                      </TableCell>
                    )}
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
