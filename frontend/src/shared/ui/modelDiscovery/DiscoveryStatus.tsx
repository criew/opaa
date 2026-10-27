import Alert from '@mui/material/Alert'
import Box from '@mui/material/Box'
import Typography from '@mui/material/Typography'
import type { DiscoveryPurpose } from './modelDiscovery'
import type { DiscoveryState } from './useModelDiscovery'

interface DiscoveryStatusProps {
  state: DiscoveryState
  /** What to do next while nothing has been fetched yet. */
  idleHint: string | null
  /** The role the list is for; names the model to pull into an empty Ollama. */
  purpose?: DiscoveryPurpose
}

const HAND_ENTRY = 'Die Kennung lässt sich trotzdem von Hand eintragen.'
const PULL_EXAMPLE: Record<DiscoveryPurpose, string> = { chat: 'qwen3:8b', embedding: 'bge-m3' }

function lineFor(state: DiscoveryState, idleHint: string | null, purpose: DiscoveryPurpose) {
  if (state.status === 'loading') return 'Modelle werden abgerufen …'
  if (state.status !== 'done') return idleHint
  if (state.response.models.length === 0) {
    return state.response.ollama
      ? `${state.response.message} Bei Ollama zuerst ein Modell laden, etwa mit „ollama pull ${PULL_EXAMPLE[purpose]}“.`
      : `${state.response.message} Tragen Sie die Modell-Kennung von Hand ein.`
  }
  return `${state.response.message} Die Auswahl steht unter „Modell-Kennung“ bereit.`
}

/**
 * The outcome of „Modelle abrufen“ under the address it belongs to, in one live region that stays
 * in place, so every change - loading, result, failure - is announced from the same spot. A
 * failure is a warning inside it, not an alert of its own: the form stays usable by hand.
 */
export default function DiscoveryStatus({
  state,
  idleHint,
  purpose = 'chat',
}: DiscoveryStatusProps) {
  let content = null
  if (state.status === 'rejected') {
    content = (
      <Alert severity="error" role="presentation" sx={{ mt: 1.5 }}>
        {state.message}
      </Alert>
    )
  } else if (state.status === 'done' && !state.response.success) {
    content = (
      <Alert severity="warning" role="presentation" sx={{ mt: 1.5 }}>
        <Box>{state.response.message}</Box>
        {state.response.failure !== 'NO_MODEL_LIST' && <Box sx={{ mt: 0.5 }}>{HAND_ENTRY}</Box>}
      </Alert>
    )
  } else {
    const line = lineFor(state, idleHint, purpose)
    if (line !== null) {
      content = (
        <Typography sx={{ mt: 1, fontSize: 12.5, color: 'text.secondary' }}>{line}</Typography>
      )
    }
  }
  return (
    <Box role="status" aria-live="polite">
      {content}
    </Box>
  )
}
