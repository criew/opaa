import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Box from '@mui/material/Box'
import StateDot from '@/shared/ui/StateDot'
import AdminList, { ListText } from '@/shared/ui/list/AdminList'
import IconButton from '@mui/material/IconButton'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Typography from '@mui/material/Typography'
import ArticleOutlinedIcon from '@mui/icons-material/ArticleOutlined'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'
import type {
  RetrievalFusionContributionResponse,
  RetrievalStageResponse,
} from '@/shared/api/types'
import { isUuid } from '@/shared/utils/format'
import {
  formatStageDuration,
  translateListLabel,
  translateStageNote,
} from '../utils/retrievalProtocolText'
import {
  OUTCOME_LABELS,
  REASON_LABELS,
  STAGE_LABELS,
  STAGE_STATUS_LABELS,
} from '../utils/searchAdminLabels'
import { fontSize } from '@/shared/theme/tokens'

/** The two navigations every diagnosis row offers: open one chunk, or jump to its document. */
export interface ChunkNavigation {
  onShowChunk: (chunkId: string) => void
  onShowDocument: (documentId: string) => void
}

/**
 * The document of a diagnosis row with its library below: a link into the document's chunk list
 * where the key is an id.
 */
export function DocumentTitle({
  documentKey,
  documentTitle,
  libraryName,
  onShowDocument,
}: {
  documentKey: string
  documentTitle: string | null | undefined
  libraryName: string | null | undefined
  onShowDocument: (documentId: string) => void
}) {
  const label = documentTitle ?? documentKey
  return (
    <Box sx={{ minWidth: 0 }}>
      {isUuid(documentKey) ? (
        <Link
          component="button"
          type="button"
          variant="body2"
          title={label}
          onClick={() => onShowDocument(documentKey)}
          sx={{
            display: 'block',
            maxWidth: '100%',
            textAlign: 'left',
            overflow: 'hidden',
            textOverflow: 'ellipsis',
            whiteSpace: 'nowrap',
          }}
        >
          {label}
        </Link>
      ) : (
        <ListText>{label}</ListText>
      )}
      <ListText secondary>{libraryName ?? '—'}</ListText>
    </Box>
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
  const duration = formatStageDuration(stage.durationMs)
  const showContributions = stage.verdicts.some(
    (verdict) => (verdict.contributions?.length ?? 0) > 0,
  )
  return (
    <Accordion slotProps={{ heading: { component: 'h3' } }}>
      <AccordionSummary expandIcon={<ExpandMoreIcon />}>
        <Stack direction="row" spacing={1.5} sx={{ alignItems: 'center', flexGrow: 1 }}>
          <Typography sx={{ fontSize: fontSize.text, fontWeight: 600 }}>
            {STAGE_LABELS[stage.stage]}
          </Typography>
          <StateDot
            tone={stage.status === 'EXECUTED' ? 'neutral' : 'warning'}
            label={STAGE_STATUS_LABELS[stage.status]}
          />
          <Typography sx={{ fontSize: fontSize.text, color: 'text.secondary', ml: 'auto', mr: 1 }}>
            {stage.incomingCount} → {stage.outgoingCount} Kandidaten
            {duration && ` · ${duration}`}
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
          <AdminList
            label={`Kandidaten der Stufe ${STAGE_LABELS[stage.stage]}`}
            rows={stage.verdicts}
            rowKey={(verdict) => `${verdict.chunkId}-${verdict.listLabel ?? 'fusioniert'}`}
            rowLabel={(verdict) => verdict.documentTitle ?? verdict.documentKey}
            minWidth={760}
            columns={[
              {
                id: 'document',
                head: 'Dokument',
                cell: (verdict) => (
                  <DocumentTitle
                    documentKey={verdict.documentKey}
                    documentTitle={verdict.documentTitle}
                    libraryName={verdict.libraryName}
                    onShowDocument={navigation.onShowDocument}
                  />
                ),
              },
              {
                id: 'outcome',
                head: 'Ergebnis',
                width: '24%',
                cell: (verdict) => (
                  <>
                    <ListText>{OUTCOME_LABELS[verdict.outcome]}</ListText>
                    <ListText secondary>{REASON_LABELS[verdict.reason]}</ListText>
                  </>
                ),
              },
              {
                id: 'list',
                head: 'Liste',
                width: '14%',
                cell: (verdict) => translateListLabel(verdict.listLabel),
              },
              {
                id: 'rank',
                head: 'Rang',
                width: 64,
                align: 'right',
                cell: (verdict) => verdict.rank ?? '—',
              },
              {
                id: 'value',
                head: 'Wert',
                width: 88,
                align: 'right',
                cell: (verdict) => (verdict.value == null ? '—' : verdict.value.toFixed(4)),
              },
              ...(showContributions
                ? [
                    {
                      id: 'contributions',
                      head: 'Beiträge',
                      width: '20%',
                      wrap: true,
                      cell: (verdict: (typeof stage.verdicts)[number]) => (
                        <ContributionList contributions={verdict.contributions ?? []} />
                      ),
                    },
                  ]
                : []),
            ]}
            rowActions={(verdict) => (
              <ShowChunkButton chunkId={verdict.chunkId} onShowChunk={navigation.onShowChunk} />
            )}
          />
        )}
      </AccordionDetails>
    </Accordion>
  )
}
