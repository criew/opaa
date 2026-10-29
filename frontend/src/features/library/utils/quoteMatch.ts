/**
 * Finds a chunk's anchor quotes in the text items of a rendered page (a PDF text layer) and names
 * the items to mark. Text on both sides is compared case-insensitively and without any whitespace:
 * a text layer splits lines, words and even single words (at a font change) into separate items,
 * so neither the spaces between items nor those inside them can be trusted.
 */

/** The marked items of one page, `[first, last]` inclusive, or `null` when nothing matched. */
export type MarkedRange = readonly [number, number] | null

/** How the passage relates to the page being marked. */
export interface PageRole {
  /** The passage starts on this page - its start is searched via `quoteStart`. */
  startsHere: boolean
  /** The passage ends on this page - its end is searched via `quoteEnd` (or `quoteStart`). */
  endsHere: boolean
}

/** A shorter probe when the full quote is not found: an original changed or a ligature differs. */
const FALLBACK_PROBE_LENGTH = 24

export function normalizedText(text: string): string {
  return text.replace(/\s+/g, '').toLowerCase()
}

interface JoinedItems {
  text: string
  /** Start offset of each item in {@link text}. */
  starts: number[]
}

function join(items: readonly string[]): JoinedItems {
  const starts: number[] = []
  let text = ''
  for (const item of items) {
    starts.push(text.length)
    text += normalizedText(item)
  }
  return { text, starts }
}

/** The item holding offset `offset` of the joined text. */
function itemAt(starts: readonly number[], offset: number): number {
  let item = 0
  while (item + 1 < starts.length && starts[item + 1] <= offset) {
    item++
  }
  return item
}

/** Where `quote` sits in `text`, falling back to its head; `null` when neither is found. */
function find(text: string, quote: string, from = 0): { start: number; end: number } | null {
  const probe = normalizedText(quote)
  if (probe.length === 0) {
    return null
  }
  const exact = text.indexOf(probe, from)
  if (exact >= 0) {
    return { start: exact, end: exact + probe.length }
  }
  const head = probe.slice(0, FALLBACK_PROBE_LENGTH)
  const near = head.length < probe.length ? text.indexOf(head, from) : -1
  return near >= 0 ? { start: near, end: near + head.length } : null
}

/** End offset of `quote` in `text`, its tail as the fallback probe; -1 when neither is found. */
function findEnd(text: string, quote: string, from: number): number {
  const probe = normalizedText(quote)
  if (probe.length === 0) {
    return -1
  }
  const exact = text.indexOf(probe, from)
  if (exact >= 0) {
    return exact + probe.length
  }
  const tail = probe.slice(-FALLBACK_PROBE_LENGTH)
  const near = tail.length < probe.length ? text.indexOf(tail, from) : -1
  return near >= 0 ? near + tail.length : -1
}

/** Whether `quote` itself - no shortened probe - occurs in the joined items. */
export function containsQuote(items: readonly string[], quote: string): boolean {
  const probe = normalizedText(quote)
  return probe.length > 0 && join(items).text.includes(probe)
}

/**
 * The items of a page to mark for a passage quoted by `quoteStart`/`quoteEnd`. A page
 * in the middle of a passage is marked whole; the start or end page from or to its quote. A quote
 * that is not found on its page leaves the page unmarked, so a viewer can say so instead of marking
 * the wrong text.
 */
export function markedItems(
  items: readonly string[],
  quoteStart: string | null | undefined,
  quoteEnd: string | null | undefined,
  role: PageRole,
): MarkedRange {
  if (items.length === 0) {
    return null
  }
  const { text, starts } = join(items)
  let from = 0
  let to = text.length
  if (role.startsHere) {
    if (!quoteStart) {
      return null
    }
    const start = find(text, quoteStart)
    if (start === null) {
      return null
    }
    from = start.start
    to = start.end
  }
  if (role.endsHere) {
    if (quoteEnd) {
      const end = findEnd(text, quoteEnd, role.startsHere ? from : 0)
      if (end >= 0) {
        to = end
      } else if (!role.startsHere) {
        return null
      }
    } else if (!role.startsHere) {
      return null
    }
  } else {
    to = text.length
  }
  return [itemAt(starts, from), itemAt(starts, Math.max(from, to - 1))]
}
