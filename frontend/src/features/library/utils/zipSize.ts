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

/** More entries than a Word file has; an archive with more is refused unread. */
const MAX_ENTRIES = 10_000

/**
 * The entries of a ZIP archive's central directory; `'zip64'` where an entry hides its sizes in
 * a ZIP64 field, `null` where `bytes` are no readable archive. Bytes before the archive shift all
 * offsets, as the reader behind docx-preview (jszip) allows, so they are read shifted here too.
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
  const size = view.getUint32(end + 12, true)
  const offset = view.getUint32(end + 16, true)
  if (offset === ZIP64_MARK || size === ZIP64_MARK || count === 0xffff) return 'zip64'
  const shift = end - (offset + size)
  if (shift < 0) return null
  const entries: Entry[] = []
  let at = offset + shift
  for (let index = 0; index < count; index++) {
    if (at + ENTRY_LENGTH > end || view.getUint32(at, true) !== ENTRY_SIGNATURE) return null
    const entry = {
      method: view.getUint16(at + 10, true),
      packed: view.getUint32(at + 20, true),
      unpacked: view.getUint32(at + 24, true),
      localOffset: view.getUint32(at + 42, true) + shift,
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
 * soon as it passes `limit`, so a bomb never unpacks in full. Whatever cannot be counted counts as
 * too large: no readable archive, ZIP64, more than {@link MAX_ENTRIES} entries, entries whose
 * packed data overlap or lie outside the file, an unknown packing, a missing `DecompressionStream`.
 * `signal` stops the count with its abort reason.
 */
export async function unpacksBeyond(
  bytes: Uint8Array<ArrayBuffer>,
  limit: number,
  signal?: AbortSignal,
): Promise<boolean> {
  const entries = centralDirectory(bytes)
  if (!entries || entries === 'zip64' || entries.length > MAX_ENTRIES) return true
  if (entries.reduce((sum, entry) => sum + entry.unpacked, 0) > limit) return true
  const parts = packedParts(bytes, entries)
  if (!parts) return true
  let total = 0
  for (const { entry, data } of parts) {
    signal?.throwIfAborted()
    if (entry.method === STORED) total += data.length
    else if (entry.method === DEFLATED) total += await inflatedLength(data, limit - total, signal)
    else return true
    if (total > limit) return true
  }
  return false
}

/**
 * The packed data of every entry, or `null` where a local header is missing, data reach beyond
 * the file or two entries share bytes - so no data is inflated twice.
 */
function packedParts(bytes: Uint8Array<ArrayBuffer>, entries: Entry[]) {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  const parts: { entry: Entry; from: number; data: Uint8Array<ArrayBuffer> }[] = []
  for (const entry of entries) {
    const local = entry.localOffset
    if (local + LOCAL_LENGTH > bytes.length || view.getUint32(local, true) !== LOCAL_SIGNATURE) {
      return null
    }
    const start =
      local + LOCAL_LENGTH + view.getUint16(local + 26, true) + view.getUint16(local + 28, true)
    if (start + entry.packed > bytes.length) return null
    parts.push({ entry, from: local, data: bytes.subarray(start, start + entry.packed) })
  }
  const ordered = [...parts].sort((a, b) => a.from - b.from)
  for (let index = 1; index < ordered.length; index++) {
    const before = ordered[index - 1]
    if (before.data.byteOffset - bytes.byteOffset + before.data.length > ordered[index].from) {
      return null
    }
  }
  return parts
}

/** Inflates `data` and counts its bytes, stopping once the count passes `budget`. */
async function inflatedLength(
  data: Uint8Array<ArrayBuffer>,
  budget: number,
  signal?: AbortSignal,
): Promise<number> {
  if (typeof DecompressionStream === 'undefined') return Infinity
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
      if (signal?.aborted) {
        await reader.cancel()
        signal.throwIfAborted()
      }
      const { done, value } = await reader.read()
      if (done) return length
      length += value.length
      if (length > budget) {
        await reader.cancel()
        return length
      }
    }
  } catch (error) {
    if (signal?.aborted) throw error
    // a broken stream is left for the reader to reject; what it gave so far counts
    return length
  }
}
