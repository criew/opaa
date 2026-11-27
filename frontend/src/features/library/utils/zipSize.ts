const END_SIGNATURE = 0x06054b50
const ENTRY_SIGNATURE = 0x02014b50
const LOCAL_SIGNATURE = 0x04034b50
const END_LENGTH = 22
const ENTRY_LENGTH = 46
const LOCAL_LENGTH = 30
/** The end record may be followed by an archive comment of up to this length. */
const MAX_COMMENT = 0xffff
const ZIP64_MARK = 0xffffffff
const STORED = 0
const DEFLATED = 8

interface Entry {
  method: number
  packed: number
  unpacked: number
  localOffset: number
}

/**
 * The entries of a ZIP archive's central directory; `'zip64'` where an entry hides its sizes in
 * a ZIP64 field, `null` where `bytes` are no readable archive.
 */
function centralDirectory(bytes: Uint8Array): Entry[] | 'zip64' | null {
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
  const count = view.getUint16(end + 10, true)
  let at = view.getUint32(end + 16, true)
  if (at === ZIP64_MARK || count === 0xffff) return 'zip64'
  const entries: Entry[] = []
  for (let index = 0; index < count; index++) {
    if (at + ENTRY_LENGTH > end || view.getUint32(at, true) !== ENTRY_SIGNATURE) return null
    const entry = {
      method: view.getUint16(at + 10, true),
      packed: view.getUint32(at + 20, true),
      unpacked: view.getUint32(at + 24, true),
      localOffset: view.getUint32(at + 42, true),
    }
    if (entry.packed === ZIP64_MARK || entry.unpacked === ZIP64_MARK) return 'zip64'
    entries.push(entry)
    at +=
      ENTRY_LENGTH +
      view.getUint16(at + 28, true) +
      view.getUint16(at + 30, true) +
      view.getUint16(at + 32, true)
  }
  return entries
}

/**
 * The sum of the unpacked sizes a ZIP archive (a Word file) declares in its central directory,
 * read without unpacking anything. `Infinity` where an entry hides its size in a ZIP64 field, so a
 * caller's bound rejects it; `null` where `bytes` are no readable archive.
 */
export function declaredUnpackedSize(bytes: Uint8Array): number | null {
  const entries = centralDirectory(bytes)
  if (entries === 'zip64') return Infinity
  return entries && entries.reduce((sum, entry) => sum + entry.unpacked, 0)
}

/**
 * Whether the entries of a ZIP archive unpack to more than `limit` bytes together, whatever sizes
 * they declare: deflated entries are inflated as a stream and counted, and the count stops as
 * soon as it passes `limit`, so a bomb never unpacks in full. A ZIP64 archive counts as too large;
 * no archive, or an entry that cannot be inflated, is left for the reader to reject. Without
 * `DecompressionStream` only the declared sizes count.
 */
export async function unpacksBeyond(
  bytes: Uint8Array<ArrayBuffer>,
  limit: number,
): Promise<boolean> {
  const entries = centralDirectory(bytes)
  if (entries === 'zip64') return true
  if (!entries) return false
  if (entries.reduce((sum, entry) => sum + entry.unpacked, 0) > limit) return true
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let total = 0
  for (const entry of entries) {
    const local = entry.localOffset
    if (local + LOCAL_LENGTH > bytes.length || view.getUint32(local, true) !== LOCAL_SIGNATURE) {
      continue
    }
    const start =
      local + LOCAL_LENGTH + view.getUint16(local + 26, true) + view.getUint16(local + 28, true)
    const data = bytes.subarray(start, Math.min(start + entry.packed, bytes.length))
    if (entry.method === STORED) total += data.length
    else if (entry.method === DEFLATED) total += await inflatedLength(data, limit - total)
    if (total > limit) return true
  }
  return false
}

/** Inflates `data` and counts its bytes, stopping once the count passes `budget`. */
async function inflatedLength(data: Uint8Array<ArrayBuffer>, budget: number): Promise<number> {
  if (typeof DecompressionStream === 'undefined') return 0
  const reader = new ReadableStream<BufferSource>({
    start(controller) {
      controller.enqueue(data)
      controller.close()
    },
  })
    .pipeThrough(new DecompressionStream('deflate-raw'))
    .getReader()
  let length = 0
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) return length
      length += value.length
      if (length > budget) {
        await reader.cancel()
        return length
      }
    }
  } catch {
    return length
  }
}
