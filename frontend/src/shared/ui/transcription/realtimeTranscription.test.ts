import { describe, expect, it } from 'vitest'
import { batchPrefillFor, batchUntouched, liveKeyMissing } from './realtimeTranscription'

/** The dictation endpoint that goes with a live service, and when a live service lacks its key. */
describe('realtimeTranscription', () => {
  it('fills OpenAI, derives vLLM from the live address and leaves Mistral empty', () => {
    expect(batchPrefillFor('OPENAI', 'wss://api.openai.com/v1/realtime', 'x')).toEqual({
      baseUrl: 'https://api.openai.com/v1',
      model: 'gpt-4o-mini-transcribe',
    })
    expect(batchPrefillFor('VLLM', 'ws://gpu-01:8000/v1/realtime', ' voxtral ')).toEqual({
      baseUrl: 'http://gpu-01:8000/v1',
      model: 'voxtral',
    })
    expect(batchPrefillFor('VLLM', 'wss://gpu.example/v1/realtime', 'voxtral')?.baseUrl).toBe(
      'https://gpu.example/v1',
    )
    expect(batchPrefillFor('VLLM', 'kein Ort', 'voxtral')).toBeNull()
    expect(batchPrefillFor('MISTRAL', 'wss://api.mistral.ai/v1/x', 'voxtral')).toBeNull()
  })

  it('fills only an empty endpoint or the one it filled before', () => {
    const fill = { baseUrl: 'https://api.openai.com/v1', model: 'gpt-4o-mini-transcribe' }
    expect(batchUntouched('', '', null)).toBe(true)
    expect(batchUntouched(fill.baseUrl, fill.model, fill)).toBe(true)
    expect(batchUntouched(fill.baseUrl, 'whisper-1', fill)).toBe(false)
    expect(batchUntouched('http://speaches:8000/v1', '', null)).toBe(false)
  })

  it('asks OpenAI and Mistral for a key the batch key cannot give on another host', () => {
    const base = { liveUrl: 'wss://api.openai.com/v1/realtime', ownKey: false }
    expect(
      liveKeyMissing({
        ...base,
        protocol: 'OPENAI',
        batchUrl: 'http://speaches:8000/v1',
        batchKey: true,
      }),
    ).toBe(true)
    expect(
      liveKeyMissing({
        ...base,
        protocol: 'OPENAI',
        batchUrl: 'https://api.openai.com/v1',
        batchKey: true,
      }),
    ).toBe(false)
    expect(
      liveKeyMissing({
        ...base,
        protocol: 'VLLM',
        batchUrl: 'http://speaches:8000/v1',
        batchKey: false,
      }),
    ).toBe(false)
    expect(
      liveKeyMissing({ ...base, ownKey: true, protocol: 'MISTRAL', batchUrl: '', batchKey: false }),
    ).toBe(false)
  })
})
