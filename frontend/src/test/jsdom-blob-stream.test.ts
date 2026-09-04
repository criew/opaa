import { describe, expect, it } from 'vitest'

/**
 * Guards the jsdom gap described in `jsdom-blob-stream.ts`: without `Blob.prototype.stream()`,
 * undici's multipart serialisation produces a body that never yields bytes, and a `FormData`
 * upload carrying a `File` hangs rather than failing (#1169). Both assertions fail cross-platform
 * if the polyfill is removed, unlike the upload tests, which only hang on some Node versions.
 */
describe('jsdom Blob.stream()', () => {
  it('yields the blob bytes', async () => {
    const blob = new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], { type: 'image/png' })

    const chunks: Uint8Array[] = []
    for await (const chunk of blob.stream()) {
      chunks.push(chunk as Uint8Array)
    }

    expect(Array.from(chunks.flatMap((chunk) => Array.from(chunk)))).toEqual([
      0x89, 0x50, 0x4e, 0x47,
    ])
  })

  it('lets a FormData request body carrying a File be read to completion', async () => {
    const formData = new FormData()
    formData.append('file', new File([new Uint8Array([0x89, 0x50])], 'logo.png'))

    const body = await new Request('http://localhost/upload', {
      method: 'PUT',
      body: formData,
    }).arrayBuffer()

    expect(body.byteLength).toBeGreaterThan(0)
  })
})
