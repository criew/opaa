import { useEffect, useRef, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import SectionHead from '@/shared/ui/SectionHead'
import { useSearchAdminStore } from '../stores/searchAdminStore'
import ChunkPreviewDialog from './ChunkPreviewDialog'
import DiagnosisForm from './DiagnosisForm'
import DiagnosisResult from './DiagnosisResult'
import type { ChunkNavigation } from './DiagnosisStagePanel'
import DocumentChunkSection, { type DocumentChunkSectionHandle } from './DocumentChunkSection'

/**
 * The diagnosis and a document's chunk view.
 *
 * Both deliberately live in one tab: a click on a document title in the diagnosis jumps
 * into the chunk view below. Separated, that would be a tab change with a parameter - without
 * a loading gain, since the chunk view fetches nothing by itself.
 */
export default function DiagnosisSection() {
  const profiles = useSearchAdminStore((s) => s.profiles)
  const personContextAvailable = useSearchAdminStore((s) => s.personContextAvailable)
  const personContextHint = useSearchAdminStore((s) => s.personContextHint)
  const contextError = useSearchAdminStore((s) => s.contextError)
  const diagnosis = useSearchAdminStore((s) => s.diagnosis)
  const diagnosisError = useSearchAdminStore((s) => s.diagnosisError)
  const running = useSearchAdminStore((s) => s.isRunningDiagnosis)
  const documentChunks = useSearchAdminStore((s) => s.documentChunks)
  const documentChunksError = useSearchAdminStore((s) => s.documentChunksError)
  const loadingDocumentChunks = useSearchAdminStore((s) => s.isLoadingDocumentChunks)
  const runDiagnosis = useSearchAdminStore((s) => s.runDiagnosis)
  const loadDocumentChunks = useSearchAdminStore((s) => s.loadDocumentChunks)
  const loadDiagnosisContext = useSearchAdminStore((s) => s.loadDiagnosisContext)

  const [previewChunkId, setPreviewChunkId] = useState<string | null>(null)
  const documentChunkSectionRef = useRef<DocumentChunkSectionHandle>(null)

  useEffect(() => {
    void loadDiagnosisContext()
  }, [loadDiagnosisContext])

  const navigation: ChunkNavigation = {
    onShowChunk: setPreviewChunkId,
    onShowDocument: (documentId) => documentChunkSectionRef.current?.showDocument(documentId),
  }

  return (
    <>
      {contextError && (
        <Alert severity="error" sx={{ mb: 3 }}>
          {contextError}
        </Alert>
      )}

      <Box sx={{ mb: 4 }}>
        <SectionHead>Diagnose</SectionHead>
        <Typography variant="body2" sx={{ color: 'text.secondary', mb: 2 }}>
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
    </>
  )
}
