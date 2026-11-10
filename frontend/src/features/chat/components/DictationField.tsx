import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import { LEVEL_HISTORY } from '../hooks/useDictation'
import type { ChatDictation } from './useChatDictation'

/** RMS that fills a bar; normal speech with gain control sits around 0.05-0.2. */
const FULL_SCALE_RMS = 0.2

function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}

interface DictationFieldProps {
  dictation: ChatDictation
  /** What was typed before dictating; it stays visible above the recording. */
  draft: string
}

/**
 * Stands in for the message field while recording and recognising: the typed draft, the live
 * text dimmed behind it, and the level of the microphone with the elapsed time - so a silent
 * microphone shows as a flat line, not as a lively animation. Not focusable; the buttons beside
 * it and Escape lead out.
 */
export default function DictationField({ dictation, draft }: DictationFieldProps) {
  const recording = dictation.status === 'recording'
  const history = dictation.levels
  const interim = dictation.interimText
  const bars = [...Array<number>(LEVEL_HISTORY - history.length).fill(0), ...history]

  return (
    <Box
      data-testid="dictation-field"
      sx={{
        flex: 1,
        minWidth: 0,
        px: 1.5,
        py: 1,
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
      }}
    >
      {(draft || interim) && (
        <Typography
          component="div"
          sx={{ fontSize: 14, lineHeight: 1.5, whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}
        >
          {draft}
          {draft && interim && !/\s$/.test(draft) ? ' ' : ''}
          {interim && (
            <Box component="span" data-testid="dictation-interim" sx={{ color: 'text.secondary' }}>
              {interim}
            </Box>
          )}
        </Typography>
      )}
      {recording ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 24 }}>
          <Box
            aria-hidden="true"
            sx={{
              display: 'flex',
              alignItems: 'center',
              gap: '3px',
              height: 24,
              flex: 1,
              minWidth: 0,
              // On a narrow screen the oldest readings drop off the left edge.
              overflow: 'hidden',
              justifyContent: 'flex-end',
            }}
          >
            {bars.map((value, index) => (
              <Box
                key={index}
                sx={{
                  width: 3,
                  flex: 'none',
                  borderRadius: 2,
                  bgcolor: 'primary.main',
                  height: `${Math.max(12, Math.min(100, (value / FULL_SCALE_RMS) * 100))}%`,
                  opacity: value > 0 ? 1 : 0.35,
                }}
              />
            ))}
          </Box>
          <Typography
            component="span"
            aria-hidden="true"
            sx={{ fontSize: 12, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}
          >
            {formatDuration(dictation.elapsedSeconds)}
          </Typography>
        </Box>
      ) : (
        <Box
          role="status"
          aria-label="Text wird erkannt"
          sx={{ display: 'flex', alignItems: 'center', gap: 1.5, minHeight: 24 }}
        >
          <Typography component="span" sx={{ fontSize: 13, color: 'text.secondary' }}>
            Text wird erkannt …
          </Typography>
        </Box>
      )}
    </Box>
  )
}
