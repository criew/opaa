const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})

function crc32(bytes: Uint8Array): number {
  let crc = 0xffffffff
  for (const byte of bytes) crc = CRC_TABLE[(crc ^ byte) & 0xff] ^ (crc >>> 8)
  return (crc ^ 0xffffffff) >>> 0
}

/** An entry packed with deflate (method 8); `size` is the unpacked size the archive declares. */
export interface DeflatedEntry {
  deflated: Uint8Array
  size: number
}

/**
 * A ZIP archive for tests that need a real Word file or a forged one: text entries are stored
 * uncompressed, a {@link DeflatedEntry} as given. `declaredSize` overrides the unpacked size the
 * central directory names for every entry; `dataDescriptor` moves CRC and sizes of every entry
 * from its local header into a descriptor behind its data, as streaming writers do.
 */
export function storedZip(
  files: Record<string, string | DeflatedEntry>,
  {
    declaredSize,
    dataDescriptor = false,
  }: { declaredSize?: number; dataDescriptor?: boolean } = {},
): Uint8Array<ArrayBuffer> {
  const encoder = new TextEncoder()
  const locals: Uint8Array[] = []
  const centrals: Uint8Array[] = []
  let offset = 0
  for (const [name, content] of Object.entries(files)) {
    const nameBytes = encoder.encode(name)
    const deflated = typeof content === 'string' ? null : content
    const data = deflated ? deflated.deflated : encoder.encode(content as string)
    const crc = deflated ? 0 : crc32(data)
    const method = deflated ? 8 : 0
    const size = deflated ? deflated.size : data.length
    const flags = dataDescriptor ? 0x0008 : 0
    const local = new Uint8Array(30 + nameBytes.length + data.length + (dataDescriptor ? 16 : 0))
    const lv = new DataView(local.buffer)
    lv.setUint32(0, 0x04034b50, true)
    lv.setUint16(4, 20, true)
    lv.setUint16(6, flags, true)
    lv.setUint16(8, method, true)
    if (!dataDescriptor) {
      lv.setUint32(14, crc, true)
      lv.setUint32(18, data.length, true)
      lv.setUint32(22, size, true)
    }
    lv.setUint16(26, nameBytes.length, true)
    local.set(nameBytes, 30)
    local.set(data, 30 + nameBytes.length)
    if (dataDescriptor) {
      const at = 30 + nameBytes.length + data.length
      lv.setUint32(at, 0x08074b50, true)
      lv.setUint32(at + 4, crc, true)
      lv.setUint32(at + 8, data.length, true)
      lv.setUint32(at + 12, size, true)
    }
    const central = new Uint8Array(46 + nameBytes.length)
    const cv = new DataView(central.buffer)
    cv.setUint32(0, 0x02014b50, true)
    cv.setUint16(4, 20, true)
    cv.setUint16(6, 20, true)
    cv.setUint16(8, flags, true)
    cv.setUint16(10, method, true)
    cv.setUint32(16, crc, true)
    cv.setUint32(20, data.length, true)
    cv.setUint32(24, declaredSize ?? size, true)
    cv.setUint16(28, nameBytes.length, true)
    cv.setUint32(42, offset, true)
    central.set(nameBytes, 46)
    locals.push(local)
    centrals.push(central)
    offset += local.length
  }
  const centralSize = centrals.reduce((sum, part) => sum + part.length, 0)
  const end = new Uint8Array(22)
  const ev = new DataView(end.buffer)
  ev.setUint32(0, 0x06054b50, true)
  ev.setUint16(8, centrals.length, true)
  ev.setUint16(10, centrals.length, true)
  ev.setUint32(12, centralSize, true)
  ev.setUint32(16, offset, true)
  const out = new Uint8Array(offset + centralSize + end.length)
  let at = 0
  for (const part of [...locals, ...centrals, end]) {
    out.set(part, at)
    at += part.length
  }
  return out
}
