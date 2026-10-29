import type { ChunkAnchor } from '@/shared/api/types'

/**
 * Finds a chunk's anchor quotes in rendered text (the Markdown/plain text preview) and returns
 * them as a DOM Range. Text on both sides is compared case-insensitively without whitespace and
 * without Markdown markup characters, since the quotes come from the Markdown source while the
 * preview shows it rendered; link targets (`](…)`) are dropped from the quotes for the same reason.
 */

const IGNORED = /[\s*_`#>|~[\]]/

/** A shorter probe when the full quote is not found, as for the PDF text layer. */
const FALLBACK_PROBE_LENGTH = 24

export function comparableText(text: string): string {
  let out = ''
  for (const char of text.replace(/\]\([^)]*\)/g, ']')) {
    if (!IGNORED.test(char)) out += char.toLowerCase()
  }
  return out
}

interface Position {
  node: Text
  offset: number
}

/** The comparable text of every text node below `root`, with each character's DOM position. */
function collect(root: Node): { text: string; positions: Position[] } {
  const walker = root.ownerDocument!.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  let text = ''
  const positions: Position[] = []
  for (let node = walker.nextNode(); node; node = walker.nextNode()) {
    const value = node.nodeValue ?? ''
    for (let offset = 0; offset < value.length; offset++) {
      const char = value[offset]
      if (IGNORED.test(char)) continue
      for (const lower of char.toLowerCase()) {
        text += lower
        positions.push({ node: node as Text, offset })
      }
    }
  }
  return { text, positions }
}

function findStart(text: string, quote: string): { start: number; end: number } | null {
  const probe = comparableText(quote)
  if (!probe) return null
  const exact = text.indexOf(probe)
  if (exact >= 0) return { start: exact, end: exact + probe.length }
  const head = probe.slice(0, FALLBACK_PROBE_LENGTH)
  const near = head.length < probe.length ? text.indexOf(head) : -1
  return near >= 0 ? { start: near, end: near + head.length } : null
}

function findEnd(text: string, quote: string, from: number): number {
  const probe = comparableText(quote)
  if (!probe) return -1
  const exact = text.indexOf(probe, from)
  if (exact >= 0) return exact + probe.length
  const tail = probe.slice(-FALLBACK_PROBE_LENGTH)
  const near = tail.length < probe.length ? text.indexOf(tail, from) : -1
  return near >= 0 ? near + tail.length : -1
}

/**
 * The cited passage below `root` - from the start quote to the end quote, or the start quote
 * alone when the end is missing or not found after it; `null` when the start quote is not found.
 */
export function passageRange(root: Node, anchor: ChunkAnchor | null | undefined): Range | null {
  if (!anchor?.quoteStart) return null
  const { text, positions } = collect(root)
  const start = findStart(text, anchor.quoteStart)
  if (!start) return null
  const end = anchor.quoteEnd ? findEnd(text, anchor.quoteEnd, start.start) : -1
  const last = positions[(end > start.start ? end : start.end) - 1]
  const range = root.ownerDocument!.createRange()
  range.setStart(positions[start.start].node, positions[start.start].offset)
  range.setEnd(last.node, last.offset + 1)
  return range
}
