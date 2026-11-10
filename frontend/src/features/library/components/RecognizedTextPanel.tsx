import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { ChunkAnchor } from '@/shared/api/types'
import RecognizedTextNotice from '@/shared/ui/RecognizedTextNotice'
import { getDocumentText } from '../api/libraryApi'
import { comparableText } from '../utils/textPassage'
import LoadingState from '@/shared/ui/states/LoadingState'

interface RecognizedTextPanelProps {
  id: string
  anchor: ChunkAnchor
  /** The cited passage; with both set, the panel shows its whole stored text. */
  documentId?: string | null
  chunkIndex?: number | null
}

/**
 * The text recognition read for the cited passage, beside the scan, so the reader compares it
 * with the original. It shows the passage's stored text when it still belongs to the citation -
 * a re-index may have renumbered the passages - and otherwise the anchor's own quotes, which the
 * citation keeps as a snapshot.
 */
export default function RecognizedTextPanel({
  id,
  anchor,
  documentId,
  chunkIndex,
}: RecognizedTextPanelProps) {
  const quotes = [anchor.quoteStart, anchor.quoteEnd].filter(Boolean).join(' … ')
  const key = documentId && chunkIndex != null ? `${documentId}#${chunkIndex}` : null
  const [loaded, setLoaded] = useState<{ key: string; text: string | null } | null>(null)

  useEffect(() => {
    if (!documentId || chunkIndex == null) return
    let cancelled = false
    const current = `${documentId}#${chunkIndex}`
    getDocumentText(documentId, { from: chunkIndex, to: chunkIndex }).then(
      (response) => {
        const passage = response.passages.find((entry) => entry.chunkIndex === chunkIndex)
        const text = passage && belongsToAnchor(passage.text, anchor) ? passage.text.trim() : null
        if (!cancelled) setLoaded({ key: current, text })
      },
      () => {
        if (!cancelled) setLoaded({ key: current, text: null })
      },
    )
    return () => {
      cancelled = true
    }
  }, [documentId, chunkIndex, anchor])

  const pending = key !== null && loaded?.key !== key
  const text = (loaded?.key === key ? loaded?.text : null) ?? quotes
  const section = anchor.path?.at(-1)
  return (
    <Box
      id={id}
      component="aside"
      aria-label="Erkannter Text"
      tabIndex={0}
      sx={{
        width: { xs: 'auto', sm: 240, md: 300 },
        maxHeight: { xs: '40%', sm: 'none' },
        flex: 'none',
        overflow: 'auto',
        borderStyle: 'solid',
        borderColor: 'divider',
        borderWidth: { xs: '1px 0 0 0', sm: '0 0 0 1px' },
        pl: { xs: 0, sm: 1.5 },
        pt: { xs: 1, sm: 0 },
      }}
    >
      <Typography component="h3" sx={{ fontSize: 13, fontWeight: 600, mb: 0.25 }}>
        Erkannter Text
      </Typography>
      <Typography sx={{ fontSize: 12, color: 'text.secondary', mb: 1 }}>
        {[pageLabel(anchor), section].filter(Boolean).join(' · ')}
      </Typography>
      {pending ? (
        <LoadingState shape="lines" count={3} />
      ) : (
        <Typography
          component="blockquote"
          data-testid="recognized-text"
          sx={{
            m: 0,
            pl: 1.25,
            borderLeft: 3,
            borderColor: 'divider',
            fontSize: 13.5,
            whiteSpace: 'pre-wrap',
            overflowWrap: 'anywhere',
          }}
        >
          {text}
        </Typography>
      )}
      <Box sx={{ mt: 1.5 }}>
        <RecognizedTextNotice fontSize={12} />
      </Box>
    </Box>
  )
}

function pageLabel(anchor: ChunkAnchor): string | null {
  if (anchor.pageFrom == null) return null
  return anchor.pageTo != null && anchor.pageTo !== anchor.pageFrom
    ? `Seiten ${anchor.pageFrom}–${anchor.pageTo}`
    : `Seite ${anchor.pageFrom}`
}

/** Whether a stored passage still is the cited one: it holds the anchor's closing quote. */
function belongsToAnchor(text: string, anchor: ChunkAnchor): boolean {
  const probe = anchor.quoteEnd ?? anchor.quoteStart
  if (!probe) return true
  return comparableText(text).includes(comparableText(probe))
}
