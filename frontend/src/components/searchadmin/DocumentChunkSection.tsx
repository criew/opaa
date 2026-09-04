import { useImperativeHandle, useRef, useState, type Ref } from 'react'
import Accordion from '@mui/material/Accordion'
import AccordionDetails from '@mui/material/AccordionDetails'
import AccordionSummary from '@mui/material/AccordionSummary'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import Stack from '@mui/material/Stack'
import TextField from '@mui/material/TextField'
import Typography from '@mui/material/Typography'
import ExpandMoreIcon from '@mui/icons-material/ExpandMore'

import type { DocumentChunksResponse } from '../../types/api'
import SectionHead from '../SectionHead'
import ChunkContent from './ChunkContent'
import { plural } from './format'

/** Lets the diagnosis result open a document here, the one navigation that reaches into this section. */
export interface DocumentChunkSectionHandle {
  showDocument: (documentId: string) => void
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

/**
 * The chunk list of one document, with the document id it is looked up by. The input lives here so
 * typing it never re-renders the rest of the page.
 */
export default function DocumentChunkSection({
  ref,
  documentChunks,
  documentChunksError,
  loading,
  onLoadDocumentChunks,
}: {
  ref?: Ref<DocumentChunkSectionHandle>
  documentChunks: DocumentChunksResponse | null
  documentChunksError: string | null
  loading: boolean
  onLoadDocumentChunks: (documentId: string) => void
}) {
  const [documentIdInput, setDocumentIdInput] = useState('')
  const sectionRef = useRef<HTMLDivElement>(null)

  function showDocument(documentId: string) {
    setDocumentIdInput(documentId)
    onLoadDocumentChunks(documentId)
    sectionRef.current?.scrollIntoView?.({ behavior: 'smooth', block: 'start' })
  }

  useImperativeHandle(ref, () => ({ showDocument }))

  return (
    <Box ref={sectionRef}>
      <SectionHead>Chunks eines Dokuments</SectionHead>
      <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
        Zeigt alle gespeicherten Chunks eines Dokuments in Reihenfolge - so, wie die
        Indexierungs-Pipeline sie abgelegt hat, mit Zuschnitt, Kontextpräfix und Fundort. Ein Klick
        auf einen Dokumenttitel in der Diagnose führt hierher.
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
          onClick={() => showDocument(documentIdInput.trim())}
          disabled={loading || documentIdInput.trim() === ''}
          sx={{ flex: 'none', mt: 0.25 }}
        >
          {loading ? 'Lädt …' : 'Chunks laden'}
        </Button>
      </Stack>
      {documentChunksError && (
        <Alert severity="error" sx={{ mt: 2 }}>
          {documentChunksError}
        </Alert>
      )}
      {documentChunks && <DocumentChunkList document={documentChunks} />}
    </Box>
  )
}
