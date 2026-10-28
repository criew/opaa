import { useEffect } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SectionHead from '@/shared/ui/SectionHead'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { useSearchAdminStore } from '../stores/searchAdminStore'
import EmbeddingIndexSection from './EmbeddingIndexSection'
import LibraryStatusTable from './LibraryStatusTable'
import PrivateLibrariesSummary from './PrivateLibrariesSummary'

/** The content in the index per library - and the only two interventions of this page. */
export default function IndexStatusSection() {
  const status = useSearchAdminStore((s) => s.status)
  const statusError = useSearchAdminStore((s) => s.statusError)
  const loadStatus = useSearchAdminStore((s) => s.loadStatus)
  const metadataEnabled = useFeatureEnabled('metadata')
  const backfillRuns = useSearchAdminStore((s) => s.metadataBackfillRuns)
  const startMetadataBackfill = useSearchAdminStore((s) => s.startMetadataBackfill)
  const pauseMetadataBackfill = useSearchAdminStore((s) => s.pauseMetadataBackfill)
  const contextPrefixRuns = useSearchAdminStore((s) => s.contextPrefixRuns)
  const startContextPrefixRerun = useSearchAdminStore((s) => s.startContextPrefixRerun)
  const pauseContextPrefixRerun = useSearchAdminStore((s) => s.pauseContextPrefixRerun)

  useEffect(() => {
    void loadStatus()
  }, [loadStatus])

  return (
    <>
      {statusError && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {statusError}
        </Alert>
      )}

      <EmbeddingIndexSection />

      <Box sx={{ mb: 4 }}>
        <SectionHead>Indexstatus je Bibliothek</SectionHead>
        {metadataEnabled && (
          <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
            „Kernfelder" zeigt, wie viele Dokumente die aktuelle Extraktion tragen und wie viele je
            Feld noch ohne Wert sind. Beide Läufe lesen die Originaldateien in Chargen neu; die
            Suche bleibt verfügbar, ein angehaltener Lauf setzt später fort.
          </Typography>
        )}
        <LibraryStatusTable
          libraries={status?.libraries ?? []}
          backfillRuns={backfillRuns}
          contextPrefixRuns={contextPrefixRuns}
          onStartBackfill={(libraryId) => void startMetadataBackfill(libraryId)}
          onPauseBackfill={pauseMetadataBackfill}
          onStartContextPrefixRerun={(libraryId) => void startContextPrefixRerun(libraryId)}
          onPauseContextPrefixRerun={pauseContextPrefixRerun}
        />
        {status?.privateLibraries && <PrivateLibrariesSummary summary={status.privateLibraries} />}
      </Box>
    </>
  )
}
