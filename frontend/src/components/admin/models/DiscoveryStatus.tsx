import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { DiscoveryState } from './useModelDiscovery'

interface DiscoveryStatusProps {
  state: DiscoveryState
  /** What to do next while nothing has been fetched yet. */
  idleHint: string | null
}

const HAND_ENTRY = 'Die Kennung lässt sich trotzdem von Hand eintragen.'

/**
 * The outcome of „Modelle abrufen“ right under the address it belongs to: a quiet status line while
 * idle, loading or successful, an alert scoped to this spot when the endpoint could not be read.
 * A failure is a warning, not an error - the form stays fully usable by hand.
 */
export default function DiscoveryStatus({ state, idleHint }: DiscoveryStatusProps) {
  if (state.status === 'rejected') {
    return (
      <Alert severity="error" sx={{ mt: 1.5 }}>
        {state.message}
      </Alert>
    )
  }
  if (state.status === 'done' && !state.response.success) {
    const failure = state.response.failure
    return (
      <Alert severity="warning" sx={{ mt: 1.5 }}>
        <Box>{state.response.message}</Box>
        {failure !== 'NO_MODEL_LIST' && <Box sx={{ mt: 0.5 }}>{HAND_ENTRY}</Box>}
      </Alert>
    )
  }

  if (state.status === 'idle' && idleHint === null) return null
  let text = idleHint
  if (state.status === 'loading') {
    text = 'Modelle werden abgerufen …'
  } else if (state.status === 'done' && state.response.models.length === 0) {
    text = state.response.ollama
      ? `${state.response.message} Bei Ollama zuerst ein Modell laden, etwa mit „ollama pull qwen3:8b“.`
      : `${state.response.message} Tragen Sie die Modell-Kennung von Hand ein.`
  } else if (state.status === 'done') {
    text = `${state.response.message} Die Auswahl steht unter „Modell-Kennung“ bereit.`
  }
  return (
    <Typography
      role="status"
      aria-live="polite"
      sx={{ mt: 1, fontSize: 12.5, color: 'text.secondary' }}
    >
      {text}
    </Typography>
  )
}
