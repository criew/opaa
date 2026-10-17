import { useEffect, useState } from 'react'
import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Dialog from '@mui/material/Dialog'
import DialogActions from '@mui/material/DialogActions'
import DialogContent from '@mui/material/DialogContent'
import DialogTitle from '@mui/material/DialogTitle'
import IconButton from '@mui/material/IconButton'
import Stack from '@mui/material/Stack'
import Table from '@mui/material/Table'
import TableBody from '@mui/material/TableBody'
import TableCell from '@mui/material/TableCell'
import TableRow from '@mui/material/TableRow'
import Typography from '@mui/material/Typography'
import ContentCopyIcon from '@mui/icons-material/ContentCopy'
import type { ChunkInspectionResponse } from '@/shared/api/types'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'
import { visibleChunkMetadata } from '@/features/library'
import { getSearchChunk } from '../api/searchAdminApi'
import { formatMetadataValue } from '../utils/searchAdminLabels'
import ChunkContent from './ChunkContent'

const CHUNK_DIALOG_TITLE_ID = 'chunk-preview-title'

/**
 * Lazy chunk preview: nothing is fetched until a row's "Chunk anzeigen" is clicked, and the fetched
 * text leaves the DOM with the dialog rather than lingering hidden.
 */
export default function ChunkPreviewDialog({
  chunkId,
  onClose,
}: {
  chunkId: string | null
  onClose: () => void
}) {
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

  const metadataEnabled = useFeatureEnabled('metadata')
  const metadataEntries = chunk ? visibleChunkMetadata(chunk.metadata, metadataEnabled) : []

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
            <Typography variant="body2" sx={{ color: 'text.secondary' }}>
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
                <Typography variant="body2" sx={{ color: 'text.secondary' }}>
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
