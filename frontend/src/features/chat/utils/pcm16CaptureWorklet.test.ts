import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

interface Processor {
  process(inputs: Float32Array[][]): boolean
  port: { onmessage: ((event: { data: unknown }) => void) | null }
}

type ProcessorClass = new (options: { processorOptions: { targetRate: number } }) => Processor

/** Loads the worklet as the browser would, with its globals stubbed; collects what it posts. */
function loadWorklet(inputRate: number, targetRate: number) {
  const posted: Int16Array[] = []
  let registered: ProcessorClass | undefined
  class FakeProcessor {
    port = {
      onmessage: null,
      postMessage: (data: unknown) => {
        if (data instanceof ArrayBuffer) posted.push(new Int16Array(data))
      },
    }
  }
  const source = readFileSync(
    resolve(__dirname, '../../../../public/pcm16-capture-worklet.js'),
    'utf8',
  )
  new Function('AudioWorkletProcessor', 'sampleRate', 'registerProcessor', source)(
    FakeProcessor,
    inputRate,
    (_name: string, processor: ProcessorClass) => {
      registered = processor
    },
  )
  const processor = new registered!({ processorOptions: { targetRate } })
  return { processor, posted }
}

/** The peak amplitude (0..1) of a sine of `frequency` after capture, past the filter's settling. */
function peakAfterCapture(frequency: number, inputRate: number, targetRate: number): number {
  const { processor, posted } = loadWorklet(inputRate, targetRate)
  let t = 0
  for (let block = 0; block < 400; block++) {
    const samples = new Float32Array(128)
    for (let i = 0; i < samples.length; i++, t++) {
      samples[i] = 0.5 * Math.sin((2 * Math.PI * frequency * t) / inputRate)
    }
    processor.process([[samples]])
  }
  const all = posted.flatMap((frame) => Array.from(frame)).slice(100)
  return Math.max(...all.map(Math.abs)) / 0x8000
}

describe('pcm16-capture worklet', () => {
  it('passes speech frequencies almost unchanged', () => {
    expect(peakAfterCapture(1_000, 48_000, 24_000)).toBeGreaterThan(0.45)
    expect(peakAfterCapture(1_000, 48_000, 16_000)).toBeGreaterThan(0.45)
  })

  it('damps what lies above the target rate before it folds back', () => {
    expect(peakAfterCapture(20_000, 48_000, 24_000)).toBeLessThan(0.2)
    expect(peakAfterCapture(15_000, 48_000, 16_000)).toBeLessThan(0.1)
  })

  it('posts PCM16 at the target rate', () => {
    const { processor, posted } = loadWorklet(48_000, 16_000)
    for (let block = 0; block < 375; block++) processor.process([[new Float32Array(128)]])
    const samples = posted.reduce((sum, frame) => sum + frame.length, 0)
    expect(samples).toBeGreaterThanOrEqual(15_900)
    expect(samples).toBeLessThanOrEqual(16_000)
  })
})
