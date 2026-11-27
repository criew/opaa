const END_SIGNATURE = 0x06054b50
const ENTRY_SIGNATURE = 0x02014b50
const END_LENGTH = 22
const ENTRY_LENGTH = 46
/** The end record may be followed by an archive comment of up to this length. */
const MAX_COMMENT = 0xffff
const ZIP64_MARK = 0xffffffff

/**
 * The sum of the unpacked sizes a ZIP archive (a Word file) declares in its central directory,
 * read without unpacking anything. `Infinity` where an entry hides its size in a ZIP64 field, so a
 * caller's bound rejects it; `null` where `bytes` are no readable archive.
 */
export function declaredUnpackedSize(bytes: Uint8Array): number | null {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const lowest = Math.max(0, bytes.length - END_LENGTH - MAX_COMMENT)
  let end = -1
  for (let at = bytes.length - END_LENGTH; at >= lowest; at--) {
    if (view.getUint32(at, true) === END_SIGNATURE) {
      end = at
      break
    }
  }
  if (end < 0) return null
  const entries = view.getUint16(end + 10, true)
  let at = view.getUint32(end + 16, true)
  if (at === ZIP64_MARK || entries === 0xffff) return Infinity
  let total = 0
  for (let index = 0; index < entries; index++) {
    if (at + ENTRY_LENGTH > end || view.getUint32(at, true) !== ENTRY_SIGNATURE) return null
    const unpacked = view.getUint32(at + 24, true)
    if (unpacked === ZIP64_MARK) return Infinity
    total += unpacked
    at +=
      ENTRY_LENGTH +
      view.getUint16(at + 28, true) +
      view.getUint16(at + 30, true) +
      view.getUint16(at + 32, true)
  }
  return total
}
