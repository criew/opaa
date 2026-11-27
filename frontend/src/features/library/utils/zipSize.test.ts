import { deflateRawSync } from 'node:zlib'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { storedZip } from '../mocks/storedZip'
import { declaredUnpackedSize, unpacksBeyond } from './zipSize'

const MiB = 1024 * 1024

afterEach(() => {
  vi.unstubAllGlobals()
})

/** `length` zero bytes packed with deflate: a few kilobytes that unpack to megabytes. */
function zeros(length: number): Uint8Array {
  return new Uint8Array(deflateRawSync(new Uint8Array(length)))
}

describe('declaredUnpackedSize', () => {
  it('sums the unpacked sizes the central directory names', () => {
    expect(declaredUnpackedSize(storedZip({ 'a.xml': 'abc', 'b/c.xml': 'defgh' }))).toBe(8)
  })

  it('reads the claim of a forged archive without unpacking it', () => {
    const bomb = storedZip({ 'a.xml': 'x', 'b.xml': 'y' }, { declaredSize: 40 * 1024 * 1024 })
    expect(declaredUnpackedSize(bomb)).toBe(80 * 1024 * 1024)
  })

  it('finds the end record behind an archive comment', () => {
    const zip = storedZip({ 'a.xml': 'abc' })
    const comment = new TextEncoder().encode('Kommentar')
    const withComment = new Uint8Array(zip.length + comment.length)
    withComment.set(zip)
    withComment.set(comment, zip.length)
    new DataView(withComment.buffer).setUint16(zip.length - 2, comment.length, true)
    expect(declaredUnpackedSize(withComment)).toBe(3)
  })

  it('counts a ZIP64 size as unbounded and is null for anything but an archive', () => {
    expect(declaredUnpackedSize(storedZip({ 'a.xml': 'x' }, { declaredSize: 0xffffffff }))).toBe(
      Infinity,
    )
    expect(declaredUnpackedSize(new TextEncoder().encode('PK'))).toBeNull()
    expect(declaredUnpackedSize(new Uint8Array())).toBeNull()
  })
})

describe('unpacksBeyond', () => {
  it('counts what the entries really unpack to, not what they declare', async () => {
    // declares 100 bytes per entry, unpacks to 3 MiB each
    const bomb = storedZip({
      'word/document.xml': { deflated: zeros(3 * MiB), size: 100 },
      'word/styles.xml': { deflated: zeros(3 * MiB), size: 100 },
    })
    expect(bomb.length).toBeLessThan(20_000)
    expect(declaredUnpackedSize(bomb)).toBe(200)

    expect(await unpacksBeyond(bomb, 5 * MiB)).toBe(true)
    expect(await unpacksBeyond(bomb, 7 * MiB)).toBe(false)
  })

  it('stops inflating once the bound is passed', async () => {
    const bomb = storedZip({ 'a.xml': { deflated: zeros(64 * MiB), size: 1 } })
    const started = performance.now()
    expect(await unpacksBeyond(bomb, MiB)).toBe(true)
    expect(performance.now() - started).toBeLessThan(1_000)
  })

  it('counts stored entries and declared sizes too', async () => {
    expect(await unpacksBeyond(storedZip({ 'a.xml': 'abcdef' }), 5)).toBe(true)
    expect(await unpacksBeyond(storedZip({ 'a.xml': 'abc' }), 5)).toBe(false)
    expect(await unpacksBeyond(storedZip({ 'a.xml': 'x' }, { declaredSize: 6 }), 5)).toBe(true)
    expect(await unpacksBeyond(storedZip({ 'a.xml': 'x' }, { declaredSize: 0xffffffff }), 5)).toBe(
      true,
    )
  })

  it('counts what it cannot read as too large', async () => {
    expect(await unpacksBeyond(new TextEncoder().encode('PK'), 5)).toBe(true)
    expect(await unpacksBeyond(new Uint8Array(), 5)).toBe(true)
  })

  it('reads an archive behind leading bytes as the reader does, bombs included', async () => {
    const bomb = storedZip({ 'a.xml': { deflated: zeros(3 * MiB), size: 100 } })
    expect(await unpacksBeyond(prefixed(bomb), MiB)).toBe(true)
    const small = prefixed(storedZip({ 'a.xml': 'abc' }))
    expect(declaredUnpackedSize(small)).toBe(3)
    expect(await unpacksBeyond(small, 5)).toBe(false)
  })

  it('counts entries that share packed data, reach beyond the file or use another packing as too large', async () => {
    const shared = storedZip({ 'a.xml': 'abc', 'b.xml': 'def' })
    centralEntry(shared, 1).setUint32(42, 0, true)
    expect(await unpacksBeyond(shared, 1000)).toBe(true)

    const beyond = storedZip({ 'a.xml': 'abc' })
    centralEntry(beyond, 0).setUint32(20, 1_000_000, true)
    expect(await unpacksBeyond(beyond, 1000)).toBe(true)

    const unknown = storedZip({ 'a.xml': 'abc' })
    centralEntry(unknown, 0).setUint16(10, 99, true)
    expect(await unpacksBeyond(unknown, 1000)).toBe(true)
  })

  it('counts a deflated entry as too large where the browser cannot inflate', async () => {
    vi.stubGlobal('DecompressionStream', undefined)
    const zip = storedZip({ 'a.xml': { deflated: zeros(10), size: 10 } })
    expect(await unpacksBeyond(zip, 1000)).toBe(true)
  })

  it('leaves a broken deflate stream to the reader and stops when aborted', async () => {
    const broken = storedZip({ 'a.xml': { deflated: new Uint8Array([0xff, 0xff, 0xff]), size: 1 } })
    expect(await unpacksBeyond(broken, 5)).toBe(false)
    const controller = new AbortController()
    controller.abort(new Error('geschlossen'))
    const zip = storedZip({ 'a.xml': { deflated: zeros(10), size: 10 } })
    await expect(unpacksBeyond(zip, 1000, controller.signal)).rejects.toThrow('geschlossen')
  })
})

/** `zip` behind a few bytes of something else, as a self-extracting archive starts. */
function prefixed(zip: Uint8Array): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(zip.length + 7)
  out.set(new TextEncoder().encode('MZjunk!'))
  out.set(zip, 7)
  return out
}

/** The central directory record of entry `index`, to forge it. */
function centralEntry(zip: Uint8Array, index: number): DataView {
  const view = new DataView(zip.buffer, zip.byteOffset, zip.byteLength)
  let at = view.getUint32(zip.length - 22 + 16, true)
  for (let i = 0; i < index; i++) {
    at +=
      46 +
      view.getUint16(at + 28, true) +
      view.getUint16(at + 30, true) +
      view.getUint16(at + 32, true)
  }
  return new DataView(zip.buffer, zip.byteOffset + at, 46)
}
