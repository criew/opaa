import { useEffect } from 'react'
import { useDictation } from '../hooks/useDictation'
import type { Dictation } from '../hooks/useDictation'
import { useTranscriptionAvailability } from '../hooks/useTranscriptionAvailability'
import { startLiveTranscription } from '../utils/liveTranscription'

export interface ChatDictation extends Dictation {
  /** Whether the microphone is offered at all; without it nothing of dictation shows. */
  offered: boolean
  /** Recording or recognising: the field shows the recording instead of the text input. */
  busy: boolean
}

interface UseChatDictationOptions {
  /** Receives the recognised text after "Fertig"; the input decides where it goes. */
  onText: (text: string) => void
  /** Reports a failure in words, or `null` once it no longer applies. */
  onError: (message: string | null) => void
}

/**
 * Dictation as the chat input uses it: offered only where the installation and the browser can,
 * live where the server has a free live session. Escape discards a running recording or
 * recognition wherever the focus is, so the stop button just clicked does not swallow it.
 */
export function useChatDictation({ onText, onError }: UseChatDictationOptions): ChatDictation {
  const offer = useTranscriptionAvailability()
  const dictation = useDictation({
    onFinalText: onText,
    startLive: offer.realtimeAvailable ? startLiveTranscription : null,
  })
  const { error, status, cancel } = dictation
  const busy = status === 'recording' || status === 'processing'

  useEffect(() => {
    onError(error)
  }, [error, onError])

  useEffect(() => {
    if (!busy) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || event.isComposing) return
      event.preventDefault()
      cancel()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [busy, cancel])

  return { ...dictation, offered: offer.available, busy }
}
