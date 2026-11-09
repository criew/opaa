import { openTranscriptionStream } from '../api/transcriptionApi'

/** One live transcription running beside the recording. */
export interface LiveTranscription {
  /**
   * Ends the audio and asks for the final text. Rejects when live broke at any point or the final
   * text does not arrive in time; the recording made beside it is then transcribed as a whole.
   */
  finish(): Promise<string>
  /** Ends everything at once; no text follows. */
  cancel(): void
}

export interface LiveTranscriptionOptions {
  /** The microphone stream of the recording; live reads it and never stops its tracks. */
  stream: MediaStream
  /** The text recognised so far, as a whole. */
  onText: (text: string) => void
  /** Live ended before its final text; called at most once, never after `finish` or `cancel`. */
  onBroken: () => void
}

export type StartLiveTranscription = (options: LiveTranscriptionOptions) => LiveTranscription

/** How long the server may take to accept the session and name its sample rate. */
export const READY_TIMEOUT_MS = 8_000
/** How long the final text may take after the commit. */
export const DONE_TIMEOUT_MS = 10_000
/** Audio not yet sent beyond this many bytes means the connection cannot keep up. */
const MAX_BUFFERED_BYTES = 1_000_000

const WORKLET_URL = '/pcm16-capture-worklet.js'

type ServerMessage =
  | { type: 'ready'; sampleRate: number }
  | { type: 'delta'; text: string }
  | { type: 'done'; text: string }
  | { type: 'error'; reason?: string }

function parse(data: unknown): ServerMessage | null {
  if (typeof data !== 'string') return null
  try {
    const message = JSON.parse(data) as ServerMessage
    return typeof message?.type === 'string' ? message : null
  } catch {
    return null
  }
}

/**
 * Starts live transcription over OPAA's WebSocket: waits for `ready`, then feeds the microphone
 * through an audio worklet as PCM16 at the server's rate and passes every `delta` on. Audio and text
 * stay in memory; nothing is stored. Any failure only marks live as broken - the recording beside
 * it is the fallback, so no error is shown for live itself.
 */
export const startLiveTranscription: StartLiveTranscription = ({ stream, onText, onBroken }) => {
  let socket: WebSocket | null = null
  let context: AudioContext | null = null
  let node: AudioWorkletNode | null = null
  let ended = false
  let broken = false
  let committed = false
  let readyTimer: number | null = null
  let settle: { resolve: (text: string) => void; reject: (err: Error) => void } | null = null
  let flushed: (() => void) | null = null

  const releaseAudio = () => {
    node?.port.close()
    node?.disconnect()
    node = null
    if (context) void context.close().catch(() => {})
    context = null
  }

  const close = () => {
    if (readyTimer !== null) window.clearTimeout(readyTimer)
    readyTimer = null
    releaseAudio()
    if (socket && socket.readyState <= WebSocket.OPEN) socket.close(1000)
    socket = null
  }

  const breakLive = () => {
    if (broken || ended) return
    broken = true
    close()
    if (settle) settle.reject(new Error('live transcription ended'))
    else onBroken()
    settle = null
  }

  async function startAudio(sampleRate: number) {
    const AudioContextClass = window.AudioContext
    if (!AudioContextClass || typeof AudioWorkletNode === 'undefined') throw new Error('no worklet')
    context = new AudioContextClass()
    await context.audioWorklet.addModule(WORKLET_URL)
    if (ended || broken || !context) return
    node = new AudioWorkletNode(context, 'pcm16-capture', {
      processorOptions: { targetRate: sampleRate },
    })
    node.port.onmessage = (event: MessageEvent<ArrayBuffer | string>) => {
      if (event.data === 'flushed') {
        flushed?.()
        return
      }
      if (!socket || socket.readyState !== WebSocket.OPEN || committed) return
      if (socket.bufferedAmount > MAX_BUFFERED_BYTES) {
        breakLive()
        return
      }
      socket.send(event.data)
    }
    context.createMediaStreamSource(stream).connect(node)
    await context.resume()
  }

  void (async () => {
    try {
      socket = await openTranscriptionStream()
    } catch {
      breakLive()
      return
    }
    if (ended) {
      close()
      return
    }
    readyTimer = window.setTimeout(breakLive, READY_TIMEOUT_MS)
    socket.onmessage = (event) => {
      const message = parse(event.data)
      if (!message) return
      if (message.type === 'ready') {
        if (readyTimer !== null) window.clearTimeout(readyTimer)
        readyTimer = null
        startAudio(message.sampleRate).catch(breakLive)
      } else if (message.type === 'delta') {
        if (!ended && !broken) onText(message.text)
      } else if (message.type === 'done') {
        const pending = settle
        settle = null
        ended = true
        close()
        pending?.resolve(message.text)
      } else {
        breakLive()
      }
    }
    socket.onerror = breakLive
    socket.onclose = () => {
      if (!ended) breakLive()
    }
  })()

  return {
    finish: () =>
      new Promise<string>((resolve, reject) => {
        if (broken || ended || !socket || !node) {
          ended = true
          close()
          reject(new Error('live transcription is not running'))
          return
        }
        settle = { resolve, reject }
        const timer = window.setTimeout(breakLive, DONE_TIMEOUT_MS)
        const original = settle
        settle = {
          resolve: (text) => {
            window.clearTimeout(timer)
            original.resolve(text)
          },
          reject: (err) => {
            window.clearTimeout(timer)
            original.reject(err)
          },
        }
        const commit = () => {
          flushed = null
          releaseAudio()
          committed = true
          if (socket?.readyState === WebSocket.OPEN) socket.send(JSON.stringify({ type: 'commit' }))
          else breakLive()
        }
        flushed = commit
        node.port.postMessage('flush')
        window.setTimeout(() => flushed?.(), 300)
      }),
    cancel: () => {
      ended = true
      settle = null
      close()
    },
  }
}
