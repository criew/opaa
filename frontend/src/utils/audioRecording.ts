/**
 * Microphone recording for dictation (#1926), independent of how the recording is transcribed.
 * The clip lives only in memory - an array of chunks, then one Blob - and never touches IndexedDB,
 * the Cache API or any other storage. Every way out (stop, cancel, failure) ends the microphone
 * stream at once, so the browser's recording indicator goes out with it.
 */

/** One running recording. */
export interface Recording {
  /** Ends the recording and the microphone; resolves the clip. */
  stop(): Promise<Blob>
  /** Ends the recording and the microphone and drops what was recorded. */
  cancel(): void
}

export interface RecordingOptions {
  /** Called once when nothing above the noise floor was heard for `silenceMs`. */
  onSilence?: () => void
  silenceMs?: number
}

/** Preference order: Opus in WebM everywhere it exists, MP4 for older Safari. */
const MIME_TYPES = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4']

/** RMS below which a frame counts as silence (float samples in -1..1). */
const SILENCE_RMS = 0.01
const SILENCE_POLL_MS = 250

/** Whether this browser can record at all; without it the microphone is not offered. */
export function isRecordingSupported(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.MediaRecorder !== 'undefined' &&
    typeof navigator !== 'undefined' &&
    typeof navigator.mediaDevices?.getUserMedia === 'function'
  )
}

/** The first supported container, or `undefined` to let the browser choose. */
export function pickRecordingMimeType(
  isTypeSupported: (type: string) => boolean = (type) => MediaRecorder.isTypeSupported(type),
): string | undefined {
  return MIME_TYPES.find((type) => isTypeSupported(type))
}

/**
 * Asks for the microphone and starts recording. Mono with the browser's echo cancellation, noise
 * suppression and gain control; no resampling - the transcription server converts.
 */
export async function startRecording(options: RecordingOptions = {}): Promise<Recording> {
  const stream = await navigator.mediaDevices.getUserMedia({
    audio: {
      echoCancellation: true,
      noiseSuppression: true,
      autoGainControl: true,
      channelCount: 1,
    },
  })
  const stopTracks = () => stream.getTracks().forEach((track) => track.stop())

  const mimeType = pickRecordingMimeType()
  let recorder: MediaRecorder
  try {
    recorder = new MediaRecorder(stream, mimeType ? { mimeType } : undefined)
  } catch (err) {
    stopTracks()
    throw err
  }

  let chunks: Blob[] = []
  recorder.ondataavailable = (event: BlobEvent) => {
    if (event.data.size > 0) chunks.push(event.data)
  }
  const stopWatching = watchSilence(stream, options)
  let finished = false
  const release = () => {
    finished = true
    stopWatching()
    stopTracks()
  }

  try {
    recorder.start()
  } catch (err) {
    release()
    throw err
  }

  return {
    stop: () =>
      new Promise<Blob>((resolve, reject) => {
        if (finished) {
          reject(new Error('recording already ended'))
          return
        }
        recorder.onstop = () => {
          const clip = new Blob(chunks, { type: recorder.mimeType || mimeType || 'audio/webm' })
          chunks = []
          resolve(clip)
        }
        recorder.onerror = () => {
          chunks = []
          reject(new Error('recording failed'))
        }
        try {
          recorder.stop()
        } catch (err) {
          chunks = []
          reject(err instanceof Error ? err : new Error('recording failed'))
        } finally {
          release()
        }
      }),
    cancel: () => {
      if (finished) return
      recorder.ondataavailable = null
      recorder.onstop = null
      chunks = []
      try {
        if (recorder.state !== 'inactive') recorder.stop()
      } finally {
        release()
      }
    },
  }
}

/**
 * Watches the input level and calls `onSilence` once after `silenceMs` without sound. Returns the
 * function that ends the watch; a browser without Web Audio simply has no silence stop.
 */
function watchSilence(stream: MediaStream, options: RecordingOptions): () => void {
  const { onSilence, silenceMs } = options
  const AudioContextClass = typeof window !== 'undefined' ? window.AudioContext : undefined
  if (!onSilence || !silenceMs || !AudioContextClass) return () => {}

  let context: AudioContext
  try {
    context = new AudioContextClass()
  } catch {
    return () => {}
  }
  const analyser = context.createAnalyser()
  analyser.fftSize = 2048
  context.createMediaStreamSource(stream).connect(analyser)
  const samples = new Float32Array(analyser.fftSize)
  let lastSound = Date.now()
  let fired = false
  const timer = window.setInterval(() => {
    analyser.getFloatTimeDomainData(samples)
    let sum = 0
    for (const sample of samples) sum += sample * sample
    if (Math.sqrt(sum / samples.length) > SILENCE_RMS) {
      lastSound = Date.now()
    } else if (!fired && Date.now() - lastSound >= silenceMs) {
      fired = true
      onSilence()
    }
  }, SILENCE_POLL_MS)

  return () => {
    window.clearInterval(timer)
    void context.close().catch(() => {})
  }
}
