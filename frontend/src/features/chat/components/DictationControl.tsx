import { useEffect, useRef } from 'react'
import Box from '@mui/material/Box'
import IconButton from '@mui/material/IconButton'
import Tooltip from '@mui/material/Tooltip'
import { alpha } from '@mui/material/styles'
import visuallyHidden from '@mui/utils/visuallyHidden'
import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'
import MicNoneOutlinedIcon from '@mui/icons-material/MicNoneOutlined'
import type { ChatDictation } from './useChatDictation'
import { iconSize } from '@/shared/theme/tokens'

interface DictationControlProps {
  dictation: ChatDictation
  disabled?: boolean
}

const buttonSx = { p: 1, borderRadius: '8px', alignSelf: 'flex-end' } as const

/**
 * The microphone next to the send button, and while recording the two ways out of it: discard
 * or take the text into the field. Sending at once is the send button's job. Shown only where
 * dictation is offered - an installation without transcription has no function to explain.
 */
export default function DictationControl({ dictation, disabled = false }: DictationControlProps) {
  const { status } = dictation
  const takeOverRef = useRef<HTMLButtonElement>(null)
  // The microphone button leaves with the start; the focus moves on to the way out of the recording.
  useEffect(() => {
    if (status === 'recording') takeOverRef.current?.focus()
  }, [status])
  if (!dictation.offered) return null

  const announcement =
    status === 'recording'
      ? 'Aufnahme läuft. Escape verwirft sie.'
      : status === 'processing'
        ? 'Die Aufnahme wird in Text umgewandelt.'
        : ''

  return (
    <>
      {status === 'recording' && (
        <>
          <Tooltip title="Aufnahme verwerfen (Esc)">
            <IconButton
              onClick={dictation.cancel}
              aria-label="Aufnahme verwerfen"
              sx={{ ...buttonSx, color: 'text.secondary' }}
            >
              <CloseIcon sx={{ fontSize: iconSize.md }} />
            </IconButton>
          </Tooltip>
          <Tooltip title="Fertig, Text ins Eingabefeld">
            <IconButton
              onClick={() => void dictation.stop()}
              ref={takeOverRef}
              aria-label="Fertig, Text ins Eingabefeld"
              sx={{
                ...buttonSx,
                color: 'primary.main',
                bgcolor: (theme) => alpha(theme.palette.primary.main, 0.1),
                '&:hover': { bgcolor: (theme) => alpha(theme.palette.primary.main, 0.18) },
              }}
            >
              <CheckIcon sx={{ fontSize: iconSize.md }} />
            </IconButton>
          </Tooltip>
        </>
      )}
      {(status === 'idle' || status === 'starting') && (
        <Tooltip title="Spracheingabe">
          <Box component="span" sx={{ alignSelf: 'flex-end' }}>
            <IconButton
              onClick={() => void dictation.start()}
              disabled={disabled || status === 'starting'}
              aria-label="Spracheingabe starten"
              sx={{ ...buttonSx, color: 'text.secondary' }}
            >
              <MicNoneOutlinedIcon sx={{ fontSize: iconSize.md }} />
            </IconButton>
          </Box>
        </Tooltip>
      )}
      <Box component="span" aria-live="polite" sx={visuallyHidden}>
        {announcement}
      </Box>
    </>
  )
}
