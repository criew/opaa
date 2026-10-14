import { useCallback, useEffect, useRef, useState } from 'react'
import { AxiosError } from 'axios'
import { transcribeRecording } from '../services/transcriptionApi'
import { isErrorResponse } from '@/shared/api/types'
import { startRecording as startMicrophoneRecording } from '../utils/audioRecording'
import type { Recording, RecordingOptions } from '../utils/audioRecording'

/** How dictated text reaches the input. Only one recording sent after the stop exists today. */
export type DictationMode = 'batch'

/** The transport half of dictation: what becomes of a finished recording. */
export interface DictationTransport {
  mode: DictationMode
  transcribe(recording: Blob): Promise<string>
}

export type DictationStatus = 'idle' | 'starting' | 'recording' | 'processing'

/** A recording ends on its own after this long; the person can always stop earlier. */
export const MAX_DICTATION_MS = 120_000
/** A recording ends on its own after this long without sound. */
export const DICTATION_SILENCE_MS = 15_000

const TRANSFER_FAILED =
  'Die Aufnahme konnte nicht übertragen werden. Bitte versuchen Sie es erneut oder tippen Sie Ihre Frage.'
const RECORDING_FAILED =
  'Die Aufnahme wurde unterbrochen. Bitte versuchen Sie es erneut oder tippen Sie Ihre Frage.'
const NOTHING_RECOGNISED = 'Es wurde kein Text erkannt. Bitte versuchen Sie es erneut.'

const RECORDING_TOO_LARGE = 'Die Aufnahme ist zu groß. Bitte sprechen Sie eine kürzere Frage ein.'

/**
 * The German message shown for a failed transcription: the backend's own message when its error
 * envelope arrived, otherwise a fixed text - never a browser, proxy or library message.
 */
function transcriptionError(err: unknown): string {
  if (err instanceof Error && err.message === TRANSFER_FAILED) return TRANSFER_FAILED
  const cause = err instanceof Error ? err.cause : undefined
  if (cause instanceof AxiosError) {
    const data: unknown = cause.response?.data
    if (isErrorResponse(data)) return data.error
    if (cause.response?.status === 413) return RECORDING_TOO_LARGE
  }
  return TRANSFER_FAILED
}

function isNetworkError(err: unknown): boolean {
  const cause = err instanceof Error ? err.cause : undefined
  return cause instanceof AxiosError && !cause.response
}

/**
 * Sends the recording; a network failure is retried exactly once, after which the recording is
 * dropped. A refusal from the server is not retried.
 */
async function transcribeWithOneRetry(recording: Blob): Promise<string> {
  try {
    return await transcribeRecording(recording)
  } catch (err) {
    if (!isNetworkError(err)) throw err
    try {
      return await transcribeRecording(recording)
    } catch (retryErr) {
      if (isNetworkError(retryErr)) throw new Error(TRANSFER_FAILED, { cause: retryErr })
      throw retryErr
    }
  }
}

export const batchTransport: DictationTransport = {
  mode: 'batch',
  transcribe: transcribeWithOneRetry,
}

function microphoneError(err: unknown): string {
  const name = err instanceof Error || err instanceof DOMException ? err.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return 'Der Zugriff auf das Mikrofon wurde nicht erlaubt. Sie können Ihre Frage weiterhin tippen.'
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') {
    return 'Es wurde kein Mikrofon gefunden. Sie können Ihre Frage weiterhin tippen.'
  }
  return 'Die Aufnahme konnte nicht gestartet werden. Sie können Ihre Frage weiterhin tippen.'
}

export interface UseDictationOptions {
  /** Receives the recognised text; it is never sent on its own. */
  onFinalText: (text: string) => void
  transport?: DictationTransport
  /** The recording half; replaceable for tests. */
  startRecording?: (options: RecordingOptions) => Promise<Recording>
}

export interface Dictation {
  status: DictationStatus
  mode: DictationMode
  start: () => Promise<void>
  /** Ends the recording and hands it to the transport. */
  stop: () => Promise<void>
  /** Ends the recording and drops it; nothing is sent. */
  cancel: () => void
  /** Text shown while speaking; always empty in batch mode. */
  interimText: string
  finalText: string | null
  elapsedSeconds: number
  error: string | null
}

/**
 * Dictation for the chat input: recording and transport are separate, so a live mode can
 * be added as a further transport without touching the recording or the input. The recording is
 * held in memory only and dropped once the transport is done with it; the microphone is released
 * on stop, cancel, failure and unmount.
 */
export function useDictation({
  onFinalText,
  transport = batchTransport,
  startRecording = startMicrophoneRecording,
}: UseDictationOptions): Dictation {
  const [status, setStatus] = useState<DictationStatus>('idle')
  const [finalText, setFinalText] = useState<string | null>(null)
  const [elapsedSeconds, setElapsedSeconds] = useState(0)
  const [error, setError] = useState<string | null>(null)

  const recordingRef = useRef<Recording | null>(null)
  const statusRef = useRef<DictationStatus>('idle')
  // Bumped by cancel and unmount: a result that arrives for an older generation is ignored.
  const generationRef = useRef(0)
  const limitTimerRef = useRef<number | null>(null)
  const tickTimerRef = useRef<number | null>(null)
  const onFinalTextRef = useRef(onFinalText)
  const stopRef = useRef<() => Promise<void>>(async () => {})
  const failRef = useRef<() => void>(() => {})

  useEffect(() => {
    onFinalTextRef.current = onFinalText
  }, [onFinalText])

  const changeStatus = useCallback((next: DictationStatus) => {
    statusRef.current = next
    setStatus(next)
  }, [])

  const clearTimers = useCallback(() => {
    if (limitTimerRef.current !== null) window.clearTimeout(limitTimerRef.current)
    if (tickTimerRef.current !== null) window.clearInterval(tickTimerRef.current)
    limitTimerRef.current = null
    tickTimerRef.current = null
  }, [])

  const stop = useCallback(async () => {
    if (statusRef.current !== 'recording') return
    const recording = recordingRef.current
    recordingRef.current = null
    clearTimers()
    if (!recording) return
    const generation = generationRef.current
    changeStatus('processing')
    try {
      let clip: Blob | null
      try {
        clip = await recording.stop()
      } catch {
        if (generation === generationRef.current) setError(RECORDING_FAILED)
        return
      }
      const pending = transport.transcribe(clip)
      clip = null
      const text = (await pending).trim()
      if (generation !== generationRef.current) return
      setFinalText(text)
      if (text) onFinalTextRef.current(text)
      else setError(NOTHING_RECOGNISED)
    } catch (err) {
      if (generation !== generationRef.current) return
      setError(transcriptionError(err))
    } finally {
      if (generation === generationRef.current) changeStatus('idle')
    }
  }, [changeStatus, clearTimers, transport])

  useEffect(() => {
    stopRef.current = stop
  }, [stop])

  const start = useCallback(async () => {
    if (statusRef.current !== 'idle') return
    const generation = generationRef.current
    setError(null)
    setFinalText(null)
    setElapsedSeconds(0)
    changeStatus('starting')
    let recording: Recording
    try {
      recording = await startRecording({
        silenceMs: DICTATION_SILENCE_MS,
        onSilence: () => void stopRef.current(),
        onFailure: () => failRef.current(),
      })
    } catch (err) {
      if (generation === generationRef.current) {
        setError(microphoneError(err))
        changeStatus('idle')
      }
      return
    }
    if (generation !== generationRef.current) {
      recording.cancel()
      return
    }
    recordingRef.current = recording
    changeStatus('recording')
    const startedAt = Date.now()
    tickTimerRef.current = window.setInterval(
      () => setElapsedSeconds(Math.floor((Date.now() - startedAt) / 1000)),
      1000,
    )
    limitTimerRef.current = window.setTimeout(() => void stopRef.current(), MAX_DICTATION_MS)
  }, [changeStatus, startRecording])

  const cancel = useCallback(() => {
    generationRef.current += 1
    clearTimers()
    recordingRef.current?.cancel()
    recordingRef.current = null
    setElapsedSeconds(0)
    changeStatus('idle')
  }, [changeStatus, clearTimers])

  useEffect(() => {
    failRef.current = () => {
      if (statusRef.current !== 'recording') return
      cancel()
      setError(RECORDING_FAILED)
    }
  }, [cancel])

  useEffect(
    () => () => {
      generationRef.current += 1
      clearTimers()
      recordingRef.current?.cancel()
      recordingRef.current = null
    },
    [clearTimers],
  )

  return {
    status,
    mode: transport.mode,
    start,
    stop,
    cancel,
    interimText: '',
    finalText,
    elapsedSeconds,
    error,
  }
}
