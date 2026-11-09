import { useCallback, useEffect, useRef, useState } from 'react'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import IconButton from '@mui/material/IconButton'
import LinearProgress from '@mui/material/LinearProgress'
import TextField from '@mui/material/TextField'
import Tooltip from '@mui/material/Tooltip'
import Typography from '@mui/material/Typography'
import { keyframes } from '@mui/material/styles'
import CheckIcon from '@mui/icons-material/Check'
import CloseIcon from '@mui/icons-material/Close'
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutlined'
import MicNoneOutlinedIcon from '@mui/icons-material/MicNoneOutlined'
import PauseIcon from '@mui/icons-material/Pause'
import PlayArrowIcon from '@mui/icons-material/PlayArrow'
import SendIcon from '@mui/icons-material/Send'
import StopIcon from '@mui/icons-material/Stop'
import { currentUiLabVariant } from '@/shared/uiLabVariant'
import { LAB_WORD_MS, LAB_WORDS } from '../../mocks/mockSpeech'

/** Lab-only: which speech input variant this prototype shows, or `null` for the current one. */
export interface LabSpeechVariant {
  kind: 'a' | 'b' | 'c'
  live: boolean
}

export function labSpeechVariant(): LabSpeechVariant | null {
  const id = currentUiLabVariant()
  const match = id ? /^([abc])-(batch|live)$/.exec(id) : null
  if (!match) return null
  return { kind: match[1] as 'a' | 'b' | 'c', live: match[2] === 'live' }
}

export type LabStatus = 'idle' | 'recording' | 'paused' | 'processing' | 'review'

export interface LabSpeech {
  status: LabStatus
  active: boolean
  elapsed: number
  /** The live text so far; always empty without live. */
  interim: string
  /** The recognised text waiting for review (variant B). */
  review: string
  setReview: (text: string) => void
  start: () => void
  /** Ends the recording and resolves the recognised text. */
  stop: () => Promise<string>
  /** Ends the recording and keeps the text for review instead of handing it out. */
  stopToReview: () => Promise<void>
  pause: () => void
  resume: () => void
  cancel: () => void
}

/** A simulated recording: the speaker says one word every {@link LAB_WORD_MS}. */
export function useLabSpeech(live: boolean): LabSpeech {
  const [status, setStatus] = useState<LabStatus>('idle')
  const [shown, setShown] = useState(0)
  const [elapsedMs, setElapsedMs] = useState(0)
  const [review, setReview] = useState('')
  const shownRef = useRef(0)
  const generation = useRef(0)

  useEffect(() => {
    if (status !== 'recording') return
    const word = window.setInterval(() => {
      shownRef.current = Math.min(shownRef.current + 1, LAB_WORDS.length)
      setShown(shownRef.current)
    }, LAB_WORD_MS)
    const tick = window.setInterval(() => setElapsedMs((ms) => ms + 250), 250)
    return () => {
      window.clearInterval(word)
      window.clearInterval(tick)
    }
  }, [status])

  const start = useCallback(() => {
    generation.current += 1
    shownRef.current = 0
    setShown(0)
    setElapsedMs(0)
    setReview('')
    setStatus('recording')
  }, [])

  const finish = useCallback(async (): Promise<string | null> => {
    const mine = generation.current
    setStatus('processing')
    await new Promise((resolve) => window.setTimeout(resolve, live ? 350 : 1400))
    if (mine !== generation.current) return null
    return LAB_WORDS.slice(0, Math.max(shownRef.current, 3)).join(' ')
  }, [live])

  const stop = useCallback(async () => {
    const text = await finish()
    if (text === null) return ''
    setStatus('idle')
    return text
  }, [finish])

  const stopToReview = useCallback(async () => {
    const text = await finish()
    if (text === null) return
    setReview(text)
    setStatus('review')
  }, [finish])

  const cancel = useCallback(() => {
    generation.current += 1
    setStatus('idle')
    setReview('')
  }, [])

  // Escape discards wherever the focus is, also on the stop button just clicked.
  const active = status !== 'idle'
  useEffect(() => {
    if (!active) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') cancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [active, cancel])

  return {
    status,
    active: status !== 'idle',
    elapsed: Math.floor(elapsedMs / 1000),
    interim: live && status !== 'idle' ? LAB_WORDS.slice(0, shown).join(' ') : '',
    review,
    setReview,
    start,
    stop,
    stopToReview,
    pause: () => setStatus('paused'),
    resume: () => setStatus('recording'),
    cancel,
  }
}

export function formatElapsed(seconds: number): string {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

const bounce = keyframes`
  0%, 100% { transform: scaleY(0.25); }
  50% { transform: scaleY(1); }
`

const pulse = keyframes`
  0% { box-shadow: 0 0 0 0 rgba(198, 40, 40, 0.45); }
  100% { box-shadow: 0 0 0 10px rgba(198, 40, 40, 0); }
`

/** Level bars; frozen while paused. */
export function Waveform({ bars = 56, paused = false }: { bars?: number; paused?: boolean }) {
  return (
    <Box
      aria-hidden="true"
      sx={{ display: 'flex', alignItems: 'center', gap: '3px', height: 24, flex: 1, minWidth: 0 }}
    >
      {Array.from({ length: bars }, (_, i) => (
        <Box
          key={i}
          sx={{
            width: 3,
            height: `${30 + ((i * 37) % 70)}%`,
            borderRadius: 2,
            bgcolor: paused ? 'text.disabled' : 'primary.main',
            transform: paused ? 'scaleY(0.3)' : undefined,
            animation: paused
              ? 'none'
              : `${bounce} ${700 + ((i * 53) % 500)}ms ease-in-out infinite`,
            animationDelay: `${(i * 97) % 600}ms`,
            '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
          }}
        />
      ))}
    </Box>
  )
}

const iconButtonSx = { p: 1, borderRadius: '8px', alignSelf: 'flex-end' } as const

export function LabMicButton({ onClick, disabled }: { onClick: () => void; disabled?: boolean }) {
  return (
    <Tooltip title="Spracheingabe">
      <span style={{ alignSelf: 'flex-end' }}>
        <IconButton
          onClick={onClick}
          disabled={disabled}
          aria-label="Spracheingabe starten"
          sx={{ ...iconButtonSx, color: 'text.secondary' }}
        >
          <MicNoneOutlinedIcon sx={{ fontSize: 18 }} />
        </IconButton>
      </span>
    </Tooltip>
  )
}

/** Variant C: the microphone itself turns into a pulsing stop button. */
export function LabRecordingMicButton({ onClick }: { onClick: () => void }) {
  return (
    <Tooltip title="Diktat beenden, Text bleibt im Feld">
      <IconButton
        onClick={onClick}
        aria-label="Diktat beenden"
        sx={{
          ...iconButtonSx,
          color: '#fff',
          bgcolor: 'error.main',
          animation: `${pulse} 1.2s ease-out infinite`,
          '&:hover': { bgcolor: 'error.dark' },
          '@media (prefers-reduced-motion: reduce)': { animation: 'none' },
        }}
      >
        <MicNoneOutlinedIcon sx={{ fontSize: 18 }} />
      </IconButton>
    </Tooltip>
  )
}

export function LabIconButton({
  label,
  onClick,
  icon,
  tone = 'neutral',
}: {
  label: string
  onClick: () => void
  icon: 'close' | 'check' | 'stop' | 'pause' | 'play' | 'delete'
  tone?: 'neutral' | 'primary' | 'error'
}) {
  const Icon = {
    close: CloseIcon,
    check: CheckIcon,
    stop: StopIcon,
    pause: PauseIcon,
    play: PlayArrowIcon,
    delete: DeleteOutlineIcon,
  }[icon]
  return (
    <Tooltip title={label}>
      <IconButton
        onClick={onClick}
        aria-label={label}
        sx={{
          ...iconButtonSx,
          color:
            tone === 'primary'
              ? 'primary.main'
              : tone === 'error'
                ? 'error.main'
                : 'text.secondary',
          border: tone === 'primary' ? 1 : 0,
          borderColor: 'primary.main',
        }}
      >
        <Icon sx={{ fontSize: 18 }} />
      </IconButton>
    </Tooltip>
  )
}

/** Variant A: replaces the message field while recording. */
export function LabFieldRecording({ speech, draft }: { speech: LabSpeech; draft: string }) {
  const processing = speech.status === 'processing'
  return (
    <Box
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
      {(draft || speech.interim) && (
        <Typography component="div" sx={{ fontSize: 14, lineHeight: 1.5 }}>
          {draft}
          {draft && speech.interim ? ' ' : ''}
          <Box component="span" sx={{ color: 'text.secondary' }}>
            {speech.interim}
          </Box>
        </Typography>
      )}
      {processing ? (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, height: 24 }}>
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Text wird erkannt …
          </Typography>
          <LinearProgress sx={{ flex: 1 }} />
        </Box>
      ) : (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
          <Waveform />
          <Typography
            component="span"
            sx={{ fontSize: 12, color: 'text.secondary', fontVariantNumeric: 'tabular-nums' }}
          >
            {formatElapsed(speech.elapsed)}
          </Typography>
        </Box>
      )}
    </Box>
  )
}

/** Variant B: a card above the input; recording, then review and correction. */
export function LabRecordingCard({
  speech,
  live,
  onTakeOver,
  onSend,
}: {
  speech: LabSpeech
  live: boolean
  onTakeOver: (text: string) => void
  onSend: (text: string) => void
}) {
  if (!speech.active) return null
  const recording = speech.status === 'recording' || speech.status === 'paused'
  return (
    <Box
      role="region"
      aria-label="Spracheingabe"
      sx={{
        mb: 1,
        border: 1,
        borderColor: 'divider',
        borderRadius: '10px',
        bgcolor: 'background.paper',
        px: 1.75,
        py: 1.25,
        display: 'flex',
        flexDirection: 'column',
        gap: 1,
      }}
    >
      {recording && (
        <>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5 }}>
            <Box
              sx={{
                width: 8,
                height: 8,
                borderRadius: '50%',
                bgcolor: speech.status === 'paused' ? 'text.disabled' : 'error.main',
                flex: 'none',
              }}
            />
            <Typography
              sx={{
                fontSize: 13,
                fontWeight: 600,
                fontVariantNumeric: 'tabular-nums',
                flex: 'none',
              }}
            >
              {speech.status === 'paused' ? 'Pausiert' : 'Aufnahme'} {formatElapsed(speech.elapsed)}
            </Typography>
            <Waveform paused={speech.status === 'paused'} />
            <LabIconButton label="Aufnahme verwerfen" icon="delete" onClick={speech.cancel} />
            {speech.status === 'paused' ? (
              <LabIconButton label="Fortsetzen" icon="play" onClick={speech.resume} />
            ) : (
              <LabIconButton label="Pause" icon="pause" onClick={speech.pause} />
            )}
            <LabIconButton
              label="Aufnahme beenden"
              icon="stop"
              tone="error"
              onClick={() => void speech.stopToReview()}
            />
          </Box>
          <Typography
            component="div"
            sx={{ fontSize: 14, color: speech.interim ? 'text.secondary' : 'text.disabled' }}
          >
            {live
              ? speech.interim || 'Sprechen Sie, der Text erscheint hier mit.'
              : 'Der Text erscheint nach dem Beenden.'}
          </Typography>
        </>
      )}
      {speech.status === 'processing' && (
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, py: 0.5 }}>
          <Typography sx={{ fontSize: 13, color: 'text.secondary' }}>
            Text wird erkannt …
          </Typography>
          <LinearProgress sx={{ flex: 1 }} />
        </Box>
      )}
      {speech.status === 'review' && (
        <>
          <Typography sx={{ fontSize: 12, color: 'text.secondary' }}>
            Erkannter Text, bitte prüfen
          </Typography>
          <TextField
            multiline
            maxRows={6}
            size="small"
            autoFocus
            value={speech.review}
            onChange={(e) => speech.setReview(e.target.value)}
            slotProps={{ htmlInput: { 'aria-label': 'Erkannter Text' } }}
          />
          <Box sx={{ display: 'flex', gap: 1, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
            <Button size="small" color="inherit" onClick={speech.cancel}>
              Verwerfen
            </Button>
            <Button
              size="small"
              variant="outlined"
              onClick={() => {
                onTakeOver(speech.review)
                speech.cancel()
              }}
            >
              In Eingabe übernehmen
            </Button>
            <Button
              size="small"
              variant="contained"
              endIcon={<SendIcon sx={{ fontSize: 16 }} />}
              disabled={!speech.review.trim()}
              onClick={() => {
                onSend(speech.review)
                speech.cancel()
              }}
            >
              Senden
            </Button>
          </Box>
        </>
      )}
    </Box>
  )
}

/** Variant C: the quiet status line under the input while dictating. */
export function LabDictationStatus({ speech }: { speech: LabSpeech }) {
  if (!speech.active) return null
  return (
    <Typography
      component="div"
      aria-live="polite"
      sx={{
        fontSize: 12,
        color: 'text.secondary',
        display: 'flex',
        gap: 0.75,
        alignItems: 'center',
      }}
    >
      {speech.status === 'processing' ? (
        'Text wird erkannt …'
      ) : (
        <>
          <Box component="span" sx={{ color: 'error.main' }}>
            ●
          </Box>
          Hört zu · {formatElapsed(speech.elapsed)} · Mikrofon beendet, Senden schickt sofort, Esc
          verwirft das Diktierte
        </>
      )}
    </Typography>
  )
}
