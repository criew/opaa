import type { Recording, RecordingOptions } from '../utils/audioRecording'
import type { StartLiveTranscription } from '../utils/liveTranscription'

/** What the simulated speaker says; "Bau Antrag" is a deliberate misrecognition to correct. */
export const LAB_SPOKEN_TEXT =
  'Welche Unterlagen brauche ich für einen Bau Antrag im Außenbereich, und wie lange dauert die Prüfung?'

export const LAB_WORDS = LAB_SPOKEN_TEXT.split(' ')

/** One spoken word every this many milliseconds. */
export const LAB_WORD_MS = 330

/** A recording without a microphone, for the mock mode and the prototype. */
export async function mockStartRecording(options: RecordingOptions = {}): Promise<Recording> {
  void options
  await new Promise((resolve) => window.setTimeout(resolve, 150))
  return {
    stop: async () => new Blob([new Uint8Array(16)], { type: 'audio/webm' }),
    cancel: () => {},
    stream: typeof MediaStream !== 'undefined' ? new MediaStream() : undefined,
  }
}

/** A live transcription that reveals the spoken text word by word. */
export const mockStartLive: StartLiveTranscription = ({ onText }) => {
  let shown = 0
  const timer = window.setInterval(() => {
    if (shown >= LAB_WORDS.length) return
    shown += 1
    onText(LAB_WORDS.slice(0, shown).join(' '))
  }, LAB_WORD_MS)
  return {
    finish: async () => {
      window.clearInterval(timer)
      await new Promise((resolve) => window.setTimeout(resolve, 300))
      return LAB_WORDS.slice(0, Math.max(shown, 1)).join(' ')
    },
    cancel: () => window.clearInterval(timer),
  }
}
