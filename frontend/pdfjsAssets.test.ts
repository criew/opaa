import { pathToFileURL } from 'node:url'
import { describe, expect, it } from 'vitest'
import { pdfjsFiles } from './pdfjsAssets'

describe('pdfjsFiles', () => {
  // Chromium before 145 lacks both; the worker throws on its first font without them.
  it('serves a worker that brings its own Map.getOrInsertComputed and Math.sumPrecise', async () => {
    delete (Map.prototype as { getOrInsertComputed?: unknown }).getOrInsertComputed
    delete (Math as { sumPrecise?: unknown }).sumPrecise

    await import(/* @vite-ignore */ pathToFileURL(pdfjsFiles()['pdf.worker.min.js']).href)

    expect(typeof (Map.prototype as { getOrInsertComputed?: unknown }).getOrInsertComputed).toBe(
      'function',
    )
    expect(typeof (Math as { sumPrecise?: unknown }).sumPrecise).toBe('function')
  })
})
