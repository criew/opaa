import { Link as RouterLink } from 'react-router'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Link from '@mui/material/Link'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableContainer from '@mui/material/TableContainer'
import TableHead from '@mui/material/TableHead'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import SectionHead from '@/shared/ui/SectionHead'
import type { SearchDiagnosisResponse } from '@/shared/api/types'
import { plural } from '@/shared/utils/format'
import { catalogRoute } from '../../../shared/assetTypes'
import { trackedDocumentMessage } from '../utils/searchAdminLabels'
import StagePanel, { DocumentTitleCell, ShowChunkButton } from './DiagnosisStagePanel'
import type { ChunkNavigation } from './DiagnosisStagePanel'

/** The result of one diagnosis run: context, tracked document, every stage and the final selection. */
export default function DiagnosisResult({
  diagnosis,
  navigation,
}: {
  diagnosis: SearchDiagnosisResponse
  navigation: ChunkNavigation
}) {
  return (
    <Box sx={{ mt: 3 }}>
      <SectionHead component="h3">Ergebnis</SectionHead>
      <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
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
          {/* The lock is operated on the library's detail page. The link only leads into
             the catalog and names no locked library (guardrail (e): no title from
             a locked area). */}
          Bedient wird sie auf der Seite der Bibliothek, zu finden im{' '}
          <Link component={RouterLink} to={catalogRoute('KNOWLEDGE_LIBRARY')}>
            Katalog
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
        <Typography variant="body2" sx={{ color: 'text.secondary' }}>
          Die Endauswahl ist leer - in diesem Rechtekontext hätte diese Frage keinen Beleg.
        </Typography>
      ) : (
        <TableContainer>
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
