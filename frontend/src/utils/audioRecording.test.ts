import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { isRecordingSupported, pickRecordingMimeType, startRecording } from './audioRecording'

/** A MediaRecorder that hands out one chunk on stop, like a browser with a short recording. */
class FakeMediaRecorder {
  static supported = new Set(['audio/webm;codecs=opus', 'audio/webm'])
  static isTypeSupported(type: string) {
    return FakeMediaRecorder.supported.has(type)
  }
  state: 'inactive' | 'recording' = 'inactive'
  mimeType: string
  ondataavailable: ((event: { data: Blob }) => void) | null = null
  onstop: (() => void) | null = null
  onerror: (() => void) | null = null
  static last: FakeMediaRecorder | null = null
  constructor(_stream: MediaStream, options?: { mimeType?: string }) {
    this.mimeType = options?.mimeType ?? ''
    FakeMediaRecorder.last = this
  }
  start() {
    this.state = 'recording'
  }
  stop() {
    this.state = 'inactive'
    this.ondataavailable?.({ data: new Blob(['opus-data']) })
    this.onstop?.()
  }
}

function fakeStream() {
  const tracks = [{ stop: vi.fn() }]
  return { stream: { getTracks: () => tracks } as unknown as MediaStream, tracks }
}

describe('audioRecording', () => {
  let tracks: Array<{ stop: ReturnType<typeof vi.fn> }>
  let getUserMedia: ReturnType<typeof vi.fn>

  beforeEach(() => {
    const fake = fakeStream()
    tracks = fake.tracks
    getUserMedia = vi.fn(async () => fake.stream)
    vi.stubGlobal('MediaRecorder', FakeMediaRecorder)
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia },
    })
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined })
    FakeMediaRecorder.supported = new Set(['audio/webm;codecs=opus', 'audio/webm'])
  })

  it('knows when the browser can record', () => {
    expect(isRecordingSupported()).toBe(true)
  })

  it('prefers Opus in WebM and falls back to MP4', () => {
    expect(pickRecordingMimeType()).toBe('audio/webm;codecs=opus')
    expect(pickRecordingMimeType((type) => type === 'audio/mp4')).toBe('audio/mp4')
    expect(pickRecordingMimeType(() => false)).toBeUndefined()
  })

  it('asks for a mono microphone with the browser clean-up switched on', async () => {
    const recording = await startRecording()
    recording.cancel()

    expect(getUserMedia).toHaveBeenCalledWith({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        channelCount: 1,
      },
    })
  })

  it('hands over the clip in memory and ends the microphone on stop', async () => {
    const recording = await startRecording()

    const clip = await recording.stop()

    expect(clip.type).toBe('audio/webm;codecs=opus')
    expect(clip.size).toBeGreaterThan(0)
    expect(tracks[0].stop).toHaveBeenCalled()
  })

  it('ends the microphone on cancel', async () => {
    const recording = await startRecording()

    recording.cancel()

    expect(tracks[0].stop).toHaveBeenCalled()
  })

  it('ends the microphone and reports it when the recorder fails mid-recording', async () => {
    const onFailure = vi.fn()
    const recording = await startRecording({ onFailure })

    FakeMediaRecorder.last?.onerror?.()

    expect(tracks[0].stop).toHaveBeenCalled()
    expect(onFailure).toHaveBeenCalledTimes(1)
    await expect(recording.stop()).rejects.toThrow('recording already ended')
  })

  it('ends the microphone when the recorder cannot be created', async () => {
    vi.stubGlobal(
      'MediaRecorder',
      class extends FakeMediaRecorder {
        constructor(stream: MediaStream) {
          super(stream)
          throw new Error('not supported')
        }
      },
    )

    await expect(startRecording()).rejects.toThrow('not supported')
    expect(tracks[0].stop).toHaveBeenCalled()
  })
})

/** An AudioContext whose input level and run state the test sets. */
class FakeAudioContext {
  static last: FakeAudioContext | null = null
  state: 'suspended' | 'running' | 'closed' = 'suspended'
  level = 0
  resumeCalls = 0
  constructor() {
    FakeAudioContext.last = this
  }
  resume() {
    this.resumeCalls += 1
    return Promise.resolve()
  }
  close() {
    this.state = 'closed'
    return Promise.resolve()
  }
  createAnalyser() {
    return {
      fftSize: 0,
      getFloatTimeDomainData: (samples: Float32Array) => samples.fill(this.level),
    }
  }
  createMediaStreamSource() {
    return { connect: () => {} }
  }
}

describe('audioRecording silence stop', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.stubGlobal('MediaRecorder', FakeMediaRecorder)
    vi.stubGlobal('AudioContext', FakeAudioContext)
    Object.defineProperty(navigator, 'mediaDevices', {
      configurable: true,
      value: { getUserMedia: vi.fn(async () => fakeStream().stream) },
    })
  })

  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
    Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: undefined })
  })

  it('stops after the silence that follows speech while the context runs', async () => {
    const onSilence = vi.fn()
    const recording = await startRecording({ onSilence, silenceMs: 1_000 })
    const context = FakeAudioContext.last!
    expect(context.resumeCalls).toBe(1)
    context.state = 'running'
    context.level = 0.5

    vi.advanceTimersByTime(2_000)
    expect(onSilence).not.toHaveBeenCalled()

    context.level = 0
    vi.advanceTimersByTime(1_250)
    expect(onSilence).toHaveBeenCalledTimes(1)
    recording.cancel()
  })

  it('counts no silence while the context is suspended', async () => {
    const onSilence = vi.fn()
    const recording = await startRecording({ onSilence, silenceMs: 1_000 })

    vi.advanceTimersByTime(5_000)

    expect(onSilence).not.toHaveBeenCalled()
    recording.cancel()
  })
})
