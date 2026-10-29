import { describe, expect, it, vi } from 'vitest'
import { loadPdf, PDF_LOAD_OPTIONS } from './pdfjs'

const getDocument = vi.fn(() => ({ promise: Promise.resolve() }))

vi.mock('pdfjs-dist', () => ({ GlobalWorkerOptions: { workerSrc: '' }, getDocument }))
vi.mock('pdfjs-dist/build/pdf.worker.min.mjs?url', () => ({ default: '/assets/pdf.worker.mjs' }))

describe('loadPdf', () => {
  it('opens a PDF without XFA and WebAssembly, on a copy of the caller buffer', async () => {
    const data = new Uint8Array([1, 2, 3]).buffer

    await loadPdf(data)

    expect(PDF_LOAD_OPTIONS).toEqual({ enableXfa: false, useWasm: false })
    const [params] = getDocument.mock.calls[0] as unknown as [Record<string, unknown>]
    expect(params).toMatchObject({ enableXfa: false, useWasm: false })
    expect(Array.from(params.data as Uint8Array)).toEqual([1, 2, 3])
    expect((params.data as Uint8Array).buffer).not.toBe(data)
  })

  it('serves the worker from the app origin', async () => {
    const pdfjs = await import('pdfjs-dist')

    await loadPdf(new ArrayBuffer(1))

    expect(pdfjs.GlobalWorkerOptions.workerSrc).toBe('/assets/pdf.worker.mjs')
  })
})
