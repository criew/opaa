import { useEffect, useState } from 'react'
import { getTranscriptionAvailability } from '../api/transcriptionApi'
import { isRecordingSupported } from '../utils/audioRecording'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'

/** What dictation the installation offers this browser. */
export interface TranscriptionOffer {
  available: boolean
  /** Whether a live transcript may be tried beside the recording; never without `available`. */
  realtimeAvailable: boolean
}

const NONE: TranscriptionOffer = { available: false, realtimeAvailable: false }

/** Whether this browser can feed live audio: an audio worklet and WebSockets. */
function isLiveSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.WebSocket !== 'undefined' &&
    typeof window.AudioContext !== 'undefined' &&
    typeof window.AudioWorkletNode !== 'undefined'
  )
}

/**
 * Whether the microphone is offered: the feature speech-input is on, the installation's
 * transcription role is usable and this browser can record. Asked once per mount; until the answer
 * is in - and after a failed load - the microphone stays hidden, because an installation without
 * transcription has no function to explain. Live is offered on top only where the server has a
 * free live session at the time of asking and the browser can feed it.
 */
export function useTranscriptionAvailability(): TranscriptionOffer {
  const featureEnabled = useFeatureEnabled('speechInput')
  const supported = isRecordingSupported()
  const [offer, setOffer] = useState<TranscriptionOffer>(NONE)

  useEffect(() => {
    if (!featureEnabled || !supported) return
    let active = true
    void getTranscriptionAvailability()
      .then((answer) => {
        if (active) {
          setOffer({
            available: answer.available,
            realtimeAvailable: answer.available && answer.realtimeAvailable && isLiveSupported(),
          })
        }
      })
      .catch(() => {
        if (active) setOffer(NONE)
      })
    return () => {
      active = false
    }
  }, [featureEnabled, supported])

  return featureEnabled && supported ? offer : NONE
}
