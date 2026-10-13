import { act, renderHook } from '@testing-library/react'
import { AxiosError } from 'axios'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { MAX_DICTATION_MS, useDictation } from './useDictation'
import type { DictationTransport } from './useDictation'
import type { Recording, RecordingOptions } from '../utils/audioRecording'

interface FakeRecording extends Recording {
  stopped: boolean
  cancelled: boolean
  options: RecordingOptions
}

function fakeMicrophone() {
  const recordings: FakeRecording[] = []
  const startRecording = vi.fn(async (options: RecordingOptions) => {
    const recording: FakeRecording = {
      stopped: false,
      cancelled: false,
      options,
      stop: async () => {
        recording.stopped = true
        return new Blob(['audio'], { type: 'audio/webm' })
      },
      cancel: () => {
        recording.cancelled = true
      },
    }
    recordings.push(recording)
    return recording
  })
  return { startRecording, recordings }
}

function transportAnswering(...answers: Array<string | Error>): DictationTransport & {
  calls: Blob[]
} {
  const calls: Blob[] = []
  return {
    mode: 'batch',
    calls,
    transcribe: vi.fn(async (recording: Blob) => {
      calls.push(recording)
      const answer = answers[Math.min(calls.length - 1, answers.length - 1)]
      if (answer instanceof Error) throw answer
      return answer
    }),
  }
}

describe('useDictation', () => {
  beforeEach(() => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('records, sends the recording once it is stopped and hands over the text', async () => {
    const microphone = fakeMicrophone()
    const transport = transportAnswering('Hallo Welt')
    const onFinalText = vi.fn()
    const { result } = renderHook(() =>
      useDictation({ onFinalText, transport, startRecording: microphone.startRecording }),
    )

    expect(result.current.mode).toBe('batch')
    await act(() => result.current.start())
    expect(result.current.status).toBe('recording')
    expect(transport.calls).toHaveLength(0)

    await act(() => result.current.stop())

    expect(microphone.recordings[0].stopped).toBe(true)
    expect(transport.calls).toHaveLength(1)
    expect(onFinalText).toHaveBeenCalledWith('Hallo Welt')
    expect(result.current.finalText).toBe('Hallo Welt')
    expect(result.current.interimText).toBe('')
    expect(result.current.status).toBe('idle')
  })

  it('discards a cancelled recording without sending anything', async () => {
    const microphone = fakeMicrophone()
    const transport = transportAnswering('nie')
    const onFinalText = vi.fn()
    const { result } = renderHook(() =>
      useDictation({ onFinalText, transport, startRecording: microphone.startRecording }),
    )

    await act(() => result.current.start())
    act(() => result.current.cancel())

    expect(microphone.recordings[0].cancelled).toBe(true)
    expect(microphone.recordings[0].stopped).toBe(false)
    expect(transport.calls).toHaveLength(0)
    expect(onFinalText).not.toHaveBeenCalled()
    expect(result.current.status).toBe('idle')
  })

  it('releases the microphone when the input goes away mid-recording', async () => {
    const microphone = fakeMicrophone()
    const { result, unmount } = renderHook(() =>
      useDictation({
        onFinalText: vi.fn(),
        transport: transportAnswering('x'),
        startRecording: microphone.startRecording,
      }),
    )

    await act(() => result.current.start())
    unmount()

    expect(microphone.recordings[0].cancelled).toBe(true)
  })

  it('ends a recording on its own after two minutes', async () => {
    const microphone = fakeMicrophone()
    const transport = transportAnswering('lang')
    const onFinalText = vi.fn()
    const { result } = renderHook(() =>
      useDictation({ onFinalText, transport, startRecording: microphone.startRecording }),
    )

    await act(() => result.current.start())
    await act(async () => {
      await vi.advanceTimersByTimeAsync(MAX_DICTATION_MS)
    })

    expect(microphone.recordings[0].stopped).toBe(true)
    expect(onFinalText).toHaveBeenCalledWith('lang')
  })

  it('ends a recording after the silence the recorder reports', async () => {
    const microphone = fakeMicrophone()
    const transport = transportAnswering('still')
    const onFinalText = vi.fn()
    const { result } = renderHook(() =>
      useDictation({ onFinalText, transport, startRecording: microphone.startRecording }),
    )

    await act(() => result.current.start())
    expect(microphone.recordings[0].options.silenceMs).toBe(15_000)
    await act(async () => {
      microphone.recordings[0].options.onSilence?.()
      await vi.advanceTimersByTimeAsync(0)
    })

    expect(onFinalText).toHaveBeenCalledWith('still')
  })

  it('names a refused microphone in German and stays usable', async () => {
    const onFinalText = vi.fn()
    const startRecording = vi.fn(async () => {
      throw new DOMException('denied', 'NotAllowedError')
    })
    const { result } = renderHook(() =>
      useDictation({ onFinalText, transport: transportAnswering('x'), startRecording }),
    )

    await act(() => result.current.start())

    expect(result.current.error).toMatch(/Zugriff auf das Mikrofon wurde nicht erlaubt/)
    expect(result.current.status).toBe('idle')
  })

  it('reports a failed transcription and hands over no text', async () => {
    const microphone = fakeMicrophone()
    const onFinalText = vi.fn()
    const { result } = renderHook(() =>
      useDictation({
        onFinalText,
        transport: transportAnswering(new Error('Die Texterkennung ist fehlgeschlagen.')),
        startRecording: microphone.startRecording,
      }),
    )

    await act(() => result.current.start())
    await act(() => result.current.stop())

    expect(result.current.error).toBe('Die Texterkennung ist fehlgeschlagen.')
    expect(onFinalText).not.toHaveBeenCalled()
  })

  it('says so when nothing was recognised', async () => {
    const microphone = fakeMicrophone()
    const onFinalText = vi.fn()
    const { result } = renderHook(() =>
      useDictation({
        onFinalText,
        transport: transportAnswering('   '),
        startRecording: microphone.startRecording,
      }),
    )

    await act(() => result.current.start())
    await act(() => result.current.stop())

    expect(onFinalText).not.toHaveBeenCalled()
    expect(result.current.error).toMatch(/kein Text erkannt/)
  })
})

describe('batchTransport', () => {
  afterEach(() => {
    vi.doUnmock('../services/transcriptionApi')
    vi.resetModules()
  })

  async function transportWith(transcribeRecording: (recording: Blob) => Promise<string>) {
    vi.resetModules()
    vi.doMock('../services/transcriptionApi', () => ({ transcribeRecording }))
    return (await import('./useDictation')).batchTransport
  }

  const networkError = () =>
    new Error('Network Error', { cause: new AxiosError('Network Error', 'ERR_NETWORK') })

  it('retries a network failure exactly once', async () => {
    const transcribe = vi.fn().mockRejectedValueOnce(networkError()).mockResolvedValueOnce('ok')
    const transport = await transportWith(transcribe)

    await expect(transport.transcribe(new Blob(['a']))).resolves.toBe('ok')
    expect(transcribe).toHaveBeenCalledTimes(2)
  })

  it('gives up after the one retry with a German message', async () => {
    const transcribe = vi.fn().mockRejectedValue(networkError())
    const transport = await transportWith(transcribe)

    await expect(transport.transcribe(new Blob(['a']))).rejects.toThrow(
      /Aufnahme konnte nicht übertragen werden/,
    )
    expect(transcribe).toHaveBeenCalledTimes(2)
  })

  it('does not retry a refusal from the server', async () => {
    const transcribe = vi
      .fn()
      .mockRejectedValue(new Error('Die Spracheingabe ist derzeit nicht verfügbar.'))
    const transport = await transportWith(transcribe)

    await expect(transport.transcribe(new Blob(['a']))).rejects.toThrow(/nicht verfügbar/)
    expect(transcribe).toHaveBeenCalledTimes(1)
  })
})
