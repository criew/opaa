import type JSZip from 'jszip'

/** More entries than a Word file has; an archive with more is refused unread. */
const MAX_ENTRIES = 10_000

type CountedEntry = JSZip.JSZipObject & {
  internalStream(type: 'uint8array'): JSZip.JSZipStreamHelper<Uint8Array>
}

/**
 * Whether the entries of a ZIP archive unpack to more than `limit` bytes together, whatever sizes
 * they declare. The archive is read by jszip, the reader docx-preview renders with, so both see
 * the same entries; each entry is unpacked as a stream and counted, and the count stops as soon as
 * it passes `limit`, so a bomb never unpacks in full. What jszip cannot load or unpack, and an
 * archive of more than {@link MAX_ENTRIES} entries, counts as too large. `signal` stops the count
 * with its abort reason.
 */
export async function unpacksBeyond(
  bytes: Uint8Array,
  limit: number,
  signal?: AbortSignal,
): Promise<boolean> {
  // jszip loads with the Word view only, as docx-preview does
  const { default: reader } = await import('jszip')
  let zip: JSZip
  try {
    zip = await reader.loadAsync(bytes)
  } catch {
    return true
  }
  signal?.throwIfAborted()
  const entries = Object.values(zip.files).filter((entry) => !entry.dir)
  if (entries.length > MAX_ENTRIES) return true
  let total = 0
  for (const entry of entries) {
    signal?.throwIfAborted()
    total += await unpackedLength(entry as CountedEntry, limit - total, signal)
    if (total > limit) return true
  }
  return false
}

/**
 * Unpacks `entry` as a stream and counts its bytes, pausing once the count passes `budget`.
 * `Infinity` where jszip cannot unpack it.
 */
function unpackedLength(entry: CountedEntry, budget: number, signal?: AbortSignal) {
  return new Promise<number>((resolve, reject) => {
    let length = 0
    let settled = false
    const stream = entry.internalStream('uint8array')
    const finish = (settle: () => void) => {
      if (settled) return
      settled = true
      stream.pause()
      signal?.removeEventListener('abort', aborted)
      settle()
    }
    const aborted = () => finish(() => reject(signal?.reason))
    signal?.addEventListener('abort', aborted, { once: true })
    stream
      .on('data', (chunk) => {
        length += chunk.length
        if (length > budget) finish(() => resolve(length))
      })
      .on('error', () => finish(() => resolve(Infinity)))
      .on('end', () => finish(() => resolve(length)))
      .resume()
  })
}
