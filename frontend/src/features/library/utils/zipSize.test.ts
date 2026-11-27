import { deflateRawSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { storedZip } from '../mocks/storedZip'
import { unpacksBeyond } from './zipSize'

const MiB = 1024 * 1024

/** `length` zero bytes packed with deflate: a few kilobytes that unpack to megabytes. */
function zeros(length: number, size = length) {
  return { deflated: new Uint8Array(deflateRawSync(new Uint8Array(length))), size }
}

/** The end record of `zip`, to forge it. */
function endRecord(zip: Uint8Array): DataView {
  return new DataView(zip.buffer, zip.byteOffset + zip.length - 22, 22)
}

/** `zip` behind a few bytes of something else, as a self-extracting archive starts. */
function prefixed(zip: Uint8Array): Uint8Array<ArrayBuffer> {
  const out = new Uint8Array(zip.length + 7)
  out.set(new TextEncoder().encode('MZjunk!'))
  out.set(zip, 7)
  return out
}

describe('unpacksBeyond', () => {
  it('counts what the entries really unpack to', async () => {
    const zip = storedZip({
      'word/document.xml': zeros(3 * MiB),
      'word/styles.xml': zeros(3 * MiB),
    })
    expect(zip.length).toBeLessThan(20_000)
    expect(await unpacksBeyond(zip, 5 * MiB)).toBe(true)
    expect(await unpacksBeyond(zip, 7 * MiB)).toBe(false)
  })

  it('catches entries that declare less than they unpack to', async () => {
    const lying = storedZip({ 'word/document.xml': zeros(3 * MiB, 100) })
    expect(await unpacksBeyond(lying, 5 * MiB)).toBe(true)
  })

  it('stops unpacking once the bound is passed', async () => {
    const bomb = storedZip({ 'a.xml': zeros(64 * MiB) })
    const started = performance.now()
    expect(await unpacksBeyond(bomb, MiB)).toBe(true)
    expect(performance.now() - started).toBeLessThan(1_000)
  })

  it('counts every entry the reader finds, whatever count the end record names', async () => {
    const zip = storedZip({ 'a.xml': 'abc', 'word/document.xml': zeros(3 * MiB) })
    endRecord(zip).setUint16(8, 1, true)
    endRecord(zip).setUint16(10, 1, true)
    expect(await unpacksBeyond(zip, MiB)).toBe(true)
  })

  it.each([4, 6, 8])(
    'counts an archive whose end record switches the reader to ZIP64 (offset %i)',
    async (field) => {
      const zip = storedZip({ 'a.xml': zeros(3 * MiB) })
      endRecord(zip).setUint16(field, 0xffff, true)
      expect(await unpacksBeyond(zip, MiB)).toBe(true)
    },
  )

  it('reads an archive behind leading bytes as the reader does, bombs included', async () => {
    expect(await unpacksBeyond(prefixed(storedZip({ 'a.xml': zeros(3 * MiB) })), MiB)).toBe(true)
    expect(await unpacksBeyond(prefixed(storedZip({ 'a.xml': 'abc' })), 5)).toBe(false)
  })

  it.each([
    ['stored entries', () => storedZip({ 'a.xml': 'abc', 'b.xml': 'de' })],
    ['empty files and folders', () => storedZip({ 'word/': '', 'word/leer.xml': '' })],
    [
      'entries with a data descriptor',
      () => storedZip({ 'a.xml': 'abc', 'b.xml': zeros(2) }, { dataDescriptor: true }),
    ],
  ])('lets %s within the bound through', async (_case, zip) => {
    expect(await unpacksBeyond(zip(), 5)).toBe(false)
  })

  it('counts what the reader cannot load or unpack as too large', async () => {
    expect(await unpacksBeyond(new TextEncoder().encode('PK'), 5)).toBe(true)
    expect(await unpacksBeyond(new Uint8Array(), 5)).toBe(true)
    const broken = storedZip({ 'a.xml': { deflated: new Uint8Array([0xff, 0xff, 0xff]), size: 1 } })
    expect(await unpacksBeyond(broken, 5)).toBe(true)
  })

  it('refuses an archive with more entries than a Word file has', async () => {
    const files = Object.fromEntries(Array.from({ length: 10_001 }, (_, i) => [`f${i}.xml`, '']))
    expect(await unpacksBeyond(storedZip(files), 5)).toBe(true)
  })

  it('stops when aborted', async () => {
    const controller = new AbortController()
    controller.abort(new Error('geschlossen'))
    const zip = storedZip({ 'a.xml': zeros(10) })
    await expect(unpacksBeyond(zip, 1000, controller.signal)).rejects.toThrow('geschlossen')
  })
})
