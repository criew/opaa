import { useEffect, useState } from 'react'
import { getTranscriptionAvailability } from '../services/transcriptionApi'
import { isRecordingSupported } from '../utils/audioRecording'
import { useFeatureEnabled } from '@/shared/session/useFeatureEnabled'

/**
 * Whether the microphone is offered: the feature speech-input is on, the installation's
 * transcription role is usable and this browser can record. Asked once per mount; until the answer
 * is in - and after a failed load - the microphone stays hidden, because an installation without
 * transcription has no function to explain.
 */
export function useTranscriptionAvailability(): boolean {
  const featureEnabled = useFeatureEnabled('speechInput')
  const supported = isRecordingSupported()
  const [available, setAvailable] = useState(false)

  useEffect(() => {
    if (!featureEnabled || !supported) return
    let active = true
    void getTranscriptionAvailability()
      .then((answer) => {
        if (active) setAvailable(answer.available)
      })
      .catch(() => {
        if (active) setAvailable(false)
      })
    return () => {
      active = false
    }
  }, [featureEnabled, supported])

  return featureEnabled && supported && available
}
