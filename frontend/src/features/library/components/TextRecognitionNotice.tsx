import { useEffect, useState } from 'react'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { TextAction } from '@/shared/ui/DetailSection'
import StateDot from '@/shared/ui/StateDot'
import type { TextRecognitionBlockListResponse, TextRecognitionBlock } from '@/shared/api/types'
import { getTextRecognitionBlocks, releaseTextRecognitionBlock } from '../api/indexingApi'

interface TextRecognitionNoticeProps {
  libraryId: string
  /** Whether the caller may release a lock (editor of the library and above). */
  canRelease: boolean
  /** Bumped when a run finishes or documents change - the state is read again. */
  refreshToken: number
}

/**
 * The text recognition of the library's scans (ADR-0069): how many wait for the service, and the
 * scans whose recognition is locked, with the reason and a release for a new attempt. Nothing is
 * shown while nothing waits and nothing is locked.
 */
export default function TextRecognitionNotice({
  libraryId,
  canRelease,
  refreshToken,
}: TextRecognitionNoticeProps) {
  const [state, setState] = useState<TextRecognitionBlockListResponse | null>(null)
  const [expanded, setExpanded] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [reload, setReload] = useState(0)

  useEffect(() => {
    let cancelled = false
    getTextRecognitionBlocks(libraryId)
      .then((response) => {
        if (!cancelled) setState(response)
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [libraryId, refreshToken, reload])

  const pending = state?.pending ?? 0
  const blocks = state?.items ?? []
  if (pending === 0 && blocks.length === 0) return null

  const release = async (block: TextRecognitionBlock) => {
    setError(null)
    try {
      await releaseTextRecognitionBlock(libraryId, block.documentId)
      setReload((value) => value + 1)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Die Sperre konnte nicht aufgehoben werden')
    }
  }

  return (
    <Box
      role="status"
      aria-label="Texterkennung gescannter Dokumente"
      sx={{ mb: 2, fontSize: 13, lineHeight: '22px' }}
      data-testid="text-recognition-notice"
    >
      {pending > 0 && (
        <Box>
          <StateDot
            tone="neutral"
            label={
              pending === 1
                ? '1 Scan wartet auf die Texterkennung'
                : `${pending} Scans warten auf die Texterkennung`
            }
          />
          <Typography sx={{ fontSize: 13, lineHeight: '22px', color: 'text.secondary', pl: 2 }}>
            Sie werden im nächsten Lauf oder beim Nachholen gelesen und sind bis dahin nicht
            durchsuchbar.
          </Typography>
        </Box>
      )}
      {blocks.length > 0 && (
        <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 2 }}>
          <Box sx={{ flexGrow: 1, minWidth: 0 }}>
            <StateDot
              tone="warning"
              label={
                blocks.length === 1
                  ? 'Texterkennung für 1 Scan gesperrt'
                  : `Texterkennung für ${blocks.length} Scans gesperrt`
              }
            />
          </Box>
          <Box sx={{ flex: 'none' }}>
            <TextAction
              onClick={() => setExpanded((value) => !value)}
              context="gesperrte Scans"
              aria-expanded={expanded}
            >
              {expanded ? 'Ausblenden' : 'Anzeigen'}
            </TextAction>
          </Box>
        </Box>
      )}
      {expanded && blocks.length > 0 && (
        <Box component="ul" sx={{ listStyle: 'none', m: 0, pl: 2 }}>
          {blocks.map((block) => (
            <Box
              component="li"
              key={block.documentId}
              sx={{ display: 'flex', alignItems: 'baseline', gap: 2, py: 0.5 }}
            >
              <Box sx={{ flexGrow: 1, minWidth: 0 }}>
                <Typography sx={{ fontSize: 13, lineHeight: '22px', overflowWrap: 'anywhere' }}>
                  {block.fileName}
                </Typography>
                <Typography sx={{ fontSize: 13, lineHeight: '22px', color: 'text.secondary' }}>
                  {block.reason} · {connectionLabel(block.connectionType)} ·{' '}
                  {formatBlockedAt(block.blockedAt)}
                </Typography>
              </Box>
              {canRelease && (
                <Box sx={{ flex: 'none' }}>
                  <TextAction onClick={() => void release(block)} context={block.fileName}>
                    Freigeben
                  </TextAction>
                </Box>
              )}
            </Box>
          ))}
        </Box>
      )}
      {error && (
        <Typography role="alert" sx={{ fontSize: 13, lineHeight: '22px', color: 'error.main' }}>
          {error}
        </Typography>
      )}
    </Box>
  )
}

function connectionLabel(connectionType: TextRecognitionBlock['connectionType']): string {
  return connectionType === 'OPENAI_VISION' ? 'Vision-Modell' : 'docling-serve'
}

/** „05.11.2026, 09:15“, the date-and-time form of the run list. */
function formatBlockedAt(value: string): string {
  return new Date(value).toLocaleString('de-DE', {
    day: '2-digit',
    month: '2-digit',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}
