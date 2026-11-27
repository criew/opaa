import { describe, expect, it } from 'vitest'
import { storedZip } from '../mocks/storedZip'
import { declaredUnpackedSize } from './zipSize'

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
