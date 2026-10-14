import { useEffect } from 'react'
import Box from '@mui/material/Box'
import CircularProgress from '@mui/material/CircularProgress'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import visuallyHidden from '@mui/utils/visuallyHidden'
import CloseIcon from '@mui/icons-material/Close'
import MicNoneOutlinedIcon from '@mui/icons-material/MicNoneOutlined'
import StopIcon from '@mui/icons-material/Stop'
import { useDictation } from '../hooks/useDictation'
import type { DictationTransport } from '../hooks/useDictation'
import { useTranscriptionAvailability } from '../hooks/useTranscriptionAvailability'
import type { Recording, RecordingOptions } from '../utils/audioRecording'

interface DictationControlProps {
  /** Receives the recognised text; the input decides where it goes. Never sends. */
  onText: (text: string) => void
  /** Reports a failure in words, or `null` once it no longer applies. */
  onError: (message: string | null) => void
  disabled?: boolean
  /** Replaceable for tests. */
  transport?: DictationTransport
  startRecording?: (options: RecordingOptions) => Promise<Recording>
}

function formatDuration(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}

/**
 * The microphone next to the send button. Shown only where the installation offers
 * dictation and the browser can record - an installation without transcription has no function to
 * explain. Dictating is optional; typing stays equivalent.
 */
export default function DictationControl(props: DictationControlProps) {
  const available = useTranscriptionAvailability()
  if (!available) return null
  return <DictationButtons {...props} />
}

function DictationButtons({
  onText,
  onError,
  disabled = false,
  transport,
  startRecording,
}: DictationControlProps) {
  const dictation = useDictation({ onFinalText: onText, transport, startRecording })
  const { status, error, elapsedSeconds } = dictation

  useEffect(() => {
    onError(error)
  }, [error, onError])

  const announcement =
    status === 'recording'
      ? 'Aufnahme läuft.'
      : status === 'processing'
        ? 'Die Aufnahme wird in Text umgewandelt.'
        : ''

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, alignSelf: 'flex-end' }}>
      {status === 'recording' && (
        <>
          <Typography
            component="span"
            sx={{ fontSize: 12, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}
            aria-hidden="true"
          >
            {formatDuration(elapsedSeconds)}
          </Typography>
          <Tooltip title="Aufnahme verwerfen">
            <IconButton
              size="small"
              onClick={dictation.cancel}
              aria-label="Aufnahme verwerfen"
              sx={{ color: 'text.secondary' }}
            >
              <CloseIcon sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
          <Tooltip title="Aufnahme beenden">
            <IconButton
              onClick={() => void dictation.stop()}
              aria-label="Aufnahme beenden"
              sx={{ p: 1, borderRadius: '8px', color: 'error.main' }}
            >
              <StopIcon sx={{ fontSize: 18 }} />
            </IconButton>
          </Tooltip>
        </>
      )}
      {status === 'processing' && (
        <Box
          role="status"
          aria-label="Text wird erkannt"
          sx={{ display: 'flex', p: 1, color: 'text.secondary' }}
        >
          <CircularProgress size={18} color="inherit" />
        </Box>
      )}
      {(status === 'idle' || status === 'starting') && (
        <Tooltip title="Spracheingabe">
          <span>
            <IconButton
              onClick={() => void dictation.start()}
              disabled={disabled || status === 'starting'}
              aria-label="Spracheingabe starten"
              sx={{ p: 1, borderRadius: '8px', color: 'text.secondary' }}
            >
              <MicNoneOutlinedIcon sx={{ fontSize: 18 }} />
            </IconButton>
          </span>
        </Tooltip>
      )}
      <Box component="span" aria-live="polite" sx={visuallyHidden}>
        {announcement}
      </Box>
    </Box>
  )
}
