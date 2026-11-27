import { deflateRawSync } from 'node:zlib'
import { describe, expect, it } from 'vitest'
import { storedZip } from '../mocks/storedZip'
import { declaredUnpackedSize, unpacksBeyond } from './zipSize'

const MiB = 1024 * 1024

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

  it('leaves anything but an archive and a broken entry to the reader', async () => {
    expect(await unpacksBeyond(new TextEncoder().encode('PK'), 5)).toBe(false)
    const broken = storedZip({ 'a.xml': { deflated: new Uint8Array([0xff, 0xff, 0xff]), size: 1 } })
    expect(await unpacksBeyond(broken, 5)).toBe(false)
  })
})
