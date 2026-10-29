import type { ChunkAnchor } from '@/shared/api/types'

/**
 * Finds a chunk's anchor quotes in rendered text (the Markdown/plain text preview) and returns
 * them as a DOM Range. Only letters and digits are compared, case-insensitively: the quotes come
 * from the Markdown source, the preview shows it rendered, so markup, list bullets, table rules and
 * invisible format characters must not count. What the renderer may show or drop - link targets,
 * ordered-list numbers, a code fence's language - is tried both ways.
 */

const IGNORED = /[\s\p{P}\p{S}\p{Cf}]/u

/** A shorter probe when the full quote is not found, as for the PDF text layer. */
const FALLBACK_PROBE_LENGTH = 24

/** Farther than this from its start, an end quote belongs to another passage. */
const MAX_PASSAGE_LENGTH = 20_000

export function comparableText(text: string): string {
  let out = ''
  for (const char of text) {
    if (!IGNORED.test(char)) out += char.toLowerCase()
  }
  return out
}

/** The quote as it may read once rendered, most likely reading first. */
function renderings(quote: string): string[] {
  const withoutFences = quote.replace(/```\S*/g, ' ')
  const variants = [
    withoutFences.replace(/\]\([^)]*\)/g, ']'),
    withoutFences,
    withoutFences.replace(/\]\([^)]*\)/g, ']').replace(/(^|\s)\d+[.)](?=\s)/g, '$1'),
  ]
  return [...new Set(variants.map(comparableText))].filter((variant) => variant.length > 0)
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
  const probes = renderings(quote)
  for (const probe of probes) {
    const exact = text.indexOf(probe)
    if (exact >= 0) return { start: exact, end: exact + probe.length }
  }
  for (const probe of probes) {
    const head = probe.slice(0, FALLBACK_PROBE_LENGTH)
    const near = head.length < probe.length ? text.indexOf(head) : -1
    if (near >= 0) return { start: near, end: near + head.length }
  }
  return null
}

function findEnd(text: string, quote: string, from: number): number {
  const probes = renderings(quote)
  const within = (index: number) => index >= 0 && index - from <= MAX_PASSAGE_LENGTH
  for (const probe of probes) {
    const exact = text.indexOf(probe, from)
    if (within(exact)) return exact + probe.length
  }
  for (const probe of probes) {
    const tail = probe.slice(-FALLBACK_PROBE_LENGTH)
    const near = tail.length < probe.length ? text.indexOf(tail, from) : -1
    if (within(near)) return near + tail.length
  }
  return -1
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
  // the passage's closing punctuation is not compared but belongs to it
  let endOffset = last.offset + 1
  const tail = last.node.nodeValue ?? ''
  while (endOffset < tail.length && /\p{P}/u.test(tail[endOffset])) endOffset++
  range.setEnd(last.node, endOffset)
  return range
}
